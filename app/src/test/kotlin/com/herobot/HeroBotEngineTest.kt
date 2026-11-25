package com.herobot

import com.herobot.ai.AssistantRole
import com.herobot.ai.GeminiModelRouter
import com.herobot.ai.ReasoningSelection
import com.herobot.engine.ChatbotEngine
import com.herobot.engine.MathEngine
import com.herobot.engine.NLPHelper
import com.herobot.engine.WebSearchService
import com.herobot.voice.VoiceAssistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeroBotEngineTest {

    @Test
    fun explicitSearchRequestsShouldUseWebSearch() {
        assertTrue(WebSearchService.shouldAttemptWebSearch("can you search the web for python", true))
        assertTrue(WebSearchService.shouldAttemptWebSearch("look up the latest news", true))
        assertTrue(WebSearchService.shouldAttemptWebSearch("find me current weather", true))
    }

    @Test
    fun webSearchCanBeDisabled() {
        assertFalse(WebSearchService.shouldAttemptWebSearch("hello there", false))
        assertFalse(WebSearchService.shouldAttemptWebSearch("what is a chatbot", false))
    }

    @Test
    fun mathematicsRequestsShouldBeHandledLocallyBeforeWebSearch() {
        assertTrue(MathEngine.isMathematicsRequest("solve 2x + 5 = 15"))
        assertTrue(MathEngine.isMathematicsRequest("calculate 15 percent of 240"))
        assertTrue(ChatbotEngine.shouldPreferLocalPromptBeforeWeb("solve 2x + 5 = 15"))
        assertTrue(ChatbotEngine.shouldPreferLocalPromptBeforeWeb("explain the derivative of x squared"))
        assertTrue(MathEngine.evaluateArithmeticExpression("1+1")!!.contains("= 2"))
        assertTrue(MathEngine.evaluateArithmeticExpression("3*9")!!.contains("= 27"))
        assertTrue(MathEngine.evaluateArithmeticExpression("3×9")!!.contains("= 27"))
        assertTrue(MathEngine.evaluateArithmeticExpression("2*-3")!!.contains("= -6"))
        assertTrue(MathEngine.evaluateArithmeticExpression("-3*2")!!.contains("= -6"))
        assertTrue(MathEngine.isMathematicsRequest("3×9"))
        assertTrue(MathEngine.isMathematicsRequest("2 log 8"))
        assertTrue(MathEngine.evaluateArithmeticExpression("2 log 8")!!.contains("= 3"))
        assertTrue(MathEngine.evaluateTrigonometricExpression("sin 30")!!.contains("0.5"))
        assertTrue(MathEngine.evaluateTrigonometricExpression("cos 60")!!.contains("0.5"))
        assertTrue(MathEngine.evaluateTrigonometricExpression("tan 45")!!.contains("1"))
        assertTrue(MathEngine.explainMathConcept("infinity")!!.contains("without bound"))
    }

    @Test
    fun linearEquationsWithVariablesShouldBeSolvedLocally() {
        assertEquals("x = 5", MathEngine.solveLinearEquation("solve 2x + 5 = 15"))
        assertEquals("x = 5", MathEngine.solveLinearEquation("find x: 3 * (x - 2) = 9"))
        assertEquals("x = 5", MathEngine.solveLinearEquation("find x: 3 × (x - 2) = 9"))
        assertEquals("y = 2", MathEngine.solveLinearEquation("4 = 2y"))
        assertEquals("x = 8", MathEngine.solveLinearEquation("x / 2 + 3 = 7"))
    }

    @Test
    fun linearEquationSolverShouldReportDegenerateEquationsAndRejectUnsupportedOnes() {
        assertTrue(MathEngine.solveLinearEquation("2x + 1 = 2x + 1")!!.contains("Every value"))
        assertTrue(MathEngine.solveLinearEquation("2x + 1 = 2x + 3")!!.contains("no solution"))
        assertNull(MathEngine.solveLinearEquation("x^2 = 4"))
        assertNull(MathEngine.solveLinearEquation("x + y = 4"))
        assertNull(MathEngine.solveLinearEquation("not an equation"))
    }

    @Test
    fun variableConceptShouldBeExplained() {
        assertTrue(MathEngine.explainMathConcept("what is a variable")!!.contains("symbol"))
    }

    @Test
    fun extractsSnippetFromGoogleSearchHtml() {
        val html = "<html><body><div class=\"BNeawe vvjwJb AP7Wnd\">Paris is the capital of France</div></body></html>"
        assertEquals("Paris is the capital of France", WebSearchService.extractSearchResultSnippet(html))
    }

    @Test
    fun returnsGoogleSearchUrlWhenSnippetMissing() {
        val url = WebSearchService.fetchGoogleSearchUrl("paris capital of france")
        assertEquals("https://www.google.com/search?hl=en&q=paris+capital+of+france", url)
    }

    @Test
    fun tokenizeShouldSupportEnglishAndIndonesianWords() {
        val input = "apa itu kecerdasan buatan dan artificial intelligence"
        val tokens = NLPHelper.tokenize(input)
        assertTrue(tokens.contains("apa"))
        assertTrue(tokens.contains("itu"))
        assertTrue(tokens.contains("kecerdasan"))
        assertTrue(tokens.contains("buatan"))
        assertTrue(tokens.contains("artificial"))
        assertTrue(tokens.contains("intelligence"))
    }

    @Test
    fun shouldPreferLocalPromptBeforeWebLookup() {
        assertTrue(ChatbotEngine.shouldPreferLocalPromptBeforeWeb("what is java"))
        assertTrue(ChatbotEngine.shouldPreferLocalPromptBeforeWeb("hello there"))
    }

    @Test
    fun geminiRouterSelectsExpectedModelsAndRoles() {
        val deepRole = GeminiModelRouter.resolveRole(
            "Why does 2x + 5 = 15 solve to 5? Prove it step by step.",
            ReasoningSelection.Auto
        )
        assertEquals(AssistantRole.DEEP_REASONING, deepRole)
        assertEquals("gemini-4-carbon", deepRole.modelName)

        val interactiveRole = GeminiModelRouter.resolveRole(
            "Help me design and architect a multi-screen Kotlin Compose app.",
            ReasoningSelection.Auto
        )
        assertEquals(AssistantRole.INTERACTIVE, interactiveRole)
        assertEquals("gemini-4-neon", interactiveRole.modelName)

        val fastRole = GeminiModelRouter.resolveRole("hello", ReasoningSelection.Auto)
        assertEquals(AssistantRole.FAST, fastRole)
        assertEquals("gemini-3.1-flash-lite", fastRole.modelName)

        val generalRole = GeminiModelRouter.resolveRole(
            "Tell me an interesting story about space exploration today.",
            ReasoningSelection.Auto
        )
        assertEquals(AssistantRole.GENERAL, generalRole)
        assertEquals("gemini-4-hydrogen", generalRole.modelName)

        val fixedRole = GeminiModelRouter.resolveRole(
            "hello",
            ReasoningSelection.Fixed(AssistantRole.DEEP_REASONING)
        )
        assertEquals(AssistantRole.DEEP_REASONING, fixedRole)

        val fallbackCandidates = GeminiModelRouter.resolveCandidateModels(
            role = AssistantRole.DEEP_REASONING,
            selection = ReasoningSelection.Auto,
            exhaustedModels = setOf("gemini-4-carbon")
        )
        assertFalse(fallbackCandidates.contains("gemini-4-carbon"))
        assertEquals("gemini-4-hydrogen", fallbackCandidates.first())
        assertTrue(fallbackCandidates.contains("gemini-3.8-flash"))
        assertTrue(fallbackCandidates.contains("gemini-3.5-flash-lite"))
    }

    @Test
    fun voiceAssistantSanitizesSymbolsBeforeSpeaking() {
        val raw = "### Step-by-Step\n* **Solve** \$2x + 5 = 15\$\n- Answer: `x = 5`\n---"
        val spoken = VoiceAssistant.sanitizeForSpeech(raw)

        assertFalse(spoken.contains("*"))
        assertFalse(spoken.contains("#"))
        assertFalse(spoken.contains("$"))
        assertFalse(spoken.contains("-"))
        assertFalse(spoken.contains("`"))
        assertEquals("Step by Step\nSolve 2x + 5 = 15\nAnswer: x = 5", spoken)
    }
}
