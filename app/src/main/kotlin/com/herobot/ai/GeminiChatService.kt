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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

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
    data class Started(val role: AssistantRole) : GeminiStreamEvent
    data class Chunk(val accumulatedText: String, val role: AssistantRole) : GeminiStreamEvent
    data class Completed(val fullText: String, val role: AssistantRole) : GeminiStreamEvent
    data class Error(val message: String) : GeminiStreamEvent
}

object GeminiModelRouter {

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
}

class GeminiChatService {

    private val generalModel: GenerativeModel by lazy { createModel(AssistantRole.GENERAL) }
    private val deepReasoningModel: GenerativeModel by lazy { createModel(AssistantRole.DEEP_REASONING) }
    private val interactiveModel: GenerativeModel by lazy { createModel(AssistantRole.INTERACTIVE) }
    private val fastModel: GenerativeModel by lazy { createModel(AssistantRole.FAST) }

    private val conversationHistory = mutableListOf<Content>()
    private val learnedMemories = mutableListOf<Pair<String, String>>()

    private fun createModel(role: AssistantRole): GenerativeModel {
        return Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = role.modelName,
            systemInstruction = content(role = "system") {
                text(role.systemInstruction)
            }
        )
    }

    private fun modelFor(role: AssistantRole): GenerativeModel = when (role) {
        AssistantRole.GENERAL -> generalModel
        AssistantRole.DEEP_REASONING -> deepReasoningModel
        AssistantRole.INTERACTIVE -> interactiveModel
        AssistantRole.FAST -> fastModel
    }

    fun recordLearnedFact(question: String, answer: String) {
        learnedMemories.add(question.trim() to answer.trim())
    }

    fun clearHistory() {
        conversationHistory.clear()
    }

    fun sendMessageStream(
        userPrompt: String,
        selection: ReasoningSelection,
        localHint: String? = null
    ): Flow<GeminiStreamEvent> = flow {
        val role = GeminiModelRouter.resolveRole(userPrompt, selection)
        emit(GeminiStreamEvent.Started(role))

        val effectivePrompt = buildEffectivePrompt(userPrompt, localHint)
        val model = modelFor(role)
        val chat = model.startChat(history = conversationHistory.toList())
        val accumulatedText = StringBuilder()

        try {
            withTimeout(TIMEOUT_MILLIS) {
                chat.sendMessageStream(effectivePrompt).collect { chunk ->
                    val chunkText = chunk.text.orEmpty()
                    if (chunkText.isNotEmpty()) {
                        accumulatedText.append(chunkText)
                        emit(GeminiStreamEvent.Chunk(accumulatedText.toString(), role))
                    }
                }
            }

            val finalText = accumulatedText.toString().trim()
            if (finalText.isNotEmpty()) {
                conversationHistory.add(content(role = "user") { text(userPrompt) })
                conversationHistory.add(content(role = "model") { text(finalText) })
                if (conversationHistory.size > MAX_HISTORY_CONTENTS) {
                    repeat(conversationHistory.size - MAX_HISTORY_CONTENTS) {
                        conversationHistory.removeAt(0)
                    }
                }
                emit(GeminiStreamEvent.Completed(finalText, role))
            } else {
                emit(GeminiStreamEvent.Error("Gemini returned an empty response. Please try again."))
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Gemini chat generation timed out after ${TIMEOUT_MILLIS}ms", e)
            emit(
                GeminiStreamEvent.Error(
                    "Response timed out after 60 seconds. Try switching to Fast mode or retrying."
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini chat generation failed for model ${role.modelName}", e)
            emit(
                GeminiStreamEvent.Error(
                    e.localizedMessage ?: "Failed to generate response from Gemini."
                )
            )
        }
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

    companion object {
        private const val TAG = "GeminiChatService"
        private const val TIMEOUT_MILLIS = 60_000L
        private const val MAX_HISTORY_CONTENTS = 24
    }
}
