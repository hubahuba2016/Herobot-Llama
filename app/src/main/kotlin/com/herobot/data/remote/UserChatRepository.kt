package com.herobot.data.remote

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import com.herobot.R
import com.herobot.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class UserChatRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(expectedUid: String): String {
        val uid = auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
        check(uid == expectedUid) { "Signed-in user changed; refusing cross-user Firestore access." }
        return uid
    }

    suspend fun ensureUserProfile(
        userId: String,
        displayName: String?,
        email: String?,
        photoUrl: String?
    ): Result<Unit> {
        val docPath = "users/$userId"
        return try {
            val uid = requireUserId(userId)
            val safeName = displayName?.trim()?.takeIf { it.isNotEmpty() }?.take(80)
                ?: email?.substringBefore('@')?.take(80)?.ifEmpty { "HeroBot User" }
                ?: "HeroBot User"
            val safeEmail = email?.trim()?.take(160)?.takeIf { it.isNotEmpty() }
            val safePhoto = photoUrl?.trim()?.take(500)?.takeIf { it.isNotEmpty() }

            val userRef = db.collection("users").document(uid)
            val existing = userRef.get().await()
            if (!existing.exists()) {
                val payload = mapOf(
                    "userId" to uid,
                    "displayName" to safeName,
                    "email" to safeEmail,
                    "photoUrl" to safePhoto,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null }
                userRef.set(payload).await()
            } else {
                val updatePayload = mapOf(
                    "displayName" to safeName,
                    "email" to safeEmail,
                    "photoUrl" to safePhoto,
                    "updatedAt" to FieldValue.serverTimestamp()
                ).filterValues { it != null }
                userRef.update(updatePayload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    fun observeMessages(userId: String): Flow<List<ChatMessageRecord>> = flow {
        val uid = requireUserId(userId)
        val path = "users/$uid/messages"
        emitAll(
            db.collection("users")
                .document(uid)
                .collection("messages")
                .orderBy("clientTimestampMillis", Query.Direction.ASCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        ChatMessageRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun saveMessage(
        userId: String,
        messageDocId: String,
        message: ChatMessage
    ): Result<Unit> {
        val safeDocId = sanitizeDocId(messageDocId)
        val docPath = "users/$userId/messages/$safeDocId"
        return try {
            val uid = requireUserId(userId)
            val trimmedText = message.text.trim().take(32000)
            require(trimmedText.isNotEmpty()) { "Message text cannot be empty." }

            val docRef = db.collection("users")
                .document(uid)
                .collection("messages")
                .document(safeDocId)

            val payload = mapOf(
                "userId" to uid,
                "text" to trimmedText,
                "senderType" to message.type.name,
                "source" to message.source?.name,
                "modelId" to message.modelId?.take(80),
                "roleTitle" to message.roleTitle?.take(80),
                "clientTimestampMillis" to message.timestampMillis.coerceAtLeast(0L),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null }

            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun clearMessages(userId: String): Result<Unit> {
        val colPath = "users/$userId/messages"
        return try {
            val uid = requireUserId(userId)
            val snapshot = db.collection("users")
                .document(uid)
                .collection("messages")
                .get()
                .await()

            val docs = snapshot.documents
            for (chunk in docs.chunked(400)) {
                val batch = db.batch()
                for (doc in chunk) {
                    batch.delete(doc.reference)
                }
                batch.commit().await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, colPath)
            Result.failure(e)
        }
    }

    fun observeLearnedQa(userId: String): Flow<List<LearnedQaRecord>> = flow {
        val uid = requireUserId(userId)
        val path = "users/$uid/learned_qa"
        emitAll(
            db.collection("users")
                .document(uid)
                .collection("learned_qa")
                .orderBy("createdAt", Query.Direction.ASCENDING)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        LearnedQaRecord::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun saveLearnedQa(
        userId: String,
        qaDocId: String,
        question: String,
        answer: String
    ): Result<Unit> {
        val safeDocId = sanitizeDocId(qaDocId)
        val docPath = "users/$userId/learned_qa/$safeDocId"
        return try {
            val uid = requireUserId(userId)
            val q = question.trim().take(500)
            val a = answer.trim().take(2000)
            require(q.isNotEmpty() && a.isNotEmpty()) { "Question and answer must not be blank." }

            val docRef = db.collection("users")
                .document(uid)
                .collection("learned_qa")
                .document(safeDocId)

            val payload = mapOf(
                "userId" to uid,
                "question" to q,
                "answer" to a,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    companion object {
        fun sanitizeDocId(rawId: String): String {
            val sanitized = rawId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                .take(128)
            return sanitized.ifEmpty { "doc_${System.currentTimeMillis()}" }
        }
    }
}
