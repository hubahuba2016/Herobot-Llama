package com.herobot.engine

import android.content.Context
import android.util.Log
import com.herobot.data.DBHelper
import com.herobot.model.BotResponse
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Core HeroBot conversational engine combining:
 * 1. Linear equations & math concept/arithmetic/trig evaluation
 * 2. Local SQLite exact match + TF-cosine NLP similarity search
 * 3. Bilingual English/Indonesian built-in personality
 * 4. Web search (Wikipedia, DuckDuckGo, SearxNG, Google fallback)
 * 5. Local Ollama LLM fallback
 */
class ChatbotEngine(
    context: Context,
    private vararg val rawResIds: Int
) {
    private val appContext = context.applicationContext
    private val db: DBHelper? = try {
        DBHelper(appContext)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize DBHelper", e)
        null
    }

    private val history = ConversationHistory(maxSize = 10)

    @Volatile
    private var isSeeded = false

    fun ensureSeeded(): Int {
        val database = db ?: return 0
        if (isSeeded) return database.getCount()
        synchronized(this) {
            if (isSeeded) return database.getCount()
            try {
                if (database.getCount() == 0 && rawResIds.isNotEmpty()) {
                    Log.d(TAG, "Database empty. Seeding from ${rawResIds.size} resource file(s)...")
                    for (resId in rawResIds) {
                        try {
                            appContext.resources.openRawResource(resId).use { stream ->
                                importTrainingData(stream)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to seed resource id: $resId", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking or seeding database", e)
            }
            isSeeded = true
            return database.getCount()
        }
    }

    fun reply(input: String): BotResponse {
        ensureSeeded()

        val normalizedInput = NLPHelper.normalize(input)
        history.add("User: $normalizedInput")

        // 0. Context-aware follow-ups
        if (normalizedInput == "why" || normalizedInput == "how" ||
            normalizedInput == "why?" || normalizedInput == "how?"
        ) {
            return BotResponse(
                "I base my answers on the knowledge and sources I have access to.",
                BotResponse.Source.SYSTEM
            )
        }

        // Local linear equation solver
        MathEngine.solveLinearEquation(input)?.let { equationAnswer ->
            return BotResponse(equationAnswer, BotResponse.Source.LLM).also {
                history.add("HeroBot: ${it.text}")
            }
        }

        if (normalizedInput.contains("variable")) {
            MathEngine.explainMathConcept(normalizedInput)?.let { variableExplanation ->
                return BotResponse(variableExplanation, BotResponse.Source.LLM).also {
                    history.add("HeroBot: ${it.text}")
                }
            }
        }

        // 1. Exact match from DB
        val dbAnswer = try {
            db?.getAnswer(normalizedInput)
        } catch (e: Exception) {
            Log.e(TAG, "DB error getting answer", e)
            null
        }
        if (dbAnswer != null) {
            return BotResponse(dbAnswer, BotResponse.Source.DATABASE).also {
                history.add("HeroBot: ${it.text}")
            }
        }

        // 2. Bilingual built-in responses
        if (normalizedInput.contains("hello") ||
            normalizedInput.contains("hi ") ||
            normalizedInput == "hi" ||
            normalizedInput.contains("hey")
        ) {
            return BotResponse("Hi! I'm HeroBot.", BotResponse.Source.SYSTEM).also {
                history.add("HeroBot: ${it.text}")
            }
        }

        if (normalizedInput.contains("halo") ||
            normalizedInput.contains("hai") ||
            normalizedInput.contains("selamat pagi") ||
            normalizedInput.contains("selamat siang") ||
            normalizedInput.contains("selamat sore") ||
            normalizedInput.contains("selamat malam")
        ) {
            return BotResponse(
                "Halo! Saya HeroBot, asisten Android Anda.",
                BotResponse.Source.SYSTEM
            ).also { history.add("HeroBot: ${it.text}") }
        }

        if (normalizedInput.contains("who are you") ||
            normalizedInput.contains("siapa kamu") ||
            normalizedInput.contains("siapa namamu")
        ) {
            return BotResponse(
                "I am HeroBot, your Android assistant.",
                BotResponse.Source.SYSTEM
            ).also { history.add("HeroBot: ${it.text}") }
        }

        if (normalizedInput.contains("help") ||
            normalizedInput.contains("bantu") ||
            normalizedInput.contains("tolong")
        ) {
            return BotResponse(
                "I can answer questions, search the web for info, solve math, and learn custom responses.",
                BotResponse.Source.SYSTEM
            ).also { history.add("HeroBot: ${it.text}") }
        }

        // 3. Built-in personality
        if (normalizedInput.contains("favorite color") ||
            normalizedInput.contains("warna favorit") ||
            normalizedInput.contains("warna kesukaan")
        ) {
            return BotResponse(
                "I like Blue, it reminds me of a clean interface. Saya suka biru, karena terasa rapi dan bersih.",
                BotResponse.Source.SYSTEM
            )
        }

        // 4. NLP cosine similarity search against trained Q&A database
        findBestMatch(normalizedInput)?.let { smart ->
            return BotResponse(smart, BotResponse.Source.DATABASE).also {
                history.add("HeroBot: ${it.text}")
            }
        }

        val webSearchEnabled = isWebSearchEnabled()

        MathEngine.evaluateTrigonometricExpression(input)?.let { trigAnswer ->
            return BotResponse(trigAnswer, BotResponse.Source.LLM).also {
                history.add("HeroBot: ${it.text}")
            }
        }

        MathEngine.evaluateArithmeticExpression(input)?.let { arithmeticAnswer ->
            return BotResponse(arithmeticAnswer, BotResponse.Source.LLM).also {
                history.add("HeroBot: ${it.text}")
            }
        }

        MathEngine.explainMathConcept(normalizedInput)?.let { conceptAnswer ->
            return BotResponse(conceptAnswer, BotResponse.Source.LLM).also {
                history.add("HeroBot: ${it.text}")
            }
        }

        if (MathEngine.isMathematicsRequest(input)) {
            askLocalLLM(input)?.let { llmReply ->
                return BotResponse(llmReply, BotResponse.Source.LLM).also {
                    history.add("HeroBot: ${it.text}")
                }
            }
        }

        if (WebSearchService.shouldAttemptWebSearch(normalizedInput, webSearchEnabled)) {
            WebSearchService.fetchFromWebWithSource(normalizedInput)?.let { webResult ->
                val cleaned = WebSearchService.cleanText(webResult.text)
                var replyText = "According to the web: $cleaned"
                if (webResult.source == "google") {
                    replyText += "\nIf you'd like, you can train the chatbot by typing \"train\"."
                }
                return BotResponse(replyText, BotResponse.Source.WEB).also {
                    history.add("HeroBot: ${it.text}")
                }
            }
        }

        // 5. Local LLM prompt for general conversation
        if (shouldPreferLocalPromptBeforeWeb(normalizedInput)) {
            askLocalLLM(normalizedInput)?.let { llmReply ->
                return BotResponse(llmReply, BotResponse.Source.LLM).also {
                    history.add("HeroBot: ${it.text}")
                }
            }
        }

        // 6. General Internet search fallback
        if (webSearchEnabled) {
            WebSearchService.fetchFromWebWithSource(normalizedInput)?.let { webResult ->
                val cleaned = WebSearchService.cleanText(webResult.text)
                var replyText = "According to the web: $cleaned"
                if (webResult.source == "google") {
                    replyText += "\nIf you'd like, you can train the chatbot by typing \"train\"."
                }
                return BotResponse(replyText, BotResponse.Source.WEB).also {
                    history.add("HeroBot: ${it.text}")
                }
            }
        }

        return BotResponse(
            "I'm not sure how to answer that right now. Please try rephrasing or ask another question.",
            BotResponse.Source.NONE
        ).also { history.add("HeroBot: ${it.text}") }
    }

    fun train(question: String, answer: String): Int {
        ensureSeeded()
        return try {
            db?.insertQA(question.trim(), answer.trim())
            db?.getCount() ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "DB error inserting QA", e)
            0
        }
    }

    fun getKnowledgeCount(): Int = db?.getCount() ?: 0

    fun getParameters(): Map<String, String> = db?.getAllParameters().orEmpty()

    fun setParameter(key: String, value: String) {
        db?.setParameter(key, value)
    }

    fun importTrainingData(inputStream: InputStream) {
        val database = db ?: return
        val pairs = ArrayList<Pair<String, String>>()
        BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
            var currentQuestion: String? = null
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val trimmed = line?.trim().orEmpty()
                if (trimmed.startsWith("Q: ")) {
                    currentQuestion = trimmed.substring(3).trim()
                } else if (trimmed.startsWith("A: ") && currentQuestion != null) {
                    val answer = trimmed.substring(3).trim()
                    pairs.add(currentQuestion to answer)
                    currentQuestion = null
                }
            }
        }
        database.insertBatchQA(pairs)
        Log.d(TAG, "Seeded ${pairs.size} Q&A pairs.")
    }

    private fun findBestMatch(input: String): String? {
        val pairs = try {
            db?.getAllPairs().orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "DB error during best match search", e)
            return null
        }
        if (pairs.isEmpty()) return null

        var bestAnswer: String? = null
        var bestScore = 0.0
        val inputVector = NLPHelper.getVector(input)

        for ((question, answer) in pairs) {
            val questionVector = NLPHelper.getVector(question)
            val score = NLPHelper.cosineSimilarity(inputVector, questionVector)
            if (score > bestScore) {
                bestScore = score
                bestAnswer = answer
            }
        }

        return if (bestScore > 0.65) bestAnswer else null
    }

    private fun isWebSearchEnabled(): Boolean =
        db?.getParameter(DBHelper.KEY_WEB_SEARCH_ENABLED, "true")?.toBooleanStrictOrNull() ?: true

    private fun askLocalLLM(prompt: String): String? {
        val model = db?.getParameter(DBHelper.KEY_MODEL, "llama3.2:1b") ?: "llama3.2:1b"
        val temperature = db?.getParameter(DBHelper.KEY_TEMPERATURE, "0.7")?.toDoubleOrNull() ?: 0.7
        val topP = db?.getParameter(DBHelper.KEY_TOP_P, "0.9")?.toDoubleOrNull() ?: 0.9
        val stream = db?.getParameter(DBHelper.KEY_STREAM, "false")?.toBooleanStrictOrNull() ?: false

        return OllamaClient.askLocalLLM(
            prompt = prompt,
            history = history.getHistory(),
            model = model,
            temperature = temperature,
            topP = topP,
            stream = stream
        )
    }

    companion object {
        private const val TAG = "HeroBot_Logic"

        fun shouldPreferLocalPromptBeforeWeb(input: String?): Boolean {
            if (input == null) return false
            val normalized = NLPHelper.normalize(input)
            if (normalized.isEmpty()) return false
            return MathEngine.isMathematicsRequest(input) ||
                !WebSearchService.shouldAttemptWebSearch(normalized, true)
        }
    }
}
