package com.herobot.ui

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestoreException
import com.herobot.ai.AssistantRole
import com.herobot.ai.GeminiChatService
import com.herobot.ai.GeminiModelRouter
import com.herobot.ai.GeminiStreamEvent
import com.herobot.ai.LocalAccelerator
import com.herobot.ai.LocalModelManager
import com.herobot.ai.LocalModelStatus
import com.herobot.ai.OnDeviceEngineState
import com.herobot.ai.OnDeviceGemmaService
import com.herobot.ai.OnDeviceStreamEvent
import com.herobot.ai.ReasoningSelection
import com.herobot.data.DBHelper
import com.herobot.data.UserApiKeyStore
import com.herobot.data.remote.UserChatRepository
import com.herobot.engine.ChatbotEngine
import com.herobot.engine.MathEngine
import com.herobot.engine.NLPHelper
import com.herobot.engine.OllamaChatTurn
import com.herobot.engine.OllamaClient
import com.herobot.engine.OllamaStreamEvent
import com.herobot.model.BotResponse
import com.herobot.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isProcessing: Boolean = false,
    val isSeeding: Boolean = true,
    val knowledgeCount: Int = 0,
    val isTtsEnabled: Boolean = true,
    val useGeminiReasoning: Boolean = true,
    val isUsingOllamaFallback: Boolean = false,
    val isUsingOnDeviceGemma: Boolean = false,
    val reasoningSelection: ReasoningSelection = ReasoningSelection.Auto,
    val activeRole: AssistantRole = AssistantRole.GENERAL,
    val activeModelName: String = AssistantRole.GENERAL.modelName,
    val errorMessage: String? = null,
    val lastFailedPrompt: String? = null,
    val showTrainDialog: Boolean = false,
    val showParametersDialog: Boolean = false,
    val showAccountDialog: Boolean = false,
    val parameters: Map<String, String> = emptyMap(),
    val userDisplayName: String = "HeroBot User",
    val userEmail: String? = null,
    val hasPersonalApiKey: Boolean = false,
    val personalApiKey: String = "",
    val localModelStatus: LocalModelStatus = LocalModelStatus.NotDownloaded,
    val onDeviceEngineState: OnDeviceEngineState = OnDeviceEngineState.Unloaded,
    val localAccelerator: LocalAccelerator = LocalAccelerator.CPU,
    val localModelUrl: String = LocalModelManager.DEFAULT_DOWNLOAD_URL,
    val huggingFaceToken: String = ""
)

