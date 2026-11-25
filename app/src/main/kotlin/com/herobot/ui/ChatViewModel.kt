package com.herobot.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.herobot.R
import com.herobot.engine.ChatbotEngine
import com.herobot.model.BotResponse
import com.herobot.model.ChatMessage
import kotlinx.coroutines.Dispatchers
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
    val showTrainDialog: Boolean = false,
    val showParametersDialog: Boolean = false,
    val parameters: Map<String, String> = emptyMap()
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = ChatbotEngine(
        application,
        R.raw.chitchat,
        R.raw.chatbot_training_data
    )

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _ttsEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val ttsEvents: SharedFlow<String> = _ttsEvents.asSharedFlow()

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toastEvents: SharedFlow<String> = _toastEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            val (count, params) = withContext(Dispatchers.IO) {
                val seededCount = engine.ensureSeeded()
                val loadedParams = engine.getParameters()
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

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _uiState.value.isProcessing) return

        val userMessage = ChatMessage(
            text = trimmed,
            type = ChatMessage.Type.USER
        )

        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                isProcessing = true
            )
        }

        viewModelScope.launch {
            val response = withContext(Dispatchers.IO) {
                engine.reply(trimmed)
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

    fun train(question: String, answer: String) {
        val q = question.trim()
        val a = answer.trim()
        if (q.isEmpty() || a.isEmpty()) {
            _toastEvents.tryEmit("Both question and answer are required")
            return
        }

        viewModelScope.launch {
            val updatedCount = withContext(Dispatchers.IO) {
                engine.train(q, a)
            }
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
            val params = withContext(Dispatchers.IO) { engine.getParameters() }
            _uiState.update { it.copy(showParametersDialog = true, parameters = params) }
        }
    }

    fun dismissParametersDialog() {
        _uiState.update { it.copy(showParametersDialog = false) }
    }

    fun updateParameter(key: String, value: String) {
        viewModelScope.launch {
            val updated = withContext(Dispatchers.IO) {
                engine.setParameter(key, value)
                engine.getParameters()
            }
            _uiState.update { it.copy(parameters = updated) }
        }
    }

    fun toggleTts() {
        _uiState.update { it.copy(isTtsEnabled = !it.isTtsEnabled) }
    }

    fun clearChat() {
        _uiState.update { it.copy(messages = emptyList()) }
    }
}
