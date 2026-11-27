package com.herobot.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query(
        """
        SELECT s.*,
               (SELECT COUNT(*) FROM conversation_messages m WHERE m.sessionId = s.id) AS messageCount,
               (SELECT text FROM conversation_messages m WHERE m.sessionId = s.id ORDER BY m.clientTimestampMillis DESC LIMIT 1) AS lastPreview
        FROM conversation_sessions s
        WHERE s.userId = :userId
        ORDER BY s.updatedAtMillis DESC
        """
    )
    fun observeSessions(userId: String): Flow<List<ConversationSessionSummary>>

    @Query(
        """
        SELECT * FROM conversation_messages
        WHERE sessionId = :sessionId AND userId = :userId
        ORDER BY clientTimestampMillis ASC, id ASC
        """
    )
    fun observeMessagesForSession(
        userId: String,
        sessionId: String
    ): Flow<List<ConversationMessageEntity>>

    @Query(
        """
        SELECT * FROM conversation_sessions
        WHERE id = :sessionId AND userId = :userId
        LIMIT 1
        """
    )
    suspend fun getSessionById(userId: String, sessionId: String): ConversationSessionEntity?

    @Query(
        """
        SELECT * FROM conversation_sessions
        WHERE userId = :userId
        ORDER BY updatedAtMillis DESC
        LIMIT 1
        """
    )
    suspend fun getLatestSession(userId: String): ConversationSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: ConversationSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessage(message: ConversationMessageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessagesIgnoreDuplicates(messages: List<ConversationMessageEntity>)

    @Query("DELETE FROM conversation_messages WHERE sessionId = :sessionId AND userId = :userId")
    suspend fun clearMessagesInSession(userId: String, sessionId: String)

    @Query("DELETE FROM conversation_sessions WHERE id = :sessionId AND userId = :userId")
    suspend fun deleteSession(userId: String, sessionId: String)

    @Query(
        """
        SELECT m.sessionId AS sessionId,
               s.title AS sessionTitle,
               m.text AS text,
               m.senderType AS senderType,
               m.source AS source,
               m.clientTimestampMillis AS clientTimestampMillis
        FROM conversation_messages m
        INNER JOIN conversation_sessions s ON s.id = m.sessionId
        WHERE m.userId = :userId
          AND m.sessionId != :excludeSessionId
          AND (m.source IS NULL OR m.source != 'SYSTEM')
        ORDER BY m.clientTimestampMillis DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentCrossSessionMessages(
        userId: String,
        excludeSessionId: String,
        limit: Int = 160
    ): List<CrossSessionMessageRow>
}
