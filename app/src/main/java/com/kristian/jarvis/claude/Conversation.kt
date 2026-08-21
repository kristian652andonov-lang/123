package com.kristian.jarvis.claude

import org.json.JSONArray
import org.json.JSONObject

/**
 * The running conversation sent to Claude on every turn.
 *
 * Messages are kept in wire format so no translation is needed at request
 * time, and so assistant content - including thinking blocks and their
 * signatures - can be echoed back exactly as received, which the API requires.
 */
class Conversation(private val maxMessages: Int = 40) {

    private val messages = mutableListOf<JSONObject>()

    val size: Int get() = messages.size

    fun addUserText(text: String) {
        messages += JSONObject().apply {
            put("role", "user")
            put(
                "content",
                JSONArray().put(
                    JSONObject().put("type", "text").put("text", text)
                )
            )
        }
        trim()
    }

    /** Stores the assistant turn exactly as the API returned it. */
    fun addAssistantContent(content: JSONArray) {
        if (content.length() == 0) return
        messages += JSONObject().apply {
            put("role", "assistant")
            put("content", content)
        }
        trim()
    }

    /**
     * Tool results must all come back in a single user message - splitting
     * them teaches Claude to stop calling tools in parallel.
     */
    fun addToolResults(results: List<JSONObject>) {
        if (results.isEmpty()) return
        val content = JSONArray()
        results.forEach { content.put(it) }
        messages += JSONObject().apply {
            put("role", "user")
            put("content", content)
        }
        trim()
    }

    fun snapshot(): JSONArray = JSONArray().also { array ->
        messages.forEach { array.put(it) }
    }

    fun clear() = messages.clear()

    /**
     * Drops the oldest turns once the history gets long, always cutting at a
     * plain user message. Cutting anywhere else could orphan a tool_result
     * from its tool_use, which the API rejects.
     */
    private fun trim() {
        if (messages.size <= maxMessages) return
        var index = messages.size - maxMessages
        while (index < messages.size && !isPlainUserMessage(messages[index])) index++
        if (index >= messages.size) return
        repeat(index) { messages.removeAt(0) }
    }

    private fun isPlainUserMessage(message: JSONObject): Boolean {
        if (message.optString("role") != "user") return false
        val content = message.optJSONArray("content") ?: return true
        for (i in 0 until content.length()) {
            if (content.optJSONObject(i)?.optString("type") == "tool_result") return false
        }
        return true
    }
}
