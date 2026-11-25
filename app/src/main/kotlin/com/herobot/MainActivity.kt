package com.herobot

import android.app.Activity
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.herobot.ai.configureAppCheck
import com.herobot.ui.ChatScreen
import com.herobot.ui.ChatViewModel
import com.herobot.ui.theme.HeroBotTheme
import com.herobot.voice.VoiceAssistant

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()
    private lateinit var voiceAssistant: VoiceAssistant

    private val speechRecognizerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull()?.trim()
            if (!spoken.isNullOrEmpty()) {
                viewModel.submitInput(spoken)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure App Check with launch intent before any UI, ViewModels, or Firebase calls
        configureAppCheck(this, intent)

        enableEdgeToEdge()

        voiceAssistant = VoiceAssistant(this)

        setContent {
            HeroBotTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                LaunchedEffect(Unit) {
                    viewModel.ttsEvents.collect { text ->
                        voiceAssistant.speak(text)
                    }
                }

                LaunchedEffect(uiState.isTtsEnabled) {
                    if (!uiState.isTtsEnabled) {
                        voiceAssistant.stop()
                    }
                }

                LaunchedEffect(Unit) {
                    viewModel.toastEvents.collect { message ->
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }

                ChatScreen(
                    uiState = uiState,
                    onSendInput = viewModel::submitInput,
                    onVoiceInputClick = {
                        try {
                            speechRecognizerLauncher.launch(VoiceAssistant.createSpeechInputIntent())
                        } catch (_: Exception) {
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.voice_unavailable),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onSelectReasoningMode = viewModel::selectReasoningMode,
                    onToggleGeminiReasoning = viewModel::setUseGeminiReasoning,
                    onRetryError = viewModel::retryLastFailedPrompt,
                    onDismissError = viewModel::dismissError,
                    onToggleTts = viewModel::toggleTts,
                    onOpenTrainDialog = viewModel::openTrainDialog,
                    onDismissTrainDialog = viewModel::dismissTrainDialog,
                    onSaveTraining = viewModel::train,
                    onOpenParametersDialog = viewModel::openParametersDialog,
                    onDismissParametersDialog = viewModel::dismissParametersDialog,
                    onUpdateParameter = viewModel::updateParameter,
                    onClearChat = viewModel::clearChat
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::voiceAssistant.isInitialized) {
            voiceAssistant.shutdown()
        }
    }
}
