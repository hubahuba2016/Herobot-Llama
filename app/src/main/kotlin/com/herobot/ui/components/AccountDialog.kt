package com.herobot.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.herobot.R

@Composable
fun AccountDialog(
    displayName: String,
    email: String?,
    hasPersonalApiKey: Boolean,
    initialApiKey: String,
    onSaveApiKey: (String) -> Unit,
    onClearApiKey: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit
) {
    var apiKeyInput by remember(initialApiKey) { mutableStateOf(initialApiKey) }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.AccountCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Account & Quota",
                    style = MaterialTheme.typography.titleLarge
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (!email.isNullOrBlank()) {
                            Text(
                                text = email,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = if (hasPersonalApiKey) {
                                "Active quota: Your personal Gemini API key"
                            } else {
                                "Active quota: Shared HeroBot app quota"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (hasPersonalApiKey) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondary
                            },
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

                HorizontalDivider()

                Text(
                    text = "Personal Gemini API Key (Optional)",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "Enter your own Google AI Studio API key to use your personal Gemini quota. Saved privately on this device only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("Gemini API Key") },
                    placeholder = { Text("AIza… (leave empty for shared quota)") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Key,
                            contentDescription = null
                        )
                    },
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                imageVector = if (showKey) {
                                    Icons.Rounded.VisibilityOff
                                } else {
                                    Icons.Rounded.Visibility
                                },
                                contentDescription = if (showKey) "Hide API key" else "Show API key"
                            )
                        }
                    },
                    visualTransformation = if (showKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("personal_api_key_input")
                )

                if (hasPersonalApiKey) {
                    TextButton(
                        onClick = {
                            apiKeyInput = ""
                            onClearApiKey()
                        },
                        modifier = Modifier.testTag("clear_personal_api_key_button")
                    ) {
                        Text("Remove personal key (use shared quota)")
                    }
                }

                HorizontalDivider()

                OutlinedButton(
                    onClick = onSignOut,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sign_out_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Logout,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.sign_out))
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = apiKeyInput.trim()
                    if (trimmed.isEmpty()) {
                        onClearApiKey()
                    } else {
                        onSaveApiKey(trimmed)
                    }
                    onDismiss()
                },
                modifier = Modifier.testTag("account_save_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("account_cancel_button")
            ) {
                Text("Close")
            }
        }
    )
}
