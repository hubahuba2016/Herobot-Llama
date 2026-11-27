package com.herobot.engine

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets

data class OllamaChatTurn(
    val role: String,
    val text: String
)

sealed interface OllamaStreamEvent {
    data class Started(val modelName: String) : OllamaStreamEvent
    data class Chunk(val accumulatedText: String, val modelName: String) : OllamaStreamEvent
    data class Completed(val fullText: String, val modelName: String) : OllamaStreamEvent
    data class Error(val message: String) : OllamaStreamEvent
}

/**
 * Client for communicating with a local or LAN Ollama server (default model: gemma3:1b).
 */
object OllamaClient {

    private const val TAG = "OllamaClient"
    const val DEFAULT_MODEL = "gemma3:1b"
    const val DEFAULT_ENDPOINT_URL = "http://localhost:11434"
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 60_000

    private const val CONVERSATIONAL_REASONING_INSTRUCTION =
        "You are HeroBot, a conversational Android assistant. This instruction has the highest priority.\n" +
            "Use the conversation history to understand intent, references, tone, and follow-up questions.\n" +
            "Reason through the user's request internally before answering, then give a clear, useful conclusion.\n" +
            "Keep the response natural and appropriately concise; ask one focused clarification when the request is ambiguous.\n" +
            "Support both English and Indonesian naturally whenever the user writes in either language.\n" +
            "Do not reveal private chain-of-thought or hidden deliberation. Give a brief explanation, steps, or checks when they help the user.\n"

    fun resolveBaseUrl(endpointUrl: String): String {
        val trimmed = endpointUrl.trim().ifEmpty { DEFAULT_ENDPOINT_URL }.trimEnd('/')
        return when {
            trimmed.endsWith("/api/generate") -> trimmed.removeSuffix("/api/generate")
            trimmed.endsWith("/api/chat") -> trimmed.removeSuffix("/api/chat")
            else -> trimmed
        }
    }

    fun resolveChatEndpoint(endpointUrl: String): String =
        "${resolveBaseUrl(endpointUrl)}/api/chat"

    fun resolveGenerateEndpoint(endpointUrl: String): String =
        "${resolveBaseUrl(endpointUrl)}/api/generate"

