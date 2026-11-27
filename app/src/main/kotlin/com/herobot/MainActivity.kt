package com.herobot

import android.app.Activity
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.herobot.ai.LocalModelManager
import com.herobot.ai.OnDeviceGemmaService
import com.herobot.ai.configureAppCheck
import com.herobot.auth.attemptAutoSignIn
import com.herobot.auth.authStateFlow
import com.herobot.auth.signOut
import com.herobot.data.UserApiKeyStore
import com.herobot.data.remote.UserChatRepository
import com.herobot.engine.ChatbotEngine
import com.herobot.ui.AuthScreen
import com.herobot.ui.ChatScreen
import com.herobot.ui.ChatViewModel
import com.herobot.ui.theme.HeroBotTheme
import com.herobot.voice.VoiceAssistant

class MainActivity : ComponentActivity() {

    private lateinit var voiceAssistant: VoiceAssistant

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure App Check with launch intent before any UI, ViewModels, or Firebase calls
        configureAppCheck(this, intent)

        enableEdgeToEdge()

        voiceAssistant = VoiceAssistant(this)

        setContent {
            HeroBotTheme {
                HeroBotApp(voiceAssistant = voiceAssistant)
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

@Composable
private fun HeroBotApp(voiceAssistant: VoiceAssistant) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val auth = remember { FirebaseAuth.getInstance() }
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)

    LaunchedEffect(Unit) {
        if (auth.currentUser == null) {
            attemptAutoSignIn(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = {},
                onUnauthenticated = {},
                scope = scope
            )
        }
    }

    val user = currentUser
    if (user == null) {
        AuthScreen(
            onAuthSuccess = {}
        )
    } else {
        key(user.uid) {
            AuthenticatedChatHost(
                user = user,
                credentialManager = credentialManager,
                voiceAssistant = voiceAssistant
            )
        }
    }
}

@Composable
private fun AuthenticatedChatHost(
    user: FirebaseUser,
    credentialManager: CredentialManager,
    voiceAssistant: VoiceAssistant
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val viewModel: ChatViewModel = viewModel(
        key = user.uid,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                val localEngine = ChatbotEngine(
                    app,
                    R.raw.chitchat,
                    R.raw.chatbot_training_data
                )
                val repository = UserChatRepository(db)
                val apiKeyStore = UserApiKeyStore(app)
                val localModelManager = LocalModelManager(app)
                val onDeviceGemmaService = OnDeviceGemmaService(app)

                ChatViewModel(
                    localEngine = localEngine,
                    userRepository = repository,
                    apiKeyStore = apiKeyStore,
                    localModelManager = localModelManager,
                    onDeviceGemmaService = onDeviceGemmaService,
                    currentUserId = user.uid,
                    userDisplayName = user.displayName,
                    userEmail = user.email,
                    userPhotoUrl = user.photoUrl?.toString()
                )
            }
        }
    )

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val openModelFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importOnDeviceGemma(uri)
        }
    }

    val speechRecognizerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull()?.trim()
            if (!spoken.isNullOrEmpty()) {
                viewModel.submitInput(spoken)
            }
        }
    }

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
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
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
                    context,
                    context.getString(R.string.voice_unavailable),
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
        onDownloadLocalModel = viewModel::downloadOnDeviceGemma,
        onCancelLocalDownload = viewModel::cancelOnDeviceDownload,
        onImportLocalModel = { openModelFileLauncher.launch(arrayOf("*/*")) },
        onLoadLocalModel = viewModel::loadOnDeviceGemma,
        onUnloadLocalModel = viewModel::unloadOnDeviceGemma,
        onDeleteLocalModel = viewModel::deleteOnDeviceGemma,
        onSelectLocalAccelerator = viewModel::setLocalAccelerator,
        onOpenAccountDialog = viewModel::openAccountDialog,
        onDismissAccountDialog = viewModel::dismissAccountDialog,
        onSavePersonalApiKey = viewModel::savePersonalApiKey,
        onClearPersonalApiKey = viewModel::clearPersonalApiKey,
        onSignOutClick = {
            voiceAssistant.stop()
            viewModel.prepareForSignOut()
            signOut(credentialManager = credentialManager, scope = scope)
        },
        onClearChat = viewModel::clearChat
    )
}
