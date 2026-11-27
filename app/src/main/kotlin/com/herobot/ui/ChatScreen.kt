package com.herobot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.herobot.R
import com.herobot.ai.AssistantRole
import com.herobot.ai.LocalAccelerator
import com.herobot.ai.LocalModelStatus
import com.herobot.ai.ReasoningSelection
import com.herobot.ui.components.AccountDialog
import com.herobot.ui.components.ChatMessageRow
import com.herobot.ui.components.HistoryDialog
import com.herobot.ui.components.ParametersDialog
import com.herobot.ui.components.TrainBotDialog

private val STARTER_PROMPTS = listOf(
    "Why does the sky appear blue? Explain step by step",
    "Solve 2x + 5 = 15 and explain each step",
    "Help me design an offline-first Android app",
    "Compare Kotlin Coroutines vs Flows"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onSendInput: (String) -> Unit,
    onVoiceInputClick: () -> Unit,
    onSelectReasoningMode: (ReasoningSelection) -> Unit,
    onToggleGeminiReasoning: (Boolean) -> Unit,
    onRetryError: () -> Unit,
    onDismissError: () -> Unit,
    onToggleTts: () -> Unit,
    onOpenTrainDialog: () -> Unit,
    onDismissTrainDialog: () -> Unit,
    onSaveTraining: (String, String) -> Unit,
    onOpenParametersDialog: () -> Unit,
    onDismissParametersDialog: () -> Unit,
    onUpdateParameter: (String, String) -> Unit,
    onDownloadLocalModel: (String, String) -> Unit,
    onCancelLocalDownload: () -> Unit,
    onImportLocalModel: () -> Unit,
    onLoadLocalModel: () -> Unit,
    onUnloadLocalModel: () -> Unit,
    onDeleteLocalModel: () -> Unit,
    onSelectLocalAccelerator: (LocalAccelerator) -> Unit,
    onOpenAccountDialog: () -> Unit,
    onDismissAccountDialog: () -> Unit,
    onSavePersonalApiKey: (String) -> Unit,
    onClearPersonalApiKey: () -> Unit,
    onOpenHistoryDialog: () -> Unit,
    onDismissHistoryDialog: () -> Unit,
    onSelectSession: (String) -> Unit,
    onNewChatSession: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onSignOutClick: () -> Unit,
    onClearChat: () -> Unit
) {
    var inputText by rememberSaveable { mutableStateOf("") }
    var showOverflowMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val lastMessageLength = uiState.messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(uiState.messages.size, lastMessageLength) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    if (uiState.showTrainDialog) {
        TrainBotDialog(
            onDismiss = onDismissTrainDialog,
            onSave = onSaveTraining
        )
    }

    if (uiState.showParametersDialog) {
        ParametersDialog(
            useGeminiReasoning = uiState.useGeminiReasoning,
            parameters = uiState.parameters,
            localModelStatus = uiState.localModelStatus,
            onDeviceEngineState = uiState.onDeviceEngineState,
            localAccelerator = uiState.localAccelerator,
            initialModelUrl = uiState.localModelUrl,
            initialHfToken = uiState.huggingFaceToken,
            onToggleGeminiReasoning = onToggleGeminiReasoning,
            onUpdateParameter = onUpdateParameter,
            onDownloadLocalModel = onDownloadLocalModel,
            onCancelDownload = onCancelLocalDownload,
            onImportLocalModel = onImportLocalModel,
            onLoadLocalModel = onLoadLocalModel,
            onUnloadLocalModel = onUnloadLocalModel,
            onDeleteLocalModel = onDeleteLocalModel,
            onSelectAccelerator = onSelectLocalAccelerator,
            onDismiss = onDismissParametersDialog
        )
    }

    if (uiState.showAccountDialog) {
        AccountDialog(
            displayName = uiState.userDisplayName,
            email = uiState.userEmail,
            hasPersonalApiKey = uiState.hasPersonalApiKey,
            initialApiKey = uiState.personalApiKey,
            onSaveApiKey = onSavePersonalApiKey,
            onClearApiKey = onClearPersonalApiKey,
            onSignOut = {
                onDismissAccountDialog()
                onSignOutClick()
            },
            onDismiss = onDismissAccountDialog
        )
    }

    if (uiState.showHistoryDialog) {
        HistoryDialog(
            sessions = uiState.savedSessions,
            activeSessionId = uiState.activeSessionId,
            onSelectSession = onSelectSession,
            onNewChat = onNewChatSession,
            onDeleteSession = onDeleteSession,
            onDismiss = onDismissHistoryDialog
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal
        ),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(id = R.drawable.img_herobot_avatar),
                                contentDescription = "HeroBot logo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                                        CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val quotaLabel = if (uiState.hasPersonalApiKey) "Personal Key" else "Shared Quota"
                                Text(
                                    text = when {
                                        uiState.isSeeding -> "Loading knowledge base…"
                                        uiState.isUsingOnDeviceGemma ->
                                            "On-Device • Gemma 3 1B (${uiState.localAccelerator.label})"
                                        uiState.isUsingOllamaFallback ->
                                            "Ollama Fallback • ${uiState.activeModelName}"
                                        uiState.useGeminiReasoning ->
                                            "${uiState.activeRole.title} • ${uiState.activeModelName} • $quotaLabel"
                                        uiState.localModelStatus is LocalModelStatus.Ready ->
                                            "Local • Gemma 3 1B Ready • ${uiState.knowledgeCount} Q&A"
                                        else -> "Local Engine • ${uiState.knowledgeCount} Q&A pairs"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onOpenHistoryDialog,
                            modifier = Modifier.testTag("history_action_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.History,
                                contentDescription = stringResource(R.string.history_action_cd)
                            )
                        }
                        IconButton(
                            onClick = onOpenParametersDialog,
                            modifier = Modifier.testTag("settings_action_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Tune,
                                contentDescription = stringResource(R.string.settings_action_cd)
                            )
                        }
                        Box {
                            IconButton(
                                onClick = { showOverflowMenu = true },
                                modifier = Modifier.testTag("more_options_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MoreVert,
                                    contentDescription = stringResource(R.string.more_options_cd)
                                )
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_account)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.AccountCircle,
                                            contentDescription = null,
                                            tint = if (uiState.hasPersonalApiKey) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenAccountDialog()
                                    },
                                    modifier = Modifier.testTag("account_action_button")
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (uiState.isTtsEnabled) {
                                                    R.string.menu_voice_on
                                                } else {
                                                    R.string.menu_voice_off
                                                }
                                            )
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (uiState.isTtsEnabled) {
                                                Icons.AutoMirrored.Rounded.VolumeUp
                                            } else {
                                                Icons.AutoMirrored.Rounded.VolumeOff
                                            },
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onToggleTts()
                                    },
                                    modifier = Modifier.testTag("tts_toggle_button")
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_train)) },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Rounded.Psychology,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenTrainDialog()
                                    },
                                    modifier = Modifier.testTag("train_action_button")
                                )
                                if (uiState.messages.isNotEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.menu_clear_chat)) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Rounded.DeleteOutline,
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            showOverflowMenu = false
                                            onClearChat()
                                        },
                                        modifier = Modifier.testTag("clear_chat_button")
                                    )
                                }
                            }
                        }
                    }
                )

                if (uiState.useGeminiReasoning) {
                    RoleSelectorBar(
                        selection = uiState.reasoningSelection,
                        onSelect = onSelectReasoningMode
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 760.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    if (uiState.messages.isEmpty()) {
                        EmptyChatWelcome(
                            userDisplayName = uiState.userDisplayName,
                            hasPersonalApiKey = uiState.hasPersonalApiKey,
                            isLocalGemmaReady = uiState.localModelStatus is LocalModelStatus.Ready,
                            activeRole = uiState.activeRole,
                            isAuto = uiState.reasoningSelection is ReasoningSelection.Auto,
                            onPromptClick = onSendInput,
                            onOpenAccountDialog = onOpenAccountDialog,
                            onOpenParametersDialog = onOpenParametersDialog,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(vertical = 12.dp),
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("chat_message_list")
                        ) {
                            items(
                                items = uiState.messages,
                                key = { it.id }
                            ) { message ->
                                ChatMessageRow(message = message)
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = uiState.errorMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    uiState.errorMessage?.let { errorMsg ->
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag("error_banner")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = "Error",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = errorMsg,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                if (!uiState.hasPersonalApiKey &&
                                    errorMsg.contains("quota", ignoreCase = true)
                                ) {
                                    TextButton(
                                        onClick = onOpenAccountDialog,
                                        modifier = Modifier.testTag("error_add_key_button")
                                    ) {
                                        Text("Add Key")
                                    }
                                }
                                if (errorMsg.contains("Ollama", ignoreCase = true) ||
                                    errorMsg.contains("Gemma", ignoreCase = true)
                                ) {
                                    TextButton(
                                        onClick = onOpenParametersDialog,
                                        modifier = Modifier.testTag("error_ollama_settings_button")
                                    ) {
                                        Text("Local AI")
                                    }
                                }
                                if (uiState.lastFailedPrompt != null) {
                                    TextButton(
                                        onClick = onRetryError,
                                        modifier = Modifier.testTag("retry_error_button")
                                    ) {
                                        Text("Retry")
                                    }
                                }
                                IconButton(
                                    onClick = onDismissError,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = "Dismiss error",
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = uiState.isProcessing,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .testTag("thinking_indicator")
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                uiState.isUsingOnDeviceGemma ->
                                    "On-Device Gemma 3 1B (${uiState.localAccelerator.label}) is reasoning…"
                                uiState.isUsingOllamaFallback ->
                                    "Gemini unavailable • Ollama (${uiState.activeModelName}) is reasoning…"
                                uiState.useGeminiReasoning ->
                                    "${uiState.activeRole.title} (${uiState.activeModelName}) is reasoning…"
                                else ->
                                    "HeroBot is thinking…"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = {
                                Text(
                                    text = stringResource(R.string.input_placeholder),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            enabled = !uiState.isProcessing,
                            maxLines = 4,
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (inputText.isNotBlank() && !uiState.isProcessing) {
                                        onSendInput(inputText)
                                        inputText = ""
                                    }
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("chat_input_field")
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        IconButton(
                            onClick = onVoiceInputClick,
                            enabled = !uiState.isProcessing,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("voice_input_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Mic,
                                contentDescription = stringResource(R.string.voice_cd),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }

                        Spacer(modifier = Modifier.width(2.dp))

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(
                                    if (!uiState.isProcessing) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }
                                )
                                .combinedClickable(
                                    enabled = !uiState.isProcessing,
                                    onClick = {
                                        if (inputText.isNotBlank()) {
                                            onSendInput(inputText)
                                            inputText = ""
                                        }
                                    },
                                    onLongClick = onOpenTrainDialog
                                )
                                .testTag("send_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.Send,
                                contentDescription = stringResource(R.string.send_cd),
                                tint = if (!uiState.isProcessing) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleSelectorBar(
    selection: ReasoningSelection,
    onSelect: (ReasoningSelection) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            FilterChip(
                selected = selection is ReasoningSelection.Auto,
                onClick = { onSelect(ReasoningSelection.Auto) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                label = {
                    Text(
                        text = "Auto Route",
                        style = MaterialTheme.typography.labelMedium
                    )
                },
                modifier = Modifier.testTag("role_chip_auto")
            )

            AssistantRole.entries.forEach { role ->
                val isSelected = selection is ReasoningSelection.Fixed && selection.role == role
                FilterChip(
                    selected = isSelected,
                    onClick = { onSelect(ReasoningSelection.Fixed(role)) },
                    label = {
                        Text(
                            text = "${role.shortLabel} (${role.modelName.removePrefix("gemini-")})",
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    modifier = Modifier.testTag("role_chip_${role.name.lowercase()}")
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyChatWelcome(
    userDisplayName: String,
    hasPersonalApiKey: Boolean,
    isLocalGemmaReady: Boolean,
    activeRole: AssistantRole,
    isAuto: Boolean,
    onPromptClick: (String) -> Unit,
    onOpenAccountDialog: () -> Unit,
    onOpenParametersDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_herobot_avatar),
            contentDescription = "HeroBot welcome emblem",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), CircleShape)
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "Welcome, $userDisplayName",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (isAuto) {
                "Your chats & taught Q&A sync to your Google account. Auto Route balances speed and deep reasoning."
            } else {
                "${activeRole.title} (${activeRole.modelName}): ${activeRole.description}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (!hasPersonalApiKey) {
            TextButton(
                onClick = onOpenAccountDialog,
                modifier = Modifier.testTag("welcome_add_key_button")
            ) {
                Text("Optional: Add your own Gemini API key")
            }
        }
        if (!isLocalGemmaReady) {
            TextButton(
                onClick = onOpenParametersDialog,
                modifier = Modifier.testTag("welcome_download_gemma_button")
            ) {
                Text("Download On-Device Gemma 3 1B for offline fallback")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            STARTER_PROMPTS.forEach { prompt ->
                AssistChip(
                    onClick = { onPromptClick(prompt) },
                    label = {
                        Text(
                            text = prompt,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                )
            }
        }
    }
}
