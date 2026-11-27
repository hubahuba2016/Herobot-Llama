package com.herobot.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.herobot.engine.MathEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

sealed interface OnDeviceEngineState {
    data object Unloaded : OnDeviceEngineState
    data class Loading(val accelerator: LocalAccelerator) : OnDeviceEngineState
    data class Loaded(
        val modelPath: String,
        val accelerator: LocalAccelerator
    ) : OnDeviceEngineState

    data class Error(val message: String) : OnDeviceEngineState
}

sealed interface OnDeviceStreamEvent {
    data class Started(val modelName: String, val accelerator: LocalAccelerator) : OnDeviceStreamEvent
    data class Chunk(val accumulatedText: String, val modelName: String) : OnDeviceStreamEvent
    data class Completed(val fullText: String, val modelName: String) : OnDeviceStreamEvent
    data class Error(val message: String) : OnDeviceStreamEvent
}

/**
 * Runs Gemma 3 1B (.litertlm) natively on-device using the LiteRT-LM Kotlin API.
 * All Engine and Conversation operations execute on a dedicated single-thread executor.
 */
class OnDeviceGemmaService(context: Context) {

    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "litertlm-gemma3-thread").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val serviceScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var loadedAccelerator: LocalAccelerator = LocalAccelerator.CPU
    private var loadedTemperature: Double = DEFAULT_TEMPERATURE
    private var loadedTopP: Double = DEFAULT_TOP_P
    private var hasPrimedConversation = false
    private var activeSessionId: String? = null
    private var syncedHistorySize: Int = 0

    @Volatile
    private var isGenerating = false

    private val _engineState = MutableStateFlow<OnDeviceEngineState>(OnDeviceEngineState.Unloaded)
    val engineState: StateFlow<OnDeviceEngineState> = _engineState.asStateFlow()

    suspend fun loadModel(
        modelPath: String,
        accelerator: LocalAccelerator,
        temperature: Double = DEFAULT_TEMPERATURE,
        topP: Double = DEFAULT_TOP_P
    ): Result<LocalAccelerator> = withContext(dispatcher) {
        mutex.withLock {
            loadModelInternal(modelPath, accelerator, temperature, topP)
        }
    }

    private suspend fun loadModelInternal(
        modelPath: String,
        accelerator: LocalAccelerator,
        temperature: Double,
        topP: Double
    ): Result<LocalAccelerator> {
        val currentEngine = engine
        if (currentEngine != null &&
            currentEngine.engineConfig.modelPath == modelPath &&
            loadedAccelerator == accelerator &&
            conversation != null
        ) {
            return Result.success(loadedAccelerator)
        }

        releaseInternal()
        _engineState.value = OnDeviceEngineState.Loading(accelerator)

        val candidates = if (accelerator == LocalAccelerator.GPU) {
            listOf(LocalAccelerator.GPU, LocalAccelerator.CPU)
        } else {
            listOf(LocalAccelerator.CPU)
        }

        var lastError: Exception? = null
        for (candidate in candidates) {
            var candidateEngine: Engine? = null
            try {
                val backend = when (candidate) {
                    LocalAccelerator.CPU -> Backend.CPU()
                    LocalAccelerator.GPU -> Backend.GPU()
                }
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    cacheDir = appContext.cacheDir.path
                )
                candidateEngine = Engine(config).also { it.initialize() }
                val conv = createConversation(candidateEngine, temperature, topP)

                engine = candidateEngine
                conversation = conv
                loadedAccelerator = candidate
                loadedTemperature = temperature
                loadedTopP = topP
                hasPrimedConversation = false
                activeSessionId = null
                syncedHistorySize = 0
                _engineState.value = OnDeviceEngineState.Loaded(modelPath, candidate)
                return Result.success(candidate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize LiteRT-LM on $candidate backend: ${e.message}")
                lastError = e
                // Never close an engine whose initialize() threw; only close conversation/engine if initialized
                try {
                    if (engine === candidateEngine && engine != null) {
                        engine = null
                    }
                } catch (_: Exception) {
                }
            }
        }

        val errMsg = lastError?.localizedMessage ?: "Failed to initialize on-device Gemma 3 1B."
        _engineState.value = OnDeviceEngineState.Error(errMsg)
        return Result.failure(lastError ?: IllegalStateException(errMsg))
    }

    private fun createConversation(
        targetEngine: Engine,
        temperature: Double,
        topP: Double
    ): Conversation {
        val safeTemp = temperature.coerceIn(0.0, 2.0)
        val safeTopP = topP.coerceIn(0.05, 1.0)
        return targetEngine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(SYSTEM_INSTRUCTION),
                samplerConfig = SamplerConfig(
                    topK = DEFAULT_TOP_K,
                    topP = safeTopP,
                    temperature = safeTemp
                )
            )
        )
    }

    fun sendMessageStream(
        modelPath: String,
        accelerator: LocalAccelerator,
        userPrompt: String,
        priorHistory: List<Pair<String, String>>,
        learnedMemories: List<Pair<String, String>>,
        localHint: String?,
        temperature: Double = DEFAULT_TEMPERATURE,
        topP: Double = DEFAULT_TOP_P,
        sessionId: String = "default",
        crossSessionMemories: List<String> = emptyList()
    ): Flow<OnDeviceStreamEvent> = flow {
        val loadResult = mutex.withLock {
            val res = loadModelInternal(modelPath, accelerator, temperature, topP)
            if (res.isSuccess) {
                val activeEngine = engine
                val needsRecreate = loadedTemperature != temperature ||
                    loadedTopP != topP ||
                    (activeSessionId != null && activeSessionId != sessionId) ||
                    priorHistory.size < syncedHistorySize
                if (activeEngine != null && needsRecreate) {
                    conversation?.close()
                    conversation = createConversation(activeEngine, temperature, topP)
                    loadedTemperature = temperature
                    loadedTopP = topP
                    hasPrimedConversation = false
                    syncedHistorySize = 0
                }
                activeSessionId = sessionId
            }
            res
        }

        val activeAccelerator = loadResult.getOrElse { err ->
            emit(
                OnDeviceStreamEvent.Error(
                    err.localizedMessage ?: "Unable to load on-device Gemma 3 1B."
                )
            )
            return@flow
        }

        emit(OnDeviceStreamEvent.Started(LocalModelManager.MODEL_ID, activeAccelerator))

        val accumulated = StringBuilder()
        mutex.withLock {
            val activeConversation = conversation
            if (activeConversation == null) {
                emit(OnDeviceStreamEvent.Error("On-device conversation is not initialized."))
                return@flow
            }
            val unsyncedHistory = if (!hasPrimedConversation) {
                priorHistory
            } else {
                priorHistory.drop(syncedHistorySize.coerceAtMost(priorHistory.size))
            }
            val effectivePrompt = buildPromptForTurn(
                userPrompt = userPrompt,
                unsyncedHistory = unsyncedHistory,
                learnedMemories = learnedMemories,
                crossSessionMemories = crossSessionMemories,
                localHint = localHint
            )
            isGenerating = true
            try {
                activeConversation.sendMessageAsync(effectivePrompt).collect { chunk ->
                    val delta = chunk.toString()
                    if (delta.isNotEmpty()) {
                        accumulated.append(delta)
                        emit(
                            OnDeviceStreamEvent.Chunk(
                                accumulatedText = accumulated.toString(),
                                modelName = LocalModelManager.MODEL_ID
                            )
                        )
                    }
                }
                if (accumulated.isNotBlank()) {
                    hasPrimedConversation = true
                    activeSessionId = sessionId
                    syncedHistorySize = priorHistory.size + 2
                }
            } catch (e: CancellationException) {
                activeConversation.cancelProcess()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "On-device Gemma 3 1B generation error", e)
                emit(
                    OnDeviceStreamEvent.Error(
                        e.localizedMessage ?: "On-device Gemma 3 1B generation failed."
                    )
                )
                return@flow
            } finally {
                isGenerating = false
            }
        }

        val finalText = accumulated.toString().trim()
        if (finalText.isNotEmpty()) {
            emit(OnDeviceStreamEvent.Completed(finalText, LocalModelManager.MODEL_ID))
        } else {
            emit(OnDeviceStreamEvent.Error("On-device Gemma 3 1B returned an empty response."))
        }
    }.flowOn(dispatcher)

    private fun buildPromptForTurn(
        userPrompt: String,
        unsyncedHistory: List<Pair<String, String>>,
        learnedMemories: List<Pair<String, String>>,
        crossSessionMemories: List<String>,
        localHint: String?
    ): String {
        val includeHistory = unsyncedHistory.isNotEmpty()
        val includeLearned = !hasPrimedConversation && learnedMemories.isNotEmpty()
        val includeCrossMemory = crossSessionMemories.isNotEmpty()
        val isMath = MathEngine.isMathematicsRequest(userPrompt)

        if (!includeHistory && !includeLearned && !includeCrossMemory && localHint.isNullOrBlank() && !isMath) {
            return userPrompt
        }

        return buildString {
            if (includeCrossMemory) {
                append("Memory from previous saved conversations:\n")
                for (memory in crossSessionMemories.take(5)) {
                    append("- ").append(memory.take(200)).append('\n')
                }
                append('\n')
            }
            if (includeLearned) {
                append("User-taught facts:\n")
                for ((q, a) in learnedMemories.takeLast(4)) {
                    append("- ").append(q).append(" -> ").append(a).append('\n')
                }
                append('\n')
            }
            if (includeHistory) {
                append("Recent conversation context:\n")
                for ((role, text) in unsyncedHistory.takeLast(8)) {
                    append(role).append(": ").append(text.take(400)).append('\n')
                }
                append('\n')
            }
            if (!localHint.isNullOrBlank()) {
                append("Local calculation reference: ").append(localHint).append("\n\n")
            }
            if (isMath) {
                append("Show clear steps and verify the answer.\n")
            }
            append(userPrompt)
        }
    }

    fun resetConversation() {
        serviceScope.launch {
            mutex.withLock {
                while (isGenerating) {
                    conversation?.cancelProcess()
                    delay(100)
                }
                activeSessionId = null
                syncedHistorySize = 0
                hasPrimedConversation = false
                val activeEngine = engine ?: return@withLock
                conversation?.close()
                conversation = createConversation(activeEngine, loadedTemperature, loadedTopP)
            }
        }
    }

    fun unload() {
        serviceScope.launch {
            mutex.withLock {
                releaseInternal()
            }
        }
    }

    fun shutdown() {
        serviceScope.launch {
            mutex.withLock {
                releaseInternal()
            }
            executor.shutdown()
        }.invokeOnCompletion {
            serviceScope.cancel()
        }
    }

    private suspend fun releaseInternal() {
        while (isGenerating) {
            conversation?.cancelProcess()
            delay(100)
        }
        try {
            conversation?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing conversation: ${e.message}")
        } finally {
            conversation = null
        }
        try {
            engine?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing engine: ${e.message}")
        } finally {
            engine = null
        }
        hasPrimedConversation = false
        activeSessionId = null
        syncedHistorySize = 0
        _engineState.value = OnDeviceEngineState.Unloaded
    }

    companion object {
        private const val TAG = "OnDeviceGemmaService"
        private const val DEFAULT_TOP_K = 40
        private const val DEFAULT_TOP_P = 0.9
        private const val DEFAULT_TEMPERATURE = 0.7

        private const val SYSTEM_INSTRUCTION =
            "You are HeroBot, a helpful conversational Android assistant running on-device. " +
                "Use conversation context to understand follow-ups, answer clearly and concisely, " +
                "and support both English and Indonesian naturally."
    }
}
