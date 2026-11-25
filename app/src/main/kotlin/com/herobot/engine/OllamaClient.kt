package com.herobot.engine

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * Client for communicating with a local Ollama server (default http://localhost:11434/api/generate).
 */
object OllamaClient {

    private const val CONVERSATIONAL_REASONING_INSTRUCTION =
        "You are HeroBot, a conversational Android assistant. This instruction has the highest priority.\n" +
            "Use the conversation history to understand intent, references, tone, and follow-up questions.\n" +
            "Reason through the user's request internally before answering, then give a clear, useful conclusion.\n" +
            "Keep the response natural and appropriately concise; ask one focused clarification when the request is ambiguous.\n" +
            "Do not reveal private chain-of-thought or hidden deliberation. Give a brief explanation, steps, or checks when they help the user.\n"

    fun askLocalLLM(
        prompt: String,
        history: List<String>,
        model: String,
        temperature: Double,
        topP: Double,
        stream: Boolean,
        endpointUrl: String = "http://localhost:11434/api/generate"
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URI.create(endpointUrl).toURL()
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 5000
                readTimeout = 10000
            }

            val instruction = CONVERSATIONAL_REASONING_INSTRUCTION +
                if (MathEngine.isMathematicsRequest(prompt)) {
                    "For mathematics, show the relevant formula, necessary calculation steps, units when applicable, and verify the result. Do not use web search or cite web sources.\n"
                } else {
                    ""
                }

            val fullPrompt = buildString {
                append(instruction)
                for (entry in history) {
                    append(entry).append('\n')
                }
                append("User's current request: ").append(prompt).append('\n')
                append("HeroBot:")
            }

            val jsonBody = JSONObject().apply {
                put("model", model)
                put("prompt", fullPrompt)
                put("stream", stream)
                put("temperature", temperature)
                put("top_p", topP)
            }

            conn.outputStream.use { os ->
                os.write(jsonBody.toString().toByteArray(StandardCharsets.UTF_8))
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
}
