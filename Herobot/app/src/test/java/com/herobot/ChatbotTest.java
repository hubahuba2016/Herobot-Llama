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
    }
}