class ChatViewModel(
    private val localEngine: ChatbotEngine,
    private val userRepository: UserChatRepository,
    private val apiKeyStore: UserApiKeyStore,
    private val localModelManager: LocalModelManager,
    private val onDeviceGemmaService: OnDeviceGemmaService,
    private val currentUserId: String,
    userDisplayName: String?,
    userEmail: String?,
    private val userPhotoUrl: String?
) : ViewModel() {

    private val geminiService = GeminiChatService()

    private var activeGenerationJob: Job? = null
    private var persistedMessages: List<ChatMessage> = emptyList()
    private var pendingUserMessage: ChatMessage? = null
    private var streamingBotMessage: ChatMessage? = null
    private var userLearnedQa: Map<String, String> = emptyMap()

    private val initialPersonalKey = apiKeyStore.getPersonalApiKey(currentUserId).orEmpty()

    private val _uiState = MutableStateFlow(
        ChatUiState(
            userDisplayName = userDisplayName?.trim()?.takeIf { it.isNotEmpty() }
                ?: userEmail?.substringBefore('@')?.takeIf { it.isNotEmpty() }
                ?: "HeroBot User",
            userEmail = userEmail,
            hasPersonalApiKey = initialPersonalKey.isNotEmpty(),
            personalApiKey = initialPersonalKey,
            localModelStatus = localModelManager.status.value,
            onDeviceEngineState = onDeviceGemmaService.engineState.value,
            localAccelerator = localModelManager.getSavedAccelerator(),
            localModelUrl = localModelManager.getSavedDownloadUrl(),
            huggingFaceToken = localModelManager.getSavedHfToken()
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _ttsEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val ttsEvents: SharedFlow<String> = _ttsEvents.asSharedFlow()

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            val (count, params) = withContext(Dispatchers.IO) {
                val seededCount = localEngine.ensureSeeded()
                val loadedParams = localEngine.getParameters()
                seededCount to loadedParams
            }
            _uiState.update {
                it.copy(
                    isSeeding = false,
                    knowledgeCount = count,
                    parameters = params
                )
            }
        }

        viewModelScope.launch {
            localModelManager.status.collect { status ->
                _uiState.update { it.copy(localModelStatus = status) }
            }
        }

        viewModelScope.launch {
            onDeviceGemmaService.engineState.collect { engineState ->
                _uiState.update { it.copy(onDeviceEngineState = engineState) }
            }
        }

        viewModelScope.launch {
            val result = userRepository.ensureUserProfile(
                userId = currentUserId,
                displayName = userDisplayName,
                email = userEmail,
                photoUrl = userPhotoUrl
            )
            result.onFailure { err ->
                Log.w(TAG, "Profile sync warning: ${err.message}")
            }
        }

        observeUserMessages()
        observeUserLearnedQa()
    }

    private fun observeUserMessages() {
        viewModelScope.launch {
            userRepository.observeMessages(currentUserId)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val rootError = firestoreError ?: error
                    Log.w(TAG, "Error observing user messages", rootError)
                    _uiState.update {
                        it.copy(errorMessage = rootError.localizedMessage ?: "Failed to sync chat history.")
                    }
                }
                .collect { records ->
                    persistedMessages = records.map { it.toChatMessage() }
                    val currentPending = pendingUserMessage
                    if (currentPending != null && persistedMessages.any {
                            it.type == ChatMessage.Type.USER &&
                                it.timestampMillis == currentPending.timestampMillis &&
                                it.text == currentPending.text
                        }
                    ) {
                        pendingUserMessage = null
                    }
                    if (streamingBotMessage == null) {
                        geminiService.restoreHistory(persistedMessages)
                    }
                    refreshVisibleMessages()
                }
        }
    }

    private fun observeUserLearnedQa() {
        viewModelScope.launch {
            userRepository.observeLearnedQa(currentUserId)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val rootError = firestoreError ?: error
                    Log.w(TAG, "Error observing learned Q&A", rootError)
                }
                .collect { records ->
                    val pairs = records.map { it.question to it.answer }
                    userLearnedQa = records.associate {
                        NLPHelper.normalize(it.question) to it.answer
                    }
                    geminiService.restoreLearnedFacts(pairs)
                }
        }
    }

    private fun refreshVisibleMessages() {
        val combined = buildList {
            addAll(persistedMessages)
            pendingUserMessage?.let { add(it) }
            streamingBotMessage?.let { add(it) }
        }
        _uiState.update { it.copy(messages = combined) }
    }

    fun submitInput(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty() || _uiState.value.isProcessing) return

        val lower = trimmed.lowercase()
        when {
            lower.startsWith("train:") -> handleInlineTrainCommand(trimmed)
            lower == "train" -> openTrainDialog()
            else -> sendMessage(trimmed)
        }
    }

    fun sendMessage(text: String, appendUserMessage: Boolean = true) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _uiState.value.isProcessing) return

        val stateSnapshot = _uiState.value
        val userMessage = if (appendUserMessage) {
            ChatMessage(text = trimmed, type = ChatMessage.Type.USER)
        } else {
            null
        }

        if (userMessage != null) {
            pendingUserMessage = userMessage
            refreshVisibleMessages()
            viewModelScope.launch {
                val docId = "u_${userMessage.timestampMillis}_${userMessage.id}"
                userRepository.saveMessage(currentUserId, docId, userMessage).onFailure { err ->
                    Log.w(TAG, "Failed to persist user message: ${err.message}")
                }
            }
        }

        if (stateSnapshot.useGeminiReasoning) {
            sendGeminiMessage(
                prompt = trimmed,
                selection = stateSnapshot.reasoningSelection,
                personalApiKey = stateSnapshot.personalApiKey.takeIf { it.isNotBlank() }
            )
        } else {
            sendLocalEngineMessage(trimmed)
        }
    }

    private fun sendGeminiMessage(
        prompt: String,
        selection: ReasoningSelection,
        personalApiKey: String?
    ) {
        val resolvedRole = GeminiModelRouter.resolveRole(prompt, selection)
        val placeholderBotMessage = ChatMessage(
            text = "",
            type = ChatMessage.Type.BOT,
            source = BotResponse.Source.GEMINI,
            modelId = resolvedRole.modelName,
            roleTitle = resolvedRole.title,
            isStreaming = true
        )

        streamingBotMessage = placeholderBotMessage
        _uiState.update { state ->
            state.copy(
                isProcessing = true,
                isUsingOllamaFallback = false,
                isUsingOnDeviceGemma = false,
                activeRole = resolvedRole,
                activeModelName = resolvedRole.modelName,
                errorMessage = null,
                lastFailedPrompt = null
            )
        }
        refreshVisibleMessages()

        activeGenerationJob?.cancel()
        activeGenerationJob = viewModelScope.launch {
            val localHint = withContext(Dispatchers.IO) {
                MathEngine.solveLinearEquation(prompt)
                    ?: MathEngine.evaluateTrigonometricExpression(prompt)
                    ?: MathEngine.evaluateArithmeticExpression(prompt)
            }

            geminiService.sendMessageStream(
                userPrompt = prompt,
                selection = selection,
                localHint = localHint,
                personalApiKey = personalApiKey
            ).collect { event ->
                when (event) {
                    is GeminiStreamEvent.Started -> {
                        streamingBotMessage = streamingBotMessage?.copy(
                            modelId = event.modelName,
                            roleTitle = event.role.title
                        )
                        _uiState.update { state ->
                            state.copy(
                                activeRole = event.role,
                                activeModelName = event.modelName
                            )
                        }
                        refreshVisibleMessages()
                    }

                    is GeminiStreamEvent.Chunk -> {
                        streamingBotMessage = streamingBotMessage?.copy(
                            text = event.accumulatedText,
                            modelId = event.modelName,
                            roleTitle = event.role.title,
                            isStreaming = true
                        )
                        _uiState.update { state ->
                            state.copy(
                                activeRole = event.role,
                                activeModelName = event.modelName
                            )
                        }
                        refreshVisibleMessages()
                    }

                    is GeminiStreamEvent.Completed -> {
                        val completedMsg = (streamingBotMessage ?: placeholderBotMessage).copy(
                            text = event.fullText,
                            modelId = event.modelName,
                            roleTitle = event.role.title,
                            isStreaming = false
                        )
                        streamingBotMessage = null
                        _uiState.update { state ->
                            state.copy(
                                isProcessing = false,
                                isUsingOllamaFallback = false,
                                isUsingOnDeviceGemma = false,
                                activeRole = event.role,
                                activeModelName = event.modelName,
                                errorMessage = null,
                                lastFailedPrompt = null
                            )
                        }

                        val docId = "b_${completedMsg.timestampMillis}_${completedMsg.id}"
                        val saveResult = userRepository.saveMessage(currentUserId, docId, completedMsg)
                        if (saveResult.isFailure) {
                            persistedMessages = persistedMessages + completedMsg
                        }
                        refreshVisibleMessages()

                        if (_uiState.value.isTtsEnabled) {
                            _ttsEvents.tryEmit(event.fullText)
                        }
                    }

                    is GeminiStreamEvent.Error -> {
                        val partial = streamingBotMessage?.takeIf { it.text.isNotBlank() }?.copy(
                            isStreaming = false
                        )
                        if (partial != null) {
                            streamingBotMessage = null
                            val docId = "b_${partial.timestampMillis}_${partial.id}"
                            userRepository.saveMessage(currentUserId, docId, partial)
                            _uiState.update { state ->
                                state.copy(
                                    isProcessing = false,
                                    isUsingOllamaFallback = false,
                                    isUsingOnDeviceGemma = false,
                                    errorMessage = event.message,
                                    lastFailedPrompt = prompt
                                )
                            }
                            refreshVisibleMessages()
                        } else {
                            fallbackToLocalAi(
                                prompt = prompt,
                                localHint = localHint,
                                geminiError = event.message,
                                placeholderBotMessage = placeholderBotMessage
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun fallbackToLocalAi(
        prompt: String,
        localHint: String?,
        geminiError: String,
        placeholderBotMessage: ChatMessage
    ) {
        val readyModelPath = localModelManager.getReadyModelPath()
        if (readyModelPath != null) {
            val onDeviceSucceeded = runOnDeviceGemmaFallback(
                modelPath = readyModelPath,
                prompt = prompt,
                localHint = localHint,
                placeholderBotMessage = placeholderBotMessage,
                announceToast = true
            )
            if (onDeviceSucceeded) return
        }

        fallbackToOllama(
            prompt = prompt,
            localHint = localHint,
            geminiError = geminiError,
            placeholderBotMessage = placeholderBotMessage
        )
    }

    private suspend fun runOnDeviceGemmaFallback(
        modelPath: String,
        prompt: String,
        localHint: String?,
        placeholderBotMessage: ChatMessage,
        announceToast: Boolean
    ): Boolean {
        val params = _uiState.value.parameters
        val temperature = params[DBHelper.KEY_TEMPERATURE]?.toDoubleOrNull() ?: 0.7
        val topP = params[DBHelper.KEY_TOP_P]?.toDoubleOrNull() ?: 0.9
        val accelerator = _uiState.value.localAccelerator

        if (announceToast) {
            _toastEvents.tryEmit("Gemini unavailable — using On-Device Gemma 3 1B")
        }

        val priorHistory = persistedMessages
            .filter { !it.isStreaming && it.text.isNotBlank() }
            .takeLast(6)
            .map { msg ->
                val role = if (msg.type == ChatMessage.Type.USER) "User" else "HeroBot"
                role to msg.text.trim()
            }

        streamingBotMessage = placeholderBotMessage.copy(
            text = "",
            source = BotResponse.Source.LLM,
            modelId = LocalModelManager.MODEL_ID,
            roleTitle = ON_DEVICE_GEMMA_ROLE,
            isStreaming = true
        )
        _uiState.update { state ->
            state.copy(
                isUsingOnDeviceGemma = true,
                isUsingOllamaFallback = false,
                activeModelName = LocalModelManager.MODEL_ID
            )
        }
        refreshVisibleMessages()

        var succeeded = false
        onDeviceGemmaService.sendMessageStream(
            modelPath = modelPath,
            accelerator = accelerator,
            userPrompt = prompt,
            priorHistory = priorHistory,
            learnedMemories = userLearnedQa.entries.map { it.key to it.value }.takeLast(4),
            localHint = localHint,
            temperature = temperature,
            topP = topP
        ).collect { event ->
            when (event) {
                is OnDeviceStreamEvent.Started -> {
                    streamingBotMessage = streamingBotMessage?.copy(
                        source = BotResponse.Source.LLM,
                        modelId = event.modelName,
                        roleTitle = ON_DEVICE_GEMMA_ROLE
                    )
                    _uiState.update { state ->
                        state.copy(
                            isUsingOnDeviceGemma = true,
                            activeModelName = event.modelName,
                            localAccelerator = event.accelerator
                        )
                    }
                    refreshVisibleMessages()
                }

                is OnDeviceStreamEvent.Chunk -> {
                    streamingBotMessage = streamingBotMessage?.copy(
                        text = event.accumulatedText,
                        source = BotResponse.Source.LLM,
                        modelId = event.modelName,
                        roleTitle = ON_DEVICE_GEMMA_ROLE,
                        isStreaming = true
                    )
                    refreshVisibleMessages()
                }

                is OnDeviceStreamEvent.Completed -> {
                    val completedMsg = (streamingBotMessage ?: placeholderBotMessage).copy(
                        text = event.fullText,
                        source = BotResponse.Source.LLM,
                        modelId = event.modelName,
                        roleTitle = ON_DEVICE_GEMMA_ROLE,
                        isStreaming = false
                    )
                    streamingBotMessage = null
                    geminiService.appendTurn(prompt, event.fullText)
                    _uiState.update { state ->
                        state.copy(
                            isProcessing = false,
                            isUsingOnDeviceGemma = false,
                            isUsingOllamaFallback = false,
                            activeModelName = event.modelName,
                            errorMessage = null,
                            lastFailedPrompt = null
                        )
                    }

                    val docId = "b_${completedMsg.timestampMillis}_${completedMsg.id}"
                    val saveResult = userRepository.saveMessage(currentUserId, docId, completedMsg)
                    if (saveResult.isFailure) {
                        persistedMessages = persistedMessages + completedMsg
                    }
                    refreshVisibleMessages()

                    if (_uiState.value.isTtsEnabled) {
                        _ttsEvents.tryEmit(event.fullText)
                    }
                    succeeded = true
                }

                is OnDeviceStreamEvent.Error -> {
                    Log.w(TAG, "On-device Gemma 3 1B failed: ${event.message}")
                    val partial = streamingBotMessage?.takeIf { it.text.isNotBlank() }?.copy(
                        isStreaming = false
                    )
                    streamingBotMessage = null
                    if (partial != null) {
                        val docId = "b_${partial.timestampMillis}_${partial.id}"
                        val saveResult = userRepository.saveMessage(currentUserId, docId, partial)
                        if (saveResult.isFailure) {
                            persistedMessages = persistedMessages + partial
                        }
                        _uiState.update { state ->
                            state.copy(
                                isProcessing = false,
                                isUsingOnDeviceGemma = false,
                                errorMessage = event.message,
                                lastFailedPrompt = prompt
                            )
                        }
                        refreshVisibleMessages()
                        succeeded = true
                    } else {
                        _uiState.update { state ->
                            state.copy(isUsingOnDeviceGemma = false)
                        }
                    }
                }
            }
        }
        return succeeded
    }

    private suspend fun fallbackToOllama(
        prompt: String,
        localHint: String?,
        geminiError: String,
        placeholderBotMessage: ChatMessage
    ) {
        val params = _uiState.value.parameters
        val ollamaModel = params[DBHelper.KEY_MODEL]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: OllamaClient.DEFAULT_MODEL
        val ollamaUrl = params[DBHelper.KEY_OLLAMA_URL]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: OllamaClient.DEFAULT_ENDPOINT_URL
        val temperature = params[DBHelper.KEY_TEMPERATURE]?.toDoubleOrNull() ?: 0.7
        val topP = params[DBHelper.KEY_TOP_P]?.toDoubleOrNull() ?: 0.9

        _toastEvents.tryEmit("Gemini unavailable — trying Ollama ($ollamaModel)")

        val historySnapshot = persistedMessages
            .filter { !it.isStreaming && it.text.isNotBlank() }
            .takeLast(16)
            .map { msg ->
                OllamaChatTurn(
                    role = if (msg.type == ChatMessage.Type.USER) "user" else "assistant",
                    text = msg.text.trim()
                )
            }

        streamingBotMessage = placeholderBotMessage.copy(
            text = "",
            source = BotResponse.Source.LLM,
            modelId = ollamaModel,
            roleTitle = OLLAMA_FALLBACK_ROLE,
            isStreaming = true
        )
        _uiState.update { state ->
            state.copy(
                isUsingOllamaFallback = true,
                isUsingOnDeviceGemma = false,
                activeModelName = ollamaModel
            )
        }
        refreshVisibleMessages()

        OllamaClient.streamChat(
            userPrompt = prompt,
            history = historySnapshot,
            learnedMemories = userLearnedQa.entries.map { it.key to it.value }.takeLast(5),
            localHint = localHint,
            model = ollamaModel,
            temperature = temperature,
            topP = topP,
            endpointUrl = ollamaUrl
        ).collect { ollamaEvent ->
            when (ollamaEvent) {
                is OllamaStreamEvent.Started -> {
                    streamingBotMessage = streamingBotMessage?.copy(
                        source = BotResponse.Source.LLM,
                        modelId = ollamaEvent.modelName,
                        roleTitle = OLLAMA_FALLBACK_ROLE
                    )
                    _uiState.update { state ->
                        state.copy(
                            isUsingOllamaFallback = true,
                            activeModelName = ollamaEvent.modelName
                        )
                    }
                    refreshVisibleMessages()
                }

                is OllamaStreamEvent.Chunk -> {
                    streamingBotMessage = streamingBotMessage?.copy(
                        text = ollamaEvent.accumulatedText,
                        source = BotResponse.Source.LLM,
                        modelId = ollamaEvent.modelName,
                        roleTitle = OLLAMA_FALLBACK_ROLE,
                        isStreaming = true
                    )
                    refreshVisibleMessages()
                }

                is OllamaStreamEvent.Completed -> {
                    val completedMsg = (streamingBotMessage ?: placeholderBotMessage).copy(
                        text = ollamaEvent.fullText,
                        source = BotResponse.Source.LLM,
                        modelId = ollamaEvent.modelName,
                        roleTitle = OLLAMA_FALLBACK_ROLE,
                        isStreaming = false
                    )
                    streamingBotMessage = null
                    geminiService.appendTurn(prompt, ollamaEvent.fullText)
                    _uiState.update { state ->
                        state.copy(
                            isProcessing = false,
                            isUsingOllamaFallback = false,
                            isUsingOnDeviceGemma = false,
                            activeModelName = ollamaEvent.modelName,
                            errorMessage = null,
                            lastFailedPrompt = null
                        )
                    }

                    val docId = "b_${completedMsg.timestampMillis}_${completedMsg.id}"
                    val saveResult = userRepository.saveMessage(currentUserId, docId, completedMsg)
                    if (saveResult.isFailure) {
                        persistedMessages = persistedMessages + completedMsg
                    }
                    refreshVisibleMessages()

                    if (_uiState.value.isTtsEnabled) {
                        _ttsEvents.tryEmit(ollamaEvent.fullText)
                    }
                }

                is OllamaStreamEvent.Error -> {
                    val partialOllama = streamingBotMessage?.takeIf { it.text.isNotBlank() }?.copy(
                        isStreaming = false
                    )
                    streamingBotMessage = null
                    if (partialOllama != null) {
                        val docId = "b_${partialOllama.timestampMillis}_${partialOllama.id}"
                        val saveResult = userRepository.saveMessage(currentUserId, docId, partialOllama)
                        if (saveResult.isFailure) {
                            persistedMessages = persistedMessages + partialOllama
                        }
                        _uiState.update { state ->
                            state.copy(
                                isProcessing = false,
                                isUsingOllamaFallback = false,
                                isUsingOnDeviceGemma = false,
                                errorMessage = ollamaEvent.message,
                                lastFailedPrompt = prompt
                            )
                        }
                        refreshVisibleMessages()
                        return@collect
                    }

                    val offlineReply = withContext(Dispatchers.IO) {
                        localEngine.reply(prompt, allowLocalLlm = false)
                    }
                    if (offlineReply.source != BotResponse.Source.NONE) {
                        val offlineMsg = ChatMessage(
                            text = offlineReply.text,
                            type = ChatMessage.Type.BOT,
                            source = offlineReply.source,
                            modelId = offlineReply.modelId,
                            roleTitle = offlineReply.roleTitle ?: "Offline Local"
                        )
                        _uiState.update { state ->
                            state.copy(
                                isProcessing = false,
                                isUsingOllamaFallback = false,
                                isUsingOnDeviceGemma = false,
                                errorMessage = null,
                                lastFailedPrompt = null
                            )
                        }
                        val docId = "b_${offlineMsg.timestampMillis}_${offlineMsg.id}"
                        val saveResult = userRepository.saveMessage(currentUserId, docId, offlineMsg)
                        if (saveResult.isFailure) {
                            persistedMessages = persistedMessages + offlineMsg
                        }
                        refreshVisibleMessages()
                        if (_uiState.value.isTtsEnabled) {
                            _ttsEvents.tryEmit(offlineReply.text)
                        }
                    } else {
                        val hintMsg = if (localModelManager.getReadyModelPath() == null) {
                            "Gemini unavailable ($geminiError). Download On-Device Gemma 3 1B in Settings or start Ollama (${ollamaModel})."
                        } else {
                            "Gemini unavailable ($geminiError). Ollama fallback failed: ${ollamaEvent.message}"
                        }
                        _uiState.update { state ->
                            state.copy(
                                isProcessing = false,
                                isUsingOllamaFallback = false,
                                isUsingOnDeviceGemma = false,
                                errorMessage = hintMsg,
                                lastFailedPrompt = prompt
                            )
                        }
                        refreshVisibleMessages()
                    }
                }
            }
        }
    }

    private fun sendLocalEngineMessage(prompt: String) {
        _uiState.update { state ->
            state.copy(
                isProcessing = true,
                isUsingOllamaFallback = false,
                isUsingOnDeviceGemma = false,
                errorMessage = null,
                lastFailedPrompt = null
            )
        }

        activeGenerationJob?.cancel()
        activeGenerationJob = viewModelScope.launch {
            val userTaughtAnswer = userLearnedQa[NLPHelper.normalize(prompt)]
            if (userTaughtAnswer != null) {
                deliverLocalBotResponse(
                    BotResponse(text = userTaughtAnswer, source = BotResponse.Source.DATABASE)
                )
                return@launch
            }

            // Try built-in math, local Q&A, greetings, or web search first without blocking on LLM
            val quickResponse = withContext(Dispatchers.IO) {
                localEngine.reply(prompt, allowLocalLlm = false)
            }
            if (quickResponse.source != BotResponse.Source.NONE) {
                deliverLocalBotResponse(quickResponse)
                return@launch
            }

            // If On-Device Gemma 3 1B (.litertlm) is downloaded, run it directly inside the app
            val readyModelPath = localModelManager.getReadyModelPath()
            if (readyModelPath != null) {
                val placeholder = ChatMessage(
                    text = "",
                    type = ChatMessage.Type.BOT,
                    source = BotResponse.Source.LLM,
                    modelId = LocalModelManager.MODEL_ID,
                    roleTitle = ON_DEVICE_GEMMA_ROLE,
                    isStreaming = true
                )
                val ranOnDevice = runOnDeviceGemmaFallback(
                    modelPath = readyModelPath,
                    prompt = prompt,
                    localHint = null,
                    placeholderBotMessage = placeholder,
                    announceToast = false
                )
                if (ranOnDevice) return@launch
            }

            // Otherwise try external Ollama server or return final local response
            val fullLocalResponse = withContext(Dispatchers.IO) {
                localEngine.reply(prompt, allowLocalLlm = true)
            }
            deliverLocalBotResponse(fullLocalResponse)
        }
    }

    private suspend fun deliverLocalBotResponse(response: BotResponse) {
        val botMessage = ChatMessage(
            text = response.text,
            type = ChatMessage.Type.BOT,
            source = response.source,
            modelId = response.modelId,
            roleTitle = response.roleTitle
        )
        _uiState.update { it.copy(isProcessing = false, isUsingOnDeviceGemma = false) }
        val docId = "b_${botMessage.timestampMillis}_${botMessage.id}"
        val saveResult = userRepository.saveMessage(currentUserId, docId, botMessage)
        if (saveResult.isFailure) {
            persistedMessages = persistedMessages + botMessage
            refreshVisibleMessages()
        }
        if (_uiState.value.isTtsEnabled) {
            _ttsEvents.tryEmit(response.text)
        }
    }

    fun downloadOnDeviceGemma(url: String, hfToken: String) {
        _uiState.update {
            it.copy(
                localModelUrl = url.trim().ifEmpty { LocalModelManager.DEFAULT_DOWNLOAD_URL },
                huggingFaceToken = hfToken.trim()
            )
        }
        localModelManager.startDownload(url, hfToken)
    }

    fun importOnDeviceGemma(uri: Uri) {
        localModelManager.importFromUri(uri)
    }

    fun cancelOnDeviceDownload() {
        localModelManager.cancelDownload()
    }

    fun deleteOnDeviceGemma() {
        viewModelScope.launch {
            onDeviceGemmaService.unload()
            localModelManager.deleteModel()
            _toastEvents.tryEmit("Removed on-device Gemma 3 1B model")
        }
    }

    fun setLocalAccelerator(accelerator: LocalAccelerator) {
        localModelManager.saveAccelerator(accelerator)
        _uiState.update { it.copy(localAccelerator = accelerator) }
    }

    fun loadOnDeviceGemma() {
        val path = localModelManager.getReadyModelPath() ?: run {
            _toastEvents.tryEmit("Download or import Gemma 3 1B first")
            return
        }
        viewModelScope.launch {
            val params = _uiState.value.parameters
            val temperature = params[DBHelper.KEY_TEMPERATURE]?.toDoubleOrNull() ?: 0.7
            val topP = params[DBHelper.KEY_TOP_P]?.toDoubleOrNull() ?: 0.9
            val result = onDeviceGemmaService.loadModel(
                modelPath = path,
                accelerator = _uiState.value.localAccelerator,
                temperature = temperature,
                topP = topP
            )
            result.onSuccess { activeAccel ->
                _uiState.update { it.copy(localAccelerator = activeAccel) }
                _toastEvents.tryEmit("On-device Gemma 3 1B ready (${activeAccel.label})")
            }.onFailure { err ->
                _toastEvents.tryEmit(err.localizedMessage ?: "Failed to load on-device model")
            }
        }
    }

    fun unloadOnDeviceGemma() {
        onDeviceGemmaService.unload()
        _toastEvents.tryEmit("Unloaded on-device Gemma 3 1B")
    }

    fun retryLastFailedPrompt() {
        val prompt = _uiState.value.lastFailedPrompt ?: return
        sendMessage(prompt, appendUserMessage = false)
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null, lastFailedPrompt = null) }
    }

    fun selectReasoningMode(selection: ReasoningSelection) {
        _uiState.update { state ->
            val previewRole = when (selection) {
                ReasoningSelection.Auto -> state.activeRole
                is ReasoningSelection.Fixed -> selection.role
            }
            state.copy(
                reasoningSelection = selection,
                activeRole = previewRole,
                activeModelName = previewRole.modelName,
                useGeminiReasoning = true
            )
        }
    }

    fun setUseGeminiReasoning(enabled: Boolean) {
        _uiState.update {
            it.copy(
                useGeminiReasoning = enabled,
                isUsingOllamaFallback = false,
                isUsingOnDeviceGemma = false,
                errorMessage = null
            )
        }
    }

    fun train(question: String, answer: String) {
        val q = question.trim()
        val a = answer.trim()
        if (q.isEmpty() || a.isEmpty()) {
            _toastEvents.tryEmit("Both question and answer are required")
            return
        }

        viewModelScope.launch {
            val updatedCount = withContext(Dispatchers.IO) {
                localEngine.train(q, a)
            }
            geminiService.recordLearnedFact(q, a)
            val qaDocId = "qa_${NLPHelper.normalize(q).hashCode().toString().replace('-', 'n')}"
            userRepository.saveLearnedQa(currentUserId, qaDocId, q, a).onFailure { err ->
                Log.w(TAG, "Failed to sync learned Q&A to Firestore: ${err.message}")
            }

            val systemText = "System: HeroBot has learned a new response."
            val systemMsg = ChatMessage(
                text = systemText,
                type = ChatMessage.Type.BOT,
                source = BotResponse.Source.SYSTEM
            )
            val msgDocId = "s_${systemMsg.timestampMillis}_${systemMsg.id}"
            userRepository.saveMessage(currentUserId, msgDocId, systemMsg)

            _uiState.update {
                it.copy(
                    knowledgeCount = updatedCount,
                    showTrainDialog = false
                )
            }
            if (_uiState.value.isTtsEnabled) {
                _ttsEvents.tryEmit(systemText)
            }
        }
    }

    private fun handleInlineTrainCommand(userMsg: String) {
        val content = userMsg.substring(6).trim()
        val parts = content.split("|")
        if (parts.size == 2 && parts[0].trim().isNotEmpty() && parts[1].trim().isNotEmpty()) {
            train(parts[0].trim(), parts[1].trim())
        } else {
            _toastEvents.tryEmit("Error in format. Use 'train: Q | A'")
        }
    }

    fun openTrainDialog() {
        _uiState.update { it.copy(showTrainDialog = true) }
    }

    fun dismissTrainDialog() {
        _uiState.update { it.copy(showTrainDialog = false) }
    }

    fun openParametersDialog() {
        viewModelScope.launch {
            val params = withContext(Dispatchers.IO) { localEngine.getParameters() }
            _uiState.update { it.copy(showParametersDialog = true, parameters = params) }
        }
    }

    fun dismissParametersDialog() {
        _uiState.update { it.copy(showParametersDialog = false) }
    }

    fun openAccountDialog() {
        _uiState.update { it.copy(showAccountDialog = true) }
    }

    fun dismissAccountDialog() {
        _uiState.update { it.copy(showAccountDialog = false) }
    }

    fun savePersonalApiKey(apiKey: String) {
        val cleaned = apiKey.trim()
        apiKeyStore.setPersonalApiKey(currentUserId, cleaned)
        _uiState.update {
            it.copy(
                hasPersonalApiKey = cleaned.isNotEmpty(),
                personalApiKey = cleaned,
                errorMessage = null
            )
        }
        if (cleaned.isNotEmpty()) {
            _toastEvents.tryEmit("Using your personal Gemini API key")
        }
    }

    fun clearPersonalApiKey() {
        apiKeyStore.clearPersonalApiKey(currentUserId)
        _uiState.update {
            it.copy(
                hasPersonalApiKey = false,
                personalApiKey = "",
                errorMessage = null
            )
        }
        _toastEvents.tryEmit("Switched back to shared app quota")
    }

    fun updateParameter(key: String, value: String) {
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) {
                localEngine.setParameter(key, value)
                localEngine.getParameters()
            }
            _uiState.update { it.copy(parameters = updated) }
        }
    }

    fun toggleTts() {
        _uiState.update { it.copy(isTtsEnabled = !it.isTtsEnabled) }
    }

    fun clearChat() {
        activeGenerationJob?.cancel()
        streamingBotMessage = null
        pendingUserMessage = null
        geminiService.clearHistory()
        onDeviceGemmaService.resetConversation()
        _uiState.update {
            it.copy(
                isProcessing = false,
                isUsingOllamaFallback = false,
                isUsingOnDeviceGemma = false,
                errorMessage = null,
                lastFailedPrompt = null
            )
        }
        viewModelScope.launch {
            userRepository.clearMessages(currentUserId).onFailure { err ->
                _uiState.update {
                    it.copy(errorMessage = err.localizedMessage ?: "Failed to clear cloud history.")
                }
            }
        }
    }

    fun prepareForSignOut() {
        activeGenerationJob?.cancel()
        streamingBotMessage = null
        pendingUserMessage = null
        onDeviceGemmaService.unload()
    }

    override fun onCleared() {
        onDeviceGemmaService.shutdown()
        super.onCleared()
    }

    companion object {
        private const val TAG = "ChatVM"
        private const val OLLAMA_FALLBACK_ROLE = "Ollama Fallback"
        private const val ON_DEVICE_GEMMA_ROLE = "On-Device Gemma"
    }
}
