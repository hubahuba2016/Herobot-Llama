package com.herobot.ai

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.herobot.engine.MathEngine
import com.herobot.engine.NLPHelper
import com.herobot.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

enum class AssistantRole(
    val shortLabel: String,
    val title: String,
    val modelName: String,
    val description: String,
    val systemInstruction: String
) {
    GENERAL(
        shortLabel = "General",
        title = "General Reasoner",
        modelName = "gemini-4-hydrogen",
        description = "Balanced multi-turn reasoning and everyday Q&A",
        systemInstruction = """
            You are HeroBot, a thoughtful conversational Android assistant.
            Use the full conversation history to track intent, pronoun references, follow-up questions, and tone.
            Reason through each request internally before answering, then provide a clear, accurate, well-structured response.
            Ask one focused clarifying question when a request is genuinely ambiguous.
            Support both English and Indonesian naturally whenever the user writes in either language.
            Do not reveal raw hidden chain-of-thought; share concise reasoning steps or checks when they help the user.
        """.trimIndent()
    ),
    DEEP_REASONING(
        shortLabel = "Deep Reason",
        title = "Deep Reasoner",
        modelName = "gemini-4-carbon",
        description = "Multi-step analytical reasoning, math proofs, and logic",
        systemInstruction = """
            You are HeroBot's Deep Reasoner.
            Tackle every problem systematically: break down assumptions, verify edge cases, show clear logical or mathematical steps, and double-check conclusions against prior turns in the conversation.
            For math, science, or logic follow-ups, explain *why* each step holds rather than only giving the final answer.
            Keep explanations rigorous yet easy to read on a mobile screen.
        """.trimIndent()
    ),
    INTERACTIVE(
        shortLabel = "Interactive",
        title = "Interactive Strategist",
        modelName = "gemini-4-neon",
        description = "Complex interactive tasks, planning, coding, and synthesis",
        systemInstruction = """
            You are HeroBot's Interactive Strategist for complex multi-turn tasks, software architecture, planning, and collaborative problem-solving.
            Maintain continuity across every turn: remember constraints the user added earlier, surface key trade-offs concisely, propose concrete next options, and adapt dynamically to user feedback.
        """.trimIndent()
    ),
    FAST(
        shortLabel = "Fast",
        title = "Quick Responder",
        modelName = "gemini-3.1-flash-lite",
        description = "Low-latency replies for quick questions and follow-ups",
        systemInstruction = """
            You are HeroBot's Quick Responder.
            Deliver crisp, accurate, low-latency answers that still respect the conversation history and follow-up context.
            Keep responses brief unless the user asks for more detail.
        """.trimIndent()
    )
}

sealed interface ReasoningSelection {
    data object Auto : ReasoningSelection
    data class Fixed(val role: AssistantRole) : ReasoningSelection
}

sealed interface GeminiStreamEvent {
    data class Started(val role: AssistantRole, val modelName: String) : GeminiStreamEvent
    data class Chunk(
        val accumulatedText: String,
        val role: AssistantRole,
        val modelName: String
    ) : GeminiStreamEvent

    data class Completed(
        val fullText: String,
        val role: AssistantRole,
        val modelName: String
    ) : GeminiStreamEvent

    data class Error(val message: String) : GeminiStreamEvent
}

object GeminiModelRouter {

    private val AUTO_FALLBACK_CHAIN = listOf(
        "gemini-4-hydrogen",
        "gemini-3.8-flash",
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite"
    )

    private val DEEP_REASONING_KEYWORDS = listOf(
        "why", "how does", "how do", "prove", "proof", "derive", "analyze",
        "analysis", "compare", "contrast", "reason", "logic", "paradox",
        "step by step", "verify", "explain why", "calculate", "solve",
        "equation", "integral", "derivative", "mengapa", "kenapa", "jelaskan"
    )

    private val INTERACTIVE_COMPLEX_KEYWORDS = listOf(
        "plan", "design", "architect", "architecture", "build", "create",
        "debug", "refactor", "brainstorm", "strategy", "roadmap", "workflow",
        "interactive", "walk me through", "help me", "code", "implement",
        "kotlin", "compose", "android", "review"
    )

    private val FAST_GREETINGS = setOf(
        "hi", "hello", "hey", "halo", "hai", "thanks", "thank you",
        "ok", "okay", "yes", "no", "bye", "good morning", "good night"
    )

