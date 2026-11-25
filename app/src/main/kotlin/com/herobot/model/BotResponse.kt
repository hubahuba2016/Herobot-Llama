package com.herobot.model

/**
 * Encapsulates the bot's response text and its origin source.
 */
data class BotResponse(
    val text: String,
    val source: Source
) {
    enum class Source(val label: String) {
        DATABASE("Database"),
        WEB("Web"),
        LLM("LLM"),
        SYSTEM("System"),
        NONE("None")
    }

    override fun toString(): String = text
}
