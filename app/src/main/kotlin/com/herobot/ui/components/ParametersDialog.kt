package com.herobot.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.herobot.ai.LocalAccelerator
import com.herobot.ai.LocalModelManager
import com.herobot.ai.LocalModelStatus
import com.herobot.ai.OnDeviceEngineState
import com.herobot.data.DBHelper
import com.herobot.engine.OllamaClient

@Composable
fun ParametersDialog(
    useGeminiReasoning: Boolean,
    parameters: Map<String, String>,
    localModelStatus: LocalModelStatus,
    onDeviceEngineState: OnDeviceEngineState,
    localAccelerator: LocalAccelerator,
    initialModelUrl: String,
    initialHfToken: String,
    onToggleGeminiReasoning: (Boolean) -> Unit,
    onUpdateParameter: (key: String, value: String) -> Unit,
    onDownloadLocalModel: (url: String, hfToken: String) -> Unit,
    onCancelDownload: () -> Unit,
    onImportLocalModel: () -> Unit,
    onLoadLocalModel: () -> Unit,
    onUnloadLocalModel: () -> Unit,
    onDeleteLocalModel: () -> Unit,
    onSelectAccelerator: (LocalAccelerator) -> Unit,
    onDismiss: () -> Unit
) {
    val uriHandler = LocalUriHandler.current

    var geminiEnabled by remember(useGeminiReasoning) {
        mutableStateOf(useGeminiReasoning)
    }
    var crossSessionMemoryEnabled by remember(parameters) {
        mutableStateOf(parameters[DBHelper.KEY_CROSS_SESSION_MEMORY]?.toBooleanStrictOrNull() ?: true)
    }
    var webSearchEnabled by remember(parameters) {
        mutableStateOf(parameters[DBHelper.KEY_WEB_SEARCH_ENABLED]?.toBooleanStrictOrNull() ?: true)
    }
    var model by remember(parameters) {
        mutableStateOf(parameters[DBHelper.KEY_MODEL] ?: OllamaClient.DEFAULT_MODEL)
    }
    var ollamaUrl by remember(parameters) {
        mutableStateOf(parameters[DBHelper.KEY_OLLAMA_URL] ?: OllamaClient.DEFAULT_ENDPOINT_URL)
    }
    var temperature by remember(parameters) {
        mutableStateOf(parameters[DBHelper.KEY_TEMPERATURE] ?: "0.7")
    }
    var downloadUrl by remember(initialModelUrl) {
        mutableStateOf(initialModelUrl)
    }
    var hfToken by remember(initialHfToken) {
        mutableStateOf(initialHfToken)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "HeroBot & Local Models",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Gemini Conversational Reasoning",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Falls back to On-Device Gemma 3 1B or Ollama if unavailable",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = geminiEnabled,
                        onCheckedChange = { geminiEnabled = it },
                        modifier = Modifier.testTag("gemini_reasoning_switch")
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Remember Across History",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Share context from other saved chats with AI models",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = crossSessionMemoryEnabled,
                        onCheckedChange = { crossSessionMemoryEnabled = it },
                        modifier = Modifier.testTag("cross_session_memory_switch")
                    )
                }

                HorizontalDivider()

                // On-Device Gemma 3 1B (LiteRT-LM) Manager Card
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Memory,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "On-Device Gemma 3 1B (LiteRT-LM)",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = formatLocalStatusText(localModelStatus, onDeviceEngineState),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (localModelStatus is LocalModelStatus.Downloading) {
                            val total = localModelStatus.totalBytes.coerceAtLeast(1L)
                            val progress = (localModelStatus.downloadedBytes.toFloat() / total.toFloat())
                                .coerceIn(0f, 1f)
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else if (localModelStatus is LocalModelStatus.Importing ||
                            onDeviceEngineState is OnDeviceEngineState.Loading
                        ) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Backend:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LocalAccelerator.entries.forEach { accel ->
                                FilterChip(
                                    selected = localAccelerator == accel,
                                    onClick = { onSelectAccelerator(accel) },
                                    label = { Text(accel.label) },
                                    modifier = Modifier.testTag("accel_chip_${accel.name.lowercase()}")
                                )
                            }
                        }

                        if (localModelStatus !is LocalModelStatus.Ready) {
                            OutlinedTextField(
                                value = hfToken,
                                onValueChange = { hfToken = it },
                                label = { Text("Hugging Face Token (optional)") },
                                placeholder = { Text("hf_...") },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("hf_token_input")
                            )

                            OutlinedTextField(
                                value = downloadUrl,
                                onValueChange = { downloadUrl = it },
                                label = { Text("Model URL (.litertlm)") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("local_model_url_input")
                            )

                            TextButton(
                                onClick = { uriHandler.openUri(LocalModelManager.LICENSE_URL) },
                                modifier = Modifier.testTag("gemma_license_link")
                            ) {
                                Text("Accept Gemma 3 1B License on Hugging Face")
                            }
                        }

                        when (localModelStatus) {
                            is LocalModelStatus.Downloading -> {
                                OutlinedButton(
                                    onClick = onCancelDownload,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("cancel_local_download_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Stop,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Cancel Download")
                                }
                            }

                            is LocalModelStatus.Importing -> {
                                Text(
                                    text = "Importing model file into app storage…",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            is LocalModelStatus.Ready -> {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    val isLoaded = onDeviceEngineState is OnDeviceEngineState.Loaded
                                    val isLoading = onDeviceEngineState is OnDeviceEngineState.Loading
                                    Button(
                                        onClick = {
                                            if (isLoaded) onUnloadLocalModel() else onLoadLocalModel()
                                        },
                                        enabled = !isLoading,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("load_local_model_button")
                                    ) {
                                        Icon(
                                            imageVector = if (isLoaded) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            when {
                                                isLoading -> "Loading…"
                                                isLoaded -> "Unload"
                                                else -> "Load"
                                            }
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = onDeleteLocalModel,
                                        modifier = Modifier.testTag("delete_local_model_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = "Delete local model",
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Delete")
                                    }
                                }
                            }

                            is LocalModelStatus.NotDownloaded, is LocalModelStatus.Error -> {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Button(
                                        onClick = { onDownloadLocalModel(downloadUrl, hfToken) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("download_local_model_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Download,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Download")
                                    }

                                    OutlinedButton(
                                        onClick = onImportLocalModel,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("import_local_model_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.FolderOpen,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Import File")
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Local Web Search Fallback",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Used when Gemini mode is off",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = webSearchEnabled,
                        onCheckedChange = { webSearchEnabled = it },
                        modifier = Modifier.testTag("web_search_switch")
                    )
                }

                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("External Ollama Model") },
                    supportingText = { Text("Default: gemma3:1b") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ollama_model_input")
                )

                OutlinedTextField(
                    value = ollamaUrl,
                    onValueChange = { ollamaUrl = it },
                    label = { Text("External Ollama Server URL") },
                    supportingText = { Text("e.g., http://localhost:11434") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ollama_url_input")
                )

                OutlinedTextField(
                    value = temperature,
                    onValueChange = { temperature = it },
                    label = { Text("Local Temperature (0.0 – 2.0)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ollama_temperature_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onToggleGeminiReasoning(geminiEnabled)
                    onUpdateParameter(DBHelper.KEY_CROSS_SESSION_MEMORY, crossSessionMemoryEnabled.toString())
                    onUpdateParameter(DBHelper.KEY_WEB_SEARCH_ENABLED, webSearchEnabled.toString())
                    if (model.isNotBlank()) {
                        onUpdateParameter(DBHelper.KEY_MODEL, model.trim())
                    }
                    if (ollamaUrl.isNotBlank()) {
                        onUpdateParameter(DBHelper.KEY_OLLAMA_URL, ollamaUrl.trim())
                    }
                    temperature.trim().toDoubleOrNull()?.let { temp ->
                        onUpdateParameter(DBHelper.KEY_TEMPERATURE, temp.coerceIn(0.0, 2.0).toString())
                    }
                    onDismiss()
                },
                modifier = Modifier.testTag("parameters_save_button")
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("parameters_cancel_button")
            ) {
                Text("Cancel")
            }
        }
    )
}

private fun formatLocalStatusText(
    modelStatus: LocalModelStatus,
    engineState: OnDeviceEngineState
): String {
    return when (modelStatus) {
        is LocalModelStatus.NotDownloaded -> "Not downloaded (~557 MB)"
        is LocalModelStatus.Downloading -> {
            val downloadedMb = modelStatus.downloadedBytes / (1024 * 1024)
            val totalMb = (modelStatus.totalBytes / (1024 * 1024)).coerceAtLeast(1L)
            val pct = ((modelStatus.downloadedBytes * 100) / modelStatus.totalBytes.coerceAtLeast(1L)).toInt()
            "Downloading: $pct% ($downloadedMb / $totalMb MB)"
        }
        is LocalModelStatus.Importing -> "Importing .litertlm file…"
        is LocalModelStatus.Error -> "Error: ${modelStatus.message}"
        is LocalModelStatus.Ready -> {
            val sizeMb = modelStatus.fileSizeBytes / (1024 * 1024)
            when (engineState) {
                is OnDeviceEngineState.Loaded -> "Loaded (${engineState.accelerator.label}) • $sizeMb MB"
                is OnDeviceEngineState.Loading -> "Loading on ${engineState.accelerator.label}… • $sizeMb MB"
                is OnDeviceEngineState.Error -> "Downloaded ($sizeMb MB) • Load failed: ${engineState.message}"
                is OnDeviceEngineState.Unloaded -> "Downloaded & ready ($sizeMb MB)"
            }
        }
    }
}
