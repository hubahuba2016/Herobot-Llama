package com.herobot.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.herobot.R

@Composable
fun TrainBotDialog(
    onDismiss: () -> Unit,
    onSave: (question: String, answer: String) -> Unit
) {
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.train_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Teach HeroBot a new question and answer pair saved to its local database.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = question,
                    onValueChange = {
                        question = it
                        showError = false
                    },
                    label = { Text(stringResource(R.string.train_question_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("train_question_input")
                )
                OutlinedTextField(
                    value = answer,
                    onValueChange = {
                        answer = it
                        showError = false
                    },
                    label = { Text(stringResource(R.string.train_answer_label)) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("train_answer_input")
                )
                if (showError) {
                    Text(
                        text = stringResource(R.string.train_fields_required),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (question.isBlank() || answer.isBlank()) {
                        showError = true
                    } else {
                        onSave(question, answer)
                    }
                },
                modifier = Modifier.testTag("train_save_button")
            ) {
                Text(stringResource(R.string.train_save))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("train_cancel_button")
            ) {
                Text(stringResource(R.string.train_cancel))
            }
        }
    )
}
