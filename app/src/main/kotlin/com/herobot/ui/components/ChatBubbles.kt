package com.herobot.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.herobot.R
import com.herobot.model.BotResponse
import com.herobot.model.ChatMessage

private val URL_REGEX = Regex("https?://\\S+")

@Composable
fun ChatMessageRow(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val isUser = message.type == ChatMessage.Type.USER

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (!isUser) {
            Image(
                painter = painterResource(id = R.drawable.img_herobot_avatar),
                contentDescription = "HeroBot avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 310.dp)
        ) {
            val bubbleShape = if (isUser) {
                RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
            } else {
                RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
            }

            Surface(
                shape = bubbleShape,
                color = if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                tonalElevation = if (isUser) 0.dp else 1.dp,
                shadowElevation = if (isUser) 0.dp else 1.dp,
                modifier = Modifier
                    .then(
                        if (!isUser) {
                            Modifier.border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                                shape = bubbleShape
                            )
                        } else {
                            Modifier
                        }
                    )
                    .testTag(if (isUser) "user_message_bubble" else "bot_message_bubble")
            ) {
                if (isUser) {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                } else {
                    val linkColor = MaterialTheme.colorScheme.primary
                    val annotatedText = remember(message.text, linkColor) {
                        buildAnnotatedString {
                            append(message.text)
                            URL_REGEX.findAll(message.text).forEach { match ->
                                addLink(
                                    url = LinkAnnotation.Url(
                                        url = match.value,
                                        styles = TextLinkStyles(
                                            style = SpanStyle(
                                                color = linkColor,
                                                textDecoration = TextDecoration.Underline
                                            )
                                        )
                                    ),
                                    start = match.range.first,
                                    end = match.range.last + 1
                                )
                            }
                        }
                    }

                    Text(
                        text = annotatedText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }

            if (!isUser && message.source != null) {
                SourceBadge(
                    source = message.source,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun SourceBadge(
    source: BotResponse.Source,
    modifier: Modifier = Modifier
) {
    val (bgColor, textColor) = when (source) {
        BotResponse.Source.DATABASE -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f) to
            MaterialTheme.colorScheme.onPrimaryContainer
        BotResponse.Source.WEB -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f) to
            MaterialTheme.colorScheme.onSecondaryContainer
        BotResponse.Source.LLM -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f) to
            MaterialTheme.colorScheme.onTertiaryContainer
        BotResponse.Source.SYSTEM, BotResponse.Source.NONE ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .padding(horizontal = 7.dp, vertical = 2.dp)
            .testTag("message_source_badge")
    ) {
        Text(
            text = "Source: ${source.label}",
            style = MaterialTheme.typography.labelSmall,
            color = textColor
        )
    }
}
