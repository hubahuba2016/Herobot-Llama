package com.herobot;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class NLPHelperTest {

    @Test
    public void tokenize_should_support_english_and_indonesian_words() {
        String input = "apa itu kecerdasan buatan dan artificial intelligence";
        List<String> tokens = NLPHelper.tokenize(input);

        assertTrue(tokens.contains("apa"));
        assertTrue(tokens.contains("itu"));
        assertTrue(tokens.contains("kecerdasan"));
        assertTrue(tokens.contains("buatan"));
        assertTrue(tokens.contains("artificial"));
        assertTrue(tokens.contains("intelligence"));
    }
}
