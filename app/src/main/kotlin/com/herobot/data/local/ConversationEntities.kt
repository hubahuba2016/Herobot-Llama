package com.herobot.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.herobot.model.BotResponse
import com.herobot.model.ChatMessage

@Entity(
    tableName = "conversation_sessions",
    indices = [
        Index(value = ["userId"]),
        Index(value = ["updatedAtMillis"])
    ]
)
data class ConversationSessionEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val title: String,
    val lastModelId: String? = null,
    val lastSource: String? = null,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

@Entity(
    tableName = "conversation_messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["sessionId"]),
        Index(value = ["userId"]),
        Index(value = ["clientTimestampMillis"])
    ]
)
data class ConversationMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val userId: String,
    val text: String,
    val senderType: String,
    val source: String? = null,
    val modelId: String? = null,
    val roleTitle: String? = null,
    val clientTimestampMillis: Long
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

data class ConversationSessionSummary(
    @Embedded val session: ConversationSessionEntity,
    val messageCount: Int,
    val lastPreview: String?
)

data class CrossSessionMessageRow(
    val sessionId: String,
    val sessionTitle: String,
    val text: String,
    val senderType: String,
    val source: String?,
    val clientTimestampMillis: Long
)
