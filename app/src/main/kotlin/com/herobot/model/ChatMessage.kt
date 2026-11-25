package com.herobot.model

import java.util.concurrent.atomic.AtomicLong

data class ChatMessage(
    val id: Long = nextId.incrementAndGet(),
    val text: String,
    val type: Type,
    val source: BotResponse.Source? = null,
    val modelId: String? = null,
    val roleTitle: String? = null,
    val isStreaming: Boolean = false,
    val timestampMillis: Long = System.currentTimeMillis()
) {
    enum class Type {
        USER,
        BOT
    }

    companion object {
        private val nextId = AtomicLong(0L)
    }
}