    fun resolveRole(input: String, selection: ReasoningSelection): AssistantRole {
        if (selection is ReasoningSelection.Fixed) {
            return selection.role
        }

        val trimmed = input.trim()
        val normalized = NLPHelper.normalize(trimmed)
        val wordCount = normalized.split(Regex("\\s+")).count { it.isNotEmpty() }

        if (normalized in FAST_GREETINGS || (wordCount <= 4 && !MathEngine.isMathematicsRequest(trimmed) &&
                DEEP_REASONING_KEYWORDS.none { normalized.contains(it) } &&
                INTERACTIVE_COMPLEX_KEYWORDS.none { normalized.contains(it) })
        ) {
            return AssistantRole.FAST
        }

        if (MathEngine.isMathematicsRequest(trimmed) ||
            DEEP_REASONING_KEYWORDS.any { normalized.contains(it) }
        ) {
            return AssistantRole.DEEP_REASONING
        }

        if (INTERACTIVE_COMPLEX_KEYWORDS.any { normalized.contains(it) } || wordCount >= 18) {
            return AssistantRole.INTERACTIVE
        }

        return AssistantRole.GENERAL
    }

    fun resolveCandidateModels(
        role: AssistantRole,
        selection: ReasoningSelection,
        exhaustedModels: Set<String> = emptySet()
    ): List<String> {
        if (selection is ReasoningSelection.Fixed) {
            return listOf(role.modelName)
        }

        val ordered = LinkedHashSet<String>().apply {
            add(role.modelName)
            addAll(AUTO_FALLBACK_CHAIN)
        }.toList()

        val available = ordered.filter { it !in exhaustedModels }
        return available.ifEmpty { ordered }
    }
}

class GeminiChatService {

    private data class ChatTurn(val role: String, val text: String)

    private val modelCache = ConcurrentHashMap<Pair<AssistantRole, String>, GenerativeModel>()
    private val quotaExhaustedSharedModels = ConcurrentHashMap.newKeySet<String>()
    private val quotaExhaustedPersonalModels = ConcurrentHashMap.newKeySet<String>()

    private val historyTurns = mutableListOf<ChatTurn>()
    private val learnedMemories = mutableListOf<Pair<String, String>>()

