package com.herobot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ChatbotWebSearchTest {
    @Test
    public void extractsSnippetFromGoogleSearchHtml() {
        String html = "<html><body><div class=\"BNeawe vvjwJb AP7Wnd\">Paris is the capital of France</div></body></html>";

        assertEquals("Paris is the capital of France", Chatbot.extractSearchResultSnippet(html));
    }

    @Test
    public void returnsGoogleSearchUrlWhenSnippetMissing() {
        String url = Chatbot.fetchGoogleSearchUrl("paris capital of france");

        assertEquals("https://www.google.com/search?hl=en&q=paris+capital+of+france", url);
    }
}