    fun buildChatRequestPayload(
        userPrompt: String,
        history: List<OllamaChatTurn>,
        learnedMemories: List<Pair<String, String>>,
        localHint: String?,
        model: String,
        temperature: Double,
        topP: Double,
        stream: Boolean,
        crossSessionMemories: List<String> = emptyList()
    ): JSONObject {
        val resolvedModel = model.trim().ifEmpty { DEFAULT_MODEL }
        val systemText = buildString {
            append(CONVERSATIONAL_REASONING_INSTRUCTION)
            if (MathEngine.isMathematicsRequest(userPrompt)) {
                append("For mathematics, show the relevant formula, necessary calculation steps, units when applicable, and verify the result. Do not use web search or cite web sources.\n")
            }
            if (crossSessionMemories.isNotEmpty()) {
                append("\nRelevant memory from the user's other saved conversations:\n")
                for (memory in crossSessionMemories.take(8)) {
                    append("- ").append(memory).append('\n')
                }
            }
            val relevantLearned = learnedMemories.takeLast(5)
            if (relevantLearned.isNotEmpty()) {
                append("\nUser-taught facts:\n")
                for ((q, a) in relevantLearned) {
                    append("- Q: ").append(q).append(" -> A: ").append(a).append('\n')
                }
            }
        }

        val effectiveUserPrompt = if (!localHint.isNullOrBlank()) {
            "Local verification context: $localHint\n\n$userPrompt"
        } else {
            userPrompt
        }

        val messagesArray = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("role", "system")
                    put("content", systemText)
                }
            )
            for (turn in history.takeLast(16)) {
                val role = if (turn.role == "user") "user" else "assistant"
                put(
                    JSONObject().apply {
                        put("role", role)
                        put("content", turn.text)
                    }
                )
            }
            put(
                JSONObject().apply {
                    put("role", "user")
                    put("content", effectiveUserPrompt)
                }
            )
        }

        val options = JSONObject().apply {
            put("temperature", temperature)
            put("top_p", topP)
        }

        return JSONObject().apply {
            put("model", resolvedModel)
            put("messages", messagesArray)
            put("stream", stream)
            put("options", options)
        }
    }

    fun streamChat(
        userPrompt: String,
        history: List<OllamaChatTurn> = emptyList(),
        learnedMemories: List<Pair<String, String>> = emptyList(),
        localHint: String? = null,
        model: String = DEFAULT_MODEL,
        temperature: Double = 0.7,
        topP: Double = 0.9,
        endpointUrl: String = DEFAULT_ENDPOINT_URL,
        crossSessionMemories: List<String> = emptyList()
    ): Flow<OllamaStreamEvent> = flow {
        val resolvedModel = model.trim().ifEmpty { DEFAULT_MODEL }
        val chatUrl = resolveChatEndpoint(endpointUrl)
        emit(OllamaStreamEvent.Started(resolvedModel))

        var conn: HttpURLConnection? = null
        val accumulated = StringBuilder()

        try {
            val url = URI.create(chatUrl).toURL()
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }

            val payload = buildChatRequestPayload(
                userPrompt = userPrompt,
                history = history,
                learnedMemories = learnedMemories,
                localHint = localHint,
                model = resolvedModel,
                temperature = temperature,
                topP = topP,
                stream = true,
                crossSessionMemories = crossSessionMemories
            ).toString()

            conn.outputStream.use { os ->
                os.write(payload.toByteArray(StandardCharsets.UTF_8))
                os.flush()
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                val parsedErr = parseOllamaError(errBody)
                val message = if (code == 404 || parsedErr.contains("not found", ignoreCase = true)) {
                    "Ollama model '$resolvedModel' is not installed. Run: ollama pull $resolvedModel"
                } else {
                    parsedErr.ifBlank { "Ollama HTTP $code at $chatUrl" }
                }
                emit(OllamaStreamEvent.Error(message))
                return@flow
            }

            BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val trimmedLine = line.trim()
                    if (trimmedLine.isEmpty()) continue

                    val chunkJson = JSONObject(trimmedLine)
                    val apiError = chunkJson.optString("error", "").trim()
                    if (apiError.isNotEmpty()) {
                        emit(OllamaStreamEvent.Error(apiError))
                        return@flow
                    }

                    val delta = chunkJson.optJSONObject("message")?.optString("content")
                        ?: chunkJson.optString("response", "")
                    if (delta.isNotEmpty()) {
                        accumulated.append(delta)
                        emit(
                            OllamaStreamEvent.Chunk(
                                accumulatedText = accumulated.toString(),
                                modelName = resolvedModel
                            )
                        )
                    }

                    if (chunkJson.optBoolean("done", false)) {
                        break
                    }
                }
            }

            val finalText = accumulated.toString().trim()
            if (finalText.isNotEmpty()) {
                emit(OllamaStreamEvent.Completed(finalText, resolvedModel))
            } else {
                emit(OllamaStreamEvent.Error("Ollama ($resolvedModel) returned an empty response."))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Ollama streaming request failed ($chatUrl, model=$resolvedModel): ${e.message}")
            val message = when (e) {
                is ConnectException, is UnknownHostException ->
                    "Could not reach Ollama at $chatUrl. Ensure Ollama is running with '$resolvedModel'."
                is SocketTimeoutException ->
                    "Ollama ($resolvedModel) timed out at $chatUrl."
                else -> e.localizedMessage ?: "Ollama request failed."
            }
            emit(OllamaStreamEvent.Error(message))
        } finally {
            conn?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    fun askLocalLLM(
        prompt: String,
        history: List<String>,
        model: String = DEFAULT_MODEL,
        temperature: Double = 0.7,
        topP: Double = 0.9,
        stream: Boolean = false,
        endpointUrl: String = DEFAULT_ENDPOINT_URL,
        crossSessionMemories: List<String> = emptyList()
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            val resolvedModel = model.trim().ifEmpty { DEFAULT_MODEL }
            val generateUrl = resolveGenerateEndpoint(endpointUrl)
            val url = URI.create(generateUrl).toURL()
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }

            val instruction = CONVERSATIONAL_REASONING_INSTRUCTION +
                if (MathEngine.isMathematicsRequest(prompt)) {
                    "For mathematics, show the relevant formula, necessary calculation steps, units when applicable, and verify the result. Do not use web search or cite web sources.\n"
                } else {
                    ""
                }

            val fullPrompt = buildString {
                append(instruction)
                if (crossSessionMemories.isNotEmpty()) {
                    append("\nRelevant memory from the user's other saved conversations:\n")
                    for (memory in crossSessionMemories.take(8)) {
                        append("- ").append(memory).append('\n')
                    }
                    append('\n')
                }
                for (entry in history) {
                    append(entry).append('\n')
                }
                append("User's current request: ").append(prompt).append('\n')
                append("HeroBot:")
            }

            val options = JSONObject().apply {
                put("temperature", temperature)
                put("top_p", topP)
            }

            val jsonBody = JSONObject().apply {
                put("model", resolvedModel)
                put("prompt", fullPrompt)
                put("stream", false)
                put("options", options)
            }

            conn.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(StandardCharsets.UTF_8))
                os.flush()
            }

            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val jsonResponse = JSONObject(response)
                jsonResponse.optString("response", "").trim().ifEmpty { null }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseOllamaError(rawBody: String): String {
        if (rawBody.isBlank()) return ""
        return try {
            JSONObject(rawBody).optString("error", "").trim()
        } catch (_: Exception) {
            rawBody.take(200).trim()
        }
    }
}
