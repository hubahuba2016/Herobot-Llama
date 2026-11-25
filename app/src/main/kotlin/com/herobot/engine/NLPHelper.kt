package com.herobot.engine

import java.text.Normalizer
import java.util.Locale
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Core NLP utility for text normalization, tokenization, and cosine similarity.
 */
object NLPHelper {

    private val STOPWORDS = setOf(
        "what", "is", "the", "a", "an", "are", "you", "can", "to", "of", "and",
        "in", "on", "at", "for", "with", "who", "why", "how", "when", "where"
    )

    private val COMBINING_MARKS_REGEX = Regex("\\p{M}+")
    private val NON_ALNUM_SPACE_REGEX = Regex("[^\\p{L}\\p{N} ]")
    private val WHITESPACE_REGEX = Regex("\\s+")

    fun normalize(text: String?): String {
        if (text == null) return ""
        var normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
        normalized = COMBINING_MARKS_REGEX.replace(normalized, "")
        normalized = NON_ALNUM_SPACE_REGEX.replace(normalized, " ")
        return normalized.lowercase(Locale.ROOT).trim()
    }

    fun tokenize(text: String?): List<String> {
        if (text == null) return emptyList()
        val normalized = normalize(text)
        if (normalized.isEmpty()) return emptyList()
        return normalized.split(WHITESPACE_REGEX)
            .filter { it.isNotEmpty() && it !in STOPWORDS }
    }

    fun getVector(text: String?): Map<String, Int> {
        val vector = HashMap<String, Int>()
        for (word in tokenize(text)) {
            vector[word] = (vector[word] ?: 0) + 1
        }
        return vector
    }

    fun cosineSimilarity(v1: Map<String, Int>, v2: Map<String, Int>): Double {
        var dotProduct = 0.0
        var norm1 = 0.0
        var norm2 = 0.0

        for ((key, c1) in v1) {
            norm1 += c1.toDouble().pow(2.0)
            val c2 = v2[key]
            if (c2 != null) {
                dotProduct += c1.toDouble() * c2.toDouble()
            }
        }

        for (c2 in v2.values) {
            norm2 += c2.toDouble().pow(2.0)
        }

        return if (norm1 == 0.0 || norm2 == 0.0) {
            0.0
        } else {
            dotProduct / (sqrt(norm1) * sqrt(norm2))
        }
    }
}
