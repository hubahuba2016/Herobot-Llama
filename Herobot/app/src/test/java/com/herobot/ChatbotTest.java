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
}