    private fun getOrCreateModel(role: AssistantRole, modelName: String): GenerativeModel {
        return modelCache.getOrPut(role to modelName) {
            Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                modelName = modelName,
                systemInstruction = content(role = "system") {
                    text(role.systemInstruction)
                }
            )
        }
    }

    fun recordLearnedFact(question: String, answer: String) {
        val pair = question.trim() to answer.trim()
        if (pair.first.isNotEmpty() && pair.second.isNotEmpty() && pair !in learnedMemories) {
            learnedMemories.add(pair)
        }
    }

    fun restoreLearnedFacts(pairs: List<Pair<String, String>>) {
        learnedMemories.clear()
        pairs.takeLast(20).forEach { (q, a) ->
            val trimmedQ = q.trim()
            val trimmedA = a.trim()
            if (trimmedQ.isNotEmpty() && trimmedA.isNotEmpty()) {
                learnedMemories.add(trimmedQ to trimmedA)
            }
        }
    }

    fun restoreHistory(messages: List<ChatMessage>) {
        historyTurns.clear()
        messages.filter { !it.isStreaming && it.text.isNotBlank() }
            .takeLast(MAX_HISTORY_CONTENTS)
            .forEach { message ->
                val turnRole = if (message.type == ChatMessage.Type.USER) "user" else "model"
                historyTurns.add(ChatTurn(turnRole, message.text.trim()))
            }
    }

    fun clearHistory() {
        historyTurns.clear()
    }

    fun sendMessageStream(
        userPrompt: String,
        selection: ReasoningSelection,
        localHint: String? = null,
        personalApiKey: String? = null
    ): Flow<GeminiStreamEvent> = flow {
        val trimmedPersonalKey = personalApiKey?.trim()?.takeIf { it.isNotEmpty() }
        val usingPersonalKey = trimmedPersonalKey != null
        val exhaustedSet = if (usingPersonalKey) quotaExhaustedPersonalModels else quotaExhaustedSharedModels

        val role = GeminiModelRouter.resolveRole(userPrompt, selection)
        val candidates = GeminiModelRouter.resolveCandidateModels(
            role = role,
            selection = selection,
            exhaustedModels = exhaustedSet
        )
        val effectivePrompt = buildEffectivePrompt(userPrompt, localHint)
        val historySnapshot = historyTurns.toList()

        var lastError: String? = null

        for (candidateModel in candidates) {
            emit(GeminiStreamEvent.Started(role, candidateModel))
            val accumulatedText = StringBuilder()

            try {
                withTimeout(TIMEOUT_MILLIS) {
                    if (trimmedPersonalKey != null) {
                        streamWithPersonalApiKey(
                            apiKey = trimmedPersonalKey,
                            modelName = candidateModel,
                            systemInstruction = role.systemInstruction,
                            history = historySnapshot,
                            userPrompt = effectivePrompt
                        ) { chunkText ->
                            accumulatedText.append(chunkText)
                            emit(
                                GeminiStreamEvent.Chunk(
                                    accumulatedText = accumulatedText.toString(),
                                    role = role,
                                    modelName = candidateModel
                                )
                            )
                        }
                    } else {
                        val model = getOrCreateModel(role, candidateModel)
                        val firebaseHistory: List<Content> = historySnapshot.map { turn ->
                            content(role = turn.role) { text(turn.text) }
                        }
                        val chat = model.startChat(history = firebaseHistory)
                        chat.sendMessageStream(effectivePrompt).collect { chunk ->
                            val chunkText = chunk.text.orEmpty()
                            if (chunkText.isNotEmpty()) {
                                accumulatedText.append(chunkText)
                                emit(
                                    GeminiStreamEvent.Chunk(
                                        accumulatedText = accumulatedText.toString(),
                                        role = role,
                                        modelName = candidateModel
                                    )
                                )
                            }
                        }
                    }
                }

                val finalText = accumulatedText.toString().trim()
                if (finalText.isNotEmpty()) {
                    historyTurns.add(ChatTurn("user", userPrompt))
                    historyTurns.add(ChatTurn("model", finalText))
                    if (historyTurns.size > MAX_HISTORY_CONTENTS) {
                        repeat(historyTurns.size - MAX_HISTORY_CONTENTS) {
                            historyTurns.removeAt(0)
                        }
                    }
                    emit(GeminiStreamEvent.Completed(finalText, role, candidateModel))
                    return@flow
                } else {
                    lastError = "Gemini returned an empty response. Please try again."
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Gemini chat generation timed out on $candidateModel", e)
                lastError = "Response timed out after 60 seconds. Try switching to Fast mode or retrying."
                if (selection is ReasoningSelection.Fixed || accumulatedText.isNotEmpty()) {
                    emit(GeminiStreamEvent.Error(lastError))
                    return@flow
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PersonalApiKeyAuthException) {
                Log.e(TAG, "Personal Gemini API key rejected on $candidateModel", e)
                emit(GeminiStreamEvent.Error(e.message ?: "Your personal Gemini API key was rejected."))
                return@flow
            } catch (e: Exception) {
                Log.e(TAG, "Gemini chat generation failed on $candidateModel", e)
                val errorText = buildErrorChainText(e)
                val isAvailabilityOrQuota = isQuotaOrModelAvailabilityError(errorText)
                val canFallback = selection is ReasoningSelection.Auto &&
                    accumulatedText.isEmpty() &&
                    isAvailabilityOrQuota

                if (isAvailabilityOrQuota) {
                    exhaustedSet.add(candidateModel)
                }

                if (canFallback) {
                    Log.w(TAG, "Model $candidateModel unavailable/quota-exceeded; trying next available model.")
                    lastError = e.localizedMessage ?: "Quota exceeded on $candidateModel."
                    continue
                }

                val displayMessage = when {
                    selection is ReasoningSelection.Fixed && isAvailabilityOrQuota -> {
                        "${e.localizedMessage ?: "Quota exceeded for $candidateModel."} Switch to Auto Route to use a model with available quota."
                    }
                    !usingPersonalKey && isAvailabilityOrQuota -> {
                        "Shared app quota reached. You can add your own Gemini API key in Account settings to use your personal quota."
                    }
                    else -> e.localizedMessage ?: "Failed to generate response from Gemini."
                }
                emit(GeminiStreamEvent.Error(displayMessage))
                return@flow
            }
        }

        val fallbackExhaustedMsg = if (usingPersonalKey) {
            lastError ?: "All available Gemini models reached quota limits on your API key."
        } else {
            lastError ?: "Shared Gemini quota is exhausted. Add your own Gemini API key in Account settings to continue with your personal quota."
        }
        emit(GeminiStreamEvent.Error(fallbackExhaustedMsg))
    }.flowOn(Dispatchers.IO)

    private suspend fun streamWithPersonalApiKey(
        apiKey: String,
        modelName: String,
        systemInstruction: String,
        history: List<ChatTurn>,
        userPrompt: String,
        onChunk: suspend (String) -> Unit
    ) {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$modelName:streamGenerateContent?alt=sse")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 55_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("x-goog-api-key", apiKey)
        }

        try {
            val contentsArray = JSONArray()
            for (turn in history) {
                contentsArray.put(
                    JSONObject().apply {
                        put("role", turn.role)
                        put("parts", JSONArray().put(JSONObject().put("text", turn.text)))
                    }
                )
            }
            contentsArray.put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
                }
            )

            val requestBody = JSONObject().apply {
                put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                )
                put("contents", contentsArray)
            }.toString()

            connection.outputStream.use { out ->
                out.write(requestBody.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                val errBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val parsedMessage = parseApiErrorMessage(errBody)
                if (code == 401 || code == 403 || parsedMessage.contains("api key", ignoreCase = true)) {
                    throw PersonalApiKeyAuthException(
                        parsedMessage.ifBlank { "Invalid or unauthorized personal Gemini API key (HTTP $code)." }
                    )
                }
                throw IllegalStateException(
                    parsedMessage.ifBlank { "Gemini API error ($code)" }
                )
            }

            BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val jsonPayload = line.removePrefix("data:").trim()
                    if (jsonPayload.isEmpty() || jsonPayload == "[DONE]") continue

                    val root = JSONObject(jsonPayload)
                    val candidates = root.optJSONArray("candidates") ?: continue
                    val firstCandidate = candidates.optJSONObject(0) ?: continue
                    val contentObj = firstCandidate.optJSONObject("content") ?: continue
                    val parts = contentObj.optJSONArray("parts") ?: continue
                    for (i in 0 until parts.length()) {
                        val partText = parts.optJSONObject(i)?.optString("text").orEmpty()
                        if (partText.isNotEmpty()) {
                            onChunk(partText)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseApiErrorMessage(rawBody: String): String {
        if (rawBody.isBlank()) return ""
        return try {
            val json = JSONObject(rawBody)
            json.optJSONObject("error")?.optString("message").orEmpty()
        } catch (_: Exception) {
            rawBody.take(240)
        }
    }

    private fun isQuotaOrModelAvailabilityError(errorText: String): Boolean {
        val lower = errorText.lowercase()
        return lower.contains("quota") ||
            lower.contains("resource_exhausted") ||
            lower.contains("resource exhausted") ||
            lower.contains("429") ||
            lower.contains("rate limit") ||
            lower.contains("billing") ||
            lower.contains("not found") ||
            lower.contains("404") ||
            lower.contains("not supported")
    }

    private fun buildErrorChainText(throwable: Throwable): String {
        val sb = StringBuilder()
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < 6) {
            current.message?.let { sb.append(it).append(' ') }
            current = current.cause
            depth++
        }
        return sb.toString()
    }

    private fun buildEffectivePrompt(userPrompt: String, localHint: String?): String {
        val relevantLearned = learnedMemories.takeLast(5)
        if (relevantLearned.isEmpty() && localHint.isNullOrBlank()) {
            return userPrompt
        }

        return buildString {
            if (relevantLearned.isNotEmpty()) {
                append("User-taught knowledge from this session:\n")
                for ((q, a) in relevantLearned) {
                    append("- Q: ").append(q).append(" -> A: ").append(a).append('\n')
                }
                append('\n')
            }
            if (!localHint.isNullOrBlank()) {
                append("Local verification context: ").append(localHint).append("\n\n")
            }
            append(userPrompt)
        }
    }

    private class PersonalApiKeyAuthException(message: String) : Exception(message)

    companion object {
        private const val TAG = "GeminiChatService"
        private const val TIMEOUT_MILLIS = 60_000L
        private const val MAX_HISTORY_CONTENTS = 24
    }
}
