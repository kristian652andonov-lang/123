package com.kristian.jarvis.llm

import org.json.JSONArray
import org.json.JSONObject

/** What a streamed reply produces as it arrives. */
sealed interface LlmEvent {
    /** A piece of the spoken/visible answer. */
    data class TextDelta(val text: String) : LlmEvent

    /** A piece of reasoning, where the provider exposes it. */
    data class ThinkingDelta(val text: String) : LlmEvent

    /** The model wants a tool run. Handled by the tool loop. */
    data class ToolUse(val id: String, val name: String, val input: JSONObject) : LlmEvent
}

/** How one request/response turn ended. */
sealed interface TurnResult {
    /**
     * [content] is the assistant turn in the canonical (Anthropic) wire format,
     * ready to go straight back into the conversation.
     */
    data class Completed(
        val stopReason: String?,
        val content: JSONArray,
        val toolUses: List<LlmEvent.ToolUse>,
        val refusalExplanation: String? = null
    ) : TurnResult

    data class Failed(val message: String, val retryable: Boolean) : TurnResult
}

/**
 * One model backend.
 *
 * Conversation history is always kept in the Anthropic message format - it is
 * the more expressive of the two - and each client translates on the way out
 * and back. That keeps the turn loop, the tool registry and the HUD entirely
 * unaware of which provider is answering.
 */
interface LlmClient {

    /** Human-readable, for settings and error messages. */
    val displayName: String

    /**
     * Runs one turn. [onEvent] fires on a background thread as the response
     * streams; callers marshal to the main thread themselves.
     */
    suspend fun stream(
        messages: JSONArray,
        systemPrompt: String,
        tools: JSONArray?,
        narrate: Boolean,
        onEvent: (LlmEvent) -> Unit
    ): TurnResult
}

/** Which service answers. */
enum class LlmProvider(val label: String, val keyHint: String) {
    /** Paid, per-token, billed to an Anthropic API key. */
    ANTHROPIC("Claude (Anthropic)", "sk-ant-…"),

    /** Has a genuine free tier; a Google account is all that's needed. */
    GEMINI("Gemini (Google)", "AIza… or AQ.…");

    companion object {
        fun fromName(name: String?): LlmProvider =
            entries.firstOrNull { it.name == name } ?: GEMINI

        /**
         * Works out which service a pasted key belongs to, so there is one
         * box to paste into rather than a provider picker to get wrong.
         */
        fun detectFromKey(key: String): LlmProvider? {
            val trimmed = key.trim()
            return when {
                trimmed.startsWith("sk-ant-") -> ANTHROPIC
                // Everything else long enough is assumed to be a Google key:
                // that format has changed before (AIza… then AQ.…) and a
                // hardcoded prefix would reject a valid key.
                trimmed.length >= 20 && trimmed.none { it.isWhitespace() } -> GEMINI
                else -> null
            }
        }
    }
}
