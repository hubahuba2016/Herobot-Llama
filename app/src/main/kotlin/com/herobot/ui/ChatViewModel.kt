package com.herobot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.herobot.R
import com.herobot.ai.AssistantRole
import com.herobot.ai.GeminiChatService
import com.herobot.ai.GeminiModelRouter
import com.herobot.ai.GeminiStreamEvent
import com.herobot.ai.ReasoningSelection
import com.herobot.engine.ChatbotEngine
import com.herobot.engine.MathEngine
import com.herobot.model.BotResponse
import com.herobot.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val reasoningSelection: ReasoningSelection = ReasoningSelection.Auto,
    val activeRole: AssistantRole = AssistantRole.GENERAL,
    val errorMessage: String? = null,
    val lastFailedPrompt: String? = null,
    val showTrainDialog: Boolean = false,
    val showParametersDialog: Boolean = false,
    val parameters: Map<String, String> = emptyMap()
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val localEngine = ChatbotEngine(
        application,
        R.raw.chitchat,
        R.raw.chatbot_training_data
    )

    private val geminiService = GeminiChatService()

    private var activeGenerationJob: Job? = null

    private val _uiState = MutableStateFlow(ChatUiState())
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

        if (stateSnapshot.useGeminiReasoning) {
            sendGeminiMessage(trimmed, userMessage, stateSnapshot.reasoningSelection)
        } else {
            sendLocalEngineMessage(trimmed, userMessage)
        }
    }

    private fun sendGeminiMessage(
        prompt: String,
        userMessage: ChatMessage?,
        selection: ReasoningSelection
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

        _uiState.update { state ->
            state.copy(
                messages = buildList {
                    addAll(state.messages)
                    if (userMessage != null) add(userMessage)
                    add(placeholderBotMessage)
                },
                isProcessing = true,
                activeRole = resolvedRole,
                errorMessage = null,
                lastFailedPrompt = null
            )
        }

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
                localHint = localHint
            ).collect { event ->
                when (event) {
                    is GeminiStreamEvent.Started -> {
                        _uiState.update { it.copy(activeRole = event.role) }
                    }

                    is GeminiStreamEvent.Chunk -> {
                        _uiState.update { state ->
                            val updated = state.messages.map { msg ->
                                if (msg.id == placeholderBotMessage.id) {
                                    msg.copy(
                                        text = event.accumulatedText,
                                        modelId = event.role.modelName,
                                        roleTitle = event.role.title,
                                        isStreaming = true
                                    )
                                } else {
                                    msg
                                }
                            }
                            state.copy(messages = updated)
                        }
                    }

                    is GeminiStreamEvent.Completed -> {
                        _uiState.update { state ->
                            val updated = state.messages.map { msg ->
                                if (msg.id == placeholderBotMessage.id) {
                                    msg.copy(
                                        text = event.fullText,
                                        modelId = event.role.modelName,
                                        roleTitle = event.role.title,
                                        isStreaming = false
                                    )
                                } else {
                                    msg
                                }
                            }
                            state.copy(
                                messages = updated,
                                isProcessing = false,
                                errorMessage = null,
                                lastFailedPrompt = null
                            )
                        }
                        if (_uiState.value.isTtsEnabled) {
                            _ttsEvents.tryEmit(event.fullText)
                        }
                    }

                    is GeminiStreamEvent.Error -> {
                        _uiState.update { state ->
                            val cleaned = state.messages.filterNot {
                                it.id == placeholderBotMessage.id && it.text.isBlank()
                            }.map { msg ->
                                if (msg.id == placeholderBotMessage.id) {
                                    msg.copy(isStreaming = false)
                                } else {
                                    msg
                                }
                            }
                            state.copy(
                                messages = cleaned,
                                isProcessing = false,
                                errorMessage = event.message,
                                lastFailedPrompt = prompt
                            )
                        }
                    }
                }
            }
        }
    }

    private fun sendLocalEngineMessage(prompt: String, userMessage: ChatMessage?) {
        _uiState.update { state ->
            state.copy(
                messages = if (userMessage != null) state.messages + userMessage else state.messages,
                isProcessing = true,
                errorMessage = null,
                lastFailedPrompt = null
            )
        }

        viewModelScope.launch {
            val response = withContext(Dispatchers.IO) {
                localEngine.reply(prompt)
            }
            val botMessage = ChatMessage(
                text = response.text,
                type = ChatMessage.Type.BOT,
                source = response.source
            )
            _uiState.update {
                it.copy(
                    messages = it.messages + botMessage,
                    isProcessing = false
                )
            }
            if (_uiState.value.isTtsEnabled) {
                _ttsEvents.tryEmit(response.text)
            }
        }
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
                useGeminiReasoning = true
            )
        }
    }

    fun setUseGeminiReasoning(enabled: Boolean) {
        _uiState.update { it.copy(useGeminiReasoning = enabled, errorMessage = null) }
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
            val systemText = "System: HeroBot has learned a new response."
            val systemMsg = ChatMessage(
                text = systemText,
                type = ChatMessage.Type.BOT,
                source = BotResponse.Source.SYSTEM
            )
            _uiState.update {
                it.copy(
                    messages = it.messages + systemMsg,
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
        geminiService.clearHistory()
        _uiState.update {
            it.copy(
                messages = emptyList(),
                isProcessing = false,
                errorMessage = null,
                lastFailedPrompt = null
            )
        }
    }
}
