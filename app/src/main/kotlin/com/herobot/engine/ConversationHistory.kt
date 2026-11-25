package com.herobot.engine

class ConversationHistory(private val maxSize: Int = 10) {
    private val logs = ArrayDeque<String>()

    @Synchronized
    fun add(message: String) {
        if (logs.size >= maxSize) {
            logs.removeFirst()
        }
        logs.addLast(message)
    }

    @Synchronized
    fun getHistory(): List<String> = logs.toList()

    @Synchronized
    fun getFormattedHistory(): String = logs.joinToString(separator = "\n")

    @Synchronized
    fun clear() {
        logs.clear()
    }
}
