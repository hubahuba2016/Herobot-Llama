package com.herobot.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.herobot.data.DBHelper
import com.herobot.engine.OllamaClient

@Composable
fun ParametersDialog(
    useGeminiReasoning: Boolean,
    parameters: Map<String, String>,
    onToggleGeminiReasoning: (Boolean) -> Unit,
    onUpdateParameter: (key: String, value: String) -> Unit,
    onDismiss: () -> Unit
) {
    var geminiEnabled by remember(useGeminiReasoning) {
        mutableStateOf(useGeminiReasoning)
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "HeroBot Parameters",
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
                            text = "Falls back to Ollama ($model) if unavailable",
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
                    label = { Text("Ollama Fallback Model") },
                    supportingText = { Text("Default: gemma3:1b") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ollama_model_input")
                )

                OutlinedTextField(
                    value = ollamaUrl,
                    onValueChange = { ollamaUrl = it },
                    label = { Text("Ollama Server URL") },
                    supportingText = { Text("e.g., http://localhost:11434") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ollama_url_input")
                )

                OutlinedTextField(
                    value = temperature,
                    onValueChange = { temperature = it },
                    label = { Text("Ollama Temperature (0.0 – 1.0)") },
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
