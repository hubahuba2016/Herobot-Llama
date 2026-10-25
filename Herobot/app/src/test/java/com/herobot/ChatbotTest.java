package com.herobot;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChatbotTest {

    @Test
    public void explicitSearchRequestsShouldUseWebSearch() {
        assertTrue(Chatbot.shouldAttemptWebSearch("can you search the web for python", true));
        assertTrue(Chatbot.shouldAttemptWebSearch("look up the latest news", true));
        assertTrue(Chatbot.shouldAttemptWebSearch("find me current weather", true));
    }

    @Test
    public void webSearchCanBeDisabled() {
        assertFalse(Chatbot.shouldAttemptWebSearch("hello there", false));
        assertFalse(Chatbot.shouldAttemptWebSearch("what is a chatbot", false));
    }

    @Test
    public void mathematicsRequestsShouldBeHandledLocallyBeforeWebSearch() {
        assertTrue(Chatbot.isMathematicsRequest("solve 2x + 5 = 15"));
        assertTrue(Chatbot.isMathematicsRequest("calculate 15 percent of 240"));
        assertTrue(Chatbot.shouldPreferLocalPromptBeforeWeb("solve 2x + 5 = 15"));
        assertTrue(Chatbot.shouldPreferLocalPromptBeforeWeb("explain the derivative of x squared"));
        assertTrue(Chatbot.evaluateArithmeticExpression("1+1").contains("= 2"));
        assertTrue(Chatbot.evaluateArithmeticExpression("3*9").contains("= 27"));
        assertTrue(Chatbot.isMathematicsRequest("2 log 8"));
        assertTrue(Chatbot.evaluateArithmeticExpression("2 log 8").contains("= 3"));
        assertTrue(Chatbot.evaluateTrigonometricExpression("sin 30").contains("0.5"));
        assertTrue(Chatbot.evaluateTrigonometricExpression("cos 60").contains("0.5"));
        assertTrue(Chatbot.evaluateTrigonometricExpression("tan 45").contains("1"));
        assertTrue(Chatbot.explainMathConcept("infinity").contains("without bound"));
    }

    @Test
    public void linearEquationsWithVariablesShouldBeSolvedLocally() {
        assertTrue(Chatbot.solveLinearEquation("solve 2x + 5 = 15").equals("x = 5"));
        assertTrue(Chatbot.solveLinearEquation("find x: 3 * (x - 2) = 9").equals("x = 5"));
        assertTrue(Chatbot.solveLinearEquation("4 = 2y").equals("y = 2"));
        assertTrue(Chatbot.solveLinearEquation("x / 2 + 3 = 7").equals("x = 8"));
    }

    @Test
    public void linearEquationSolverShouldReportDegenerateEquationsAndRejectUnsupportedOnes() {
        assertTrue(Chatbot.solveLinearEquation("2x + 1 = 2x + 1").contains("Every value"));
        assertTrue(Chatbot.solveLinearEquation("2x + 1 = 2x + 3").contains("no solution"));
        assertTrue(Chatbot.solveLinearEquation("x^2 = 4") == null);
        assertTrue(Chatbot.solveLinearEquation("x + y = 4") == null);
        assertTrue(Chatbot.solveLinearEquation("not an equation") == null);
    }

    @Test
    public void variableConceptShouldBeExplained() {
        assertTrue(Chatbot.explainMathConcept("what is a variable").contains("symbol"));
    }
}
