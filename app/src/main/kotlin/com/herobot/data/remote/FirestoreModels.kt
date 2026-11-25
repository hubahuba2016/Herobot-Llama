package com.herobot.data.remote

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import com.herobot.model.BotResponse
import com.herobot.model.ChatMessage

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String? = null,
    val photoUrl: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ChatMessageRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val text: String = "",
    val senderType: String = "BOT",
    val source: String? = null,
    val modelId: String? = null,
    val roleTitle: String? = null,
    val clientTimestampMillis: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toChatMessage(): ChatMessage {
        val resolvedType = enumValues<ChatMessage.Type>()
            .firstOrNull { it.name == senderType }
            ?: ChatMessage.Type.BOT
        val resolvedSource = source?.let { raw ->
            enumValues<BotResponse.Source>().firstOrNull { it.name == raw }
        }
        val stableId = id.hashCode().toLong().let { hash ->
            if (hash == 0L) clientTimestampMillis else hash
        }
        return ChatMessage(
            id = stableId,
            text = text,
            type = resolvedType,
            source = resolvedSource,
            modelId = modelId,
            roleTitle = roleTitle,
            isStreaming = false,
            timestampMillis = clientTimestampMillis
        )
    }
}

@Keep
@IgnoreExtraProperties
data class LearnedQaRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val question: String = "",
    val answer: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
