package com.herobot.data.local

import com.herobot.data.remote.ChatMessageRecord
import com.herobot.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ConversationHistoryRepository(
    private val dao: ConversationDao
) {

    fun observeSessions(userId: String): Flow<List<ConversationSessionSummary>> =
        dao.observeSessions(userId)

    fun observeSessionMessages(userId: String, sessionId: String): Flow<List<ConversationMessageEntity>> =
        dao.observeMessagesForSession(userId, sessionId)

    suspend fun getOrCreateDefaultSession(userId: String): String {
        val latest = dao.getLatestSession(userId)
        if (latest != null) return latest.id

        val primaryId = primarySessionId(userId)
        val now = System.currentTimeMillis()
        dao.upsertSession(
            ConversationSessionEntity(
                id = primaryId,
                userId = userId,
                title = DEFAULT_SESSION_TITLE,
                createdAtMillis = now,
                updatedAtMillis = now
            )
        )
        return primaryId
    }

    suspend fun createNewSession(
        userId: String,
        title: String = DEFAULT_SESSION_TITLE
    ): String {
        val sessionId = "session_${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        dao.upsertSession(
            ConversationSessionEntity(
                id = sessionId,
                userId = userId,
                title = title.trim().ifEmpty { DEFAULT_SESSION_TITLE }.take(60),
                createdAtMillis = now,
                updatedAtMillis = now
            )
        )
        return sessionId
    }

    suspend fun saveMessage(
        userId: String,
        sessionId: String,
        messageId: String,
        message: ChatMessage
    ) {
        val existingSession = dao.getSessionById(userId, sessionId)
        val now = System.currentTimeMillis()
        val msgTimestamp = message.timestampMillis.coerceAtLeast(now)
        val derivedTitle = deriveTitleFromMessage(message)

        val updatedSession = if (existingSession == null) {
            ConversationSessionEntity(
                id = sessionId,
                userId = userId,
                title = derivedTitle ?: DEFAULT_SESSION_TITLE,
                lastModelId = message.modelId?.take(80),
                lastSource = message.source?.name,
                createdAtMillis = msgTimestamp,
                updatedAtMillis = msgTimestamp
            )
        } else {
            val nextTitle = if (existingSession.title == DEFAULT_SESSION_TITLE && derivedTitle != null) {
                derivedTitle
            } else {
                existingSession.title
            }
            existingSession.copy(
                title = nextTitle,
                lastModelId = message.modelId?.take(80) ?: existingSession.lastModelId,
                lastSource = message.source?.name ?: existingSession.lastSource,
                updatedAtMillis = maxOf(existingSession.updatedAtMillis, msgTimestamp)
            )
        }

        dao.upsertSession(updatedSession)
        dao.upsertMessage(
            ConversationMessageEntity(
                id = messageId,
                sessionId = sessionId,
                userId = userId,
                text = message.text.trim(),
                senderType = message.type.name,
                source = message.source?.name,
                modelId = message.modelId?.take(80),
                roleTitle = message.roleTitle?.take(80),
                clientTimestampMillis = message.timestampMillis
            )
        )
    }

    suspend fun syncCloudRecordsIntoPrimarySession(
        userId: String,
        records: List<ChatMessageRecord>
    ) {
        if (records.isEmpty()) return
        val sessionId = primarySessionId(userId)
        val existing = dao.getSessionById(userId, sessionId)
        val firstUserText = records.firstOrNull { it.senderType == ChatMessage.Type.USER.name }?.text
        val derivedTitle = firstUserText?.let { formatTitle(it) } ?: DEFAULT_SESSION_TITLE
        val lastBot = records.lastOrNull { it.senderType == ChatMessage.Type.BOT.name }
        val minTs = records.minOfOrNull { it.clientTimestampMillis } ?: System.currentTimeMillis()
        val maxTs = records.maxOfOrNull { it.clientTimestampMillis } ?: minTs

        val session = if (existing == null) {
            ConversationSessionEntity(
                id = sessionId,
                userId = userId,
                title = derivedTitle,
                lastModelId = lastBot?.modelId?.take(80),
                lastSource = lastBot?.source,
                createdAtMillis = minTs,
                updatedAtMillis = maxTs
            )
        } else {
            val nextTitle = if (existing.title == DEFAULT_SESSION_TITLE && derivedTitle != DEFAULT_SESSION_TITLE) {
                derivedTitle
            } else {
                existing.title
            }
            existing.copy(
                title = nextTitle,
                lastModelId = existing.lastModelId ?: lastBot?.modelId?.take(80),
                lastSource = existing.lastSource ?: lastBot?.source,
                updatedAtMillis = maxOf(existing.updatedAtMillis, maxTs)
            )
        }

        dao.upsertSession(session)
        val entities = records.mapNotNull { record ->
            val trimmed = record.text.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            ConversationMessageEntity(
                id = record.id.ifBlank { "cloud_${record.clientTimestampMillis}_${trimmed.hashCode()}" },
                sessionId = sessionId,
                userId = userId,
                text = trimmed,
                senderType = record.senderType,
                source = record.source,
                modelId = record.modelId?.take(80),
                roleTitle = record.roleTitle?.take(80),
                clientTimestampMillis = record.clientTimestampMillis
            )
        }
        if (entities.isNotEmpty()) {
            dao.insertMessagesIgnoreDuplicates(entities)
        }
    }

    suspend fun clearSessionMessages(userId: String, sessionId: String) {
        dao.clearMessagesInSession(userId, sessionId)
        val existing = dao.getSessionById(userId, sessionId) ?: return
        dao.upsertSession(
            existing.copy(
                title = DEFAULT_SESSION_TITLE,
                lastModelId = null,
                lastSource = null,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteSession(userId: String, sessionId: String) {
        dao.deleteSession(userId, sessionId)
    }

    private fun deriveTitleFromMessage(message: ChatMessage): String? {
        if (message.type != ChatMessage.Type.USER) return null
        return formatTitle(message.text)
    }

    private fun formatTitle(rawText: String): String {
        val singleLine = rawText.trim().replace(Regex("\\s+"), " ")
        if (singleLine.isEmpty()) return DEFAULT_SESSION_TITLE
        return if (singleLine.length <= 48) singleLine else "${singleLine.take(45)}…"
    }

    companion object {
        const val DEFAULT_SESSION_TITLE = "New Conversation"
        fun primarySessionId(userId: String): String = "primary_$userId"
    }
}
