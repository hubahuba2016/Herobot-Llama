package com.herobot.engine

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Web search service using Wikipedia REST, DuckDuckGo Instant Answer,
 * SearxNG JSON, and Google snippet fallback.
 */
object WebSearchService {

    data class WebSearchResult(val text: String, val source: String)

    fun shouldAttemptWebSearch(input: String?, webSearchEnabled: Boolean): Boolean {
        if (!webSearchEnabled || input == null) return false
        val normalized = NLPHelper.normalize(input)
        if (normalized.isEmpty()) return false

        if (normalized.contains("search the web") ||
            normalized.contains("search web") ||
            normalized.contains("browse the web") ||
            normalized.contains("browse web")
        ) {
            return true
        }

        if (normalized.contains("look up") ||
            normalized.contains("lookup") ||
            normalized.contains("find me") ||
            normalized.contains("find out")
        ) {
            return true
        }

        if (normalized.contains("can you tell me about") ||
            normalized.contains("tell me about") ||
            normalized.contains("explain")
        ) {
            return true
        }

        if (normalized.contains("latest news") ||
            normalized.contains("current weather") ||
            normalized.contains("today") ||
            normalized.contains("now")
        ) {
            return true
        }

        return false
    }

    fun cleanText(text: String?): String {
        if (text == null) return ""
        return text.replace(Regex("\\[\\d+]|\\[[^\\]]+]"), "").trim()
    }

    fun fetchFromWebWithSource(query: String): WebSearchResult? {
        fetchFromWikipedia(query)?.takeIf { it.isNotEmpty() }?.let {
            return WebSearchResult(it, "wikipedia")
        }

        fetchFromDuckDuckGo(query)?.takeIf { it.isNotEmpty() }?.let {
            return WebSearchResult(it, "duckduckgo")
        }

        fetchFromSearxng(query)?.takeIf { it.isNotEmpty() }?.let {
            return WebSearchResult(it, "searxng")
        }

        fetchFromGoogle(query)?.takeIf { it.isNotEmpty() }?.let {
            return WebSearchResult(it, "google")
        }

        return null
    }

    fun fetchGoogleSearchUrl(query: String): String {
        return try {
            "https://www.google.com/search?hl=en&q=" +
                URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        } catch (_: Exception) {
            "https://www.google.com/search?hl=en&q=" + query.replace(" ", "+")
        }
    }

    fun extractSearchResultSnippet(html: String?): String? {
        if (html.isNullOrEmpty()) return null

        val patterns = listOf(
            "(?i)<div[^>]*class=\"[^\"]*(?:BNeawe|Vwi3C|kCrYT)[^\"]*\"[^>]*>(.*?)</div>",
            "(?i)<span[^>]*class=\"[^\"]*(?:BNeawe|Vwi3C|kCrYT)[^\"]*\"[^>]*>(.*?)</span>"
        )

        for (pattern in patterns) {
            val regex = Pattern.compile(pattern, Pattern.DOTALL)
            val matcher = regex.matcher(html)
            if (matcher.find()) {
                var snippet = matcher.group(1) ?: continue
                snippet = snippet.replace(Regex("<[^>]+>"), " ")
                snippet = snippet.replace(Regex("\\s+"), " ").trim()
                if (snippet.isNotEmpty()) {
                    return snippet
                }
            }
        }
        return null
    }

    private fun createUrl(urlString: String): URL = URI.create(urlString).toURL()

    private fun fetchFromWikipedia(query: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val urlStr = "https://en.wikipedia.org/api/rest_v1/page/summary/" +
                URLEncoder.encode(query.replace(" ", "_"), StandardCharsets.UTF_8.name())
            conn = (createUrl(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val json = JSONObject(body)
                json.optString("extract", "").ifEmpty { null }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun fetchFromDuckDuckGo(query: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val urlStr = "https://api.duckduckgo.com/?q=" +
                URLEncoder.encode(query, StandardCharsets.UTF_8.name()) +
                "&format=json&no_html=1&skip_disambig=1"
            conn = (createUrl(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val json = JSONObject(body)
                val abstractText = json.optString("AbstractText", "")
                if (abstractText.isNotEmpty()) return abstractText

                if (json.has("RelatedTopics")) {
                    val topics = json.getJSONArray("RelatedTopics")
                    if (topics.length() > 0) {
                        val firstTopic = topics.optJSONObject(0)
                        if (firstTopic != null) {
                            return firstTopic.optString("Text", "").ifEmpty { null }
                        }
                    }
                }
                null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun fetchFromSearxng(query: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val urlStr = "https://searxng.org/search?format=json&q=" +
                URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            conn = (createUrl(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0")
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val json = JSONObject(body)
                if (json.has("results")) {
                    val results = json.getJSONArray("results")
                    if (results.length() > 0) {
                        val first = results.optJSONObject(0)
                        if (first != null) {
                            val content = first.optString("content", "")
                            if (content.isNotEmpty()) return content
                            val title = first.optString("title", "")
                            if (title.isNotEmpty()) return title
                        }
                    }
                }
                null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun fetchFromGoogle(query: String): String {
        var conn: HttpURLConnection? = null
        try {
            val urlStr = "https://www.google.com/search?hl=en&num=3&q=" +
                URLEncoder.encode(query, StandardCharsets.UTF_8.name())
            conn = (createUrl(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0")
                connectTimeout = 5000
                readTimeout = 5000
            }
            if (conn.responseCode == 200) {
                val body = BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use {
                    it.readText()
                }
                val snippet = extractSearchResultSnippet(body)
                if (!snippet.isNullOrEmpty()) {
                    return snippet
                }
            }
        } catch (_: Exception) {
            // Fall through to search link
        } finally {
            conn?.disconnect()
        }

        return "See Google search results: ${fetchGoogleSearchUrl(query)}"
    }
}
