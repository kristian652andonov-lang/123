package com.kristian.jarvis.claude

import android.util.Log
import com.kristian.jarvis.settings.JarvisPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What the stream produces as a reply comes in. */
sealed interface ClaudeEvent {
    /** A piece of the spoken/visible answer. */
    data class TextDelta(val text: String) : ClaudeEvent

    /** A piece of summarised reasoning; only sent in narrate mode. */
    data class ThinkingDelta(val text: String) : ClaudeEvent

    /** Claude wants a tool run. Handled by the tool loop. */
    data class ToolUse(val id: String, val name: String, val input: JSONObject) : ClaudeEvent

    /**
     * Turn finished. [content] is the assistant turn in wire format, to be put
     * straight back into the conversation.
     */
    data class Completed(
        val stopReason: String?,
        val content: JSONArray,
        val refusalExplanation: String? = null
    ) : ClaudeEvent

    data class Failed(val message: String, val retryable: Boolean) : ClaudeEvent
}

/**
 * Talks to the Anthropic Messages API directly from the phone - no backend in
 * between, and the key never leaves the device except as the x-api-key header
 * on the request itself.
 *
 * Responses are streamed so speech can start on the first complete sentence
 * instead of after the whole answer.
 */
class AnthropicClient(
    private val keys: SecureKeyStore,
    private val prefs: JarvisPrefs
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Runs one turn. [onEvent] fires on a background thread as the response
     * streams; callers marshal to the main thread themselves.
     */
    suspend fun stream(
        messages: JSONArray,
        systemPrompt: String,
        tools: JSONArray?,
        narrate: Boolean,
        onEvent: (ClaudeEvent) -> Unit
    ) = withContext(Dispatchers.IO) {
        val apiKey = keys.apiKey
        if (apiKey == null) {
            onEvent(ClaudeEvent.Failed("No Anthropic API key is stored yet.", retryable = false))
            return@withContext
        }

        val model = prefs.model
        val body = buildRequestBody(messages, systemPrompt, tools, narrate, model)

        val builder = Request.Builder()
            .url(ENDPOINT)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("content-type", "application/json")
        if (supportsServerFallback(model)) {
            builder.addHeader("anthropic-beta", FALLBACK_BETA)
        }
        val request = builder
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    onEvent(describeHttpFailure(response.code, response.body?.string()))
                    return@use
                }
                val source = response.body?.source()
                if (source == null) {
                    onEvent(ClaudeEvent.Failed("Empty response from Claude.", retryable = true))
                    return@use
                }
                parseStream(readLines = { source.readUtf8Line() }, onEvent = onEvent)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Request failed", e)
            onEvent(
                ClaudeEvent.Failed(
                    "I couldn't reach Claude - the connection failed. Check your network, sir.",
                    retryable = true
                )
            )
        }
    }

    private fun buildRequestBody(
        messages: JSONArray,
        systemPrompt: String,
        tools: JSONArray?,
        narrate: Boolean,
        model: String
    ): JSONObject = JSONObject().apply {
        put("model", model)
        put("max_tokens", prefs.maxTokens)
        put("stream", true)
        // System prompt as a cacheable block: it is stable across the whole
        // conversation, so it should be a cache prefix rather than re-read every turn.
        put(
            "system",
            JSONArray().put(
                JSONObject()
                    .put("type", "text")
                    .put("text", systemPrompt)
                    .put("cache_control", JSONObject().put("type", "ephemeral"))
            )
        )
        put("messages", messages)
        tools?.takeIf { it.length() > 0 }?.let { put("tools", it) }

        // Narrate mode asks for the reasoning to be shown, and is worth more
        // effort; ordinary spoken answers want speed.
        put(
            "output_config",
            JSONObject().put("effort", if (narrate) "high" else prefs.effort)
        )
        if (narrate) {
            put(
                "thinking",
                JSONObject().put("type", "adaptive").put("display", "summarized")
            )
        }
        if (supportsServerFallback(model)) {
            // On a policy decline, the API retries the same request on a
            // fallback model inside the same call instead of just stopping.
            put("fallbacks", "default")
        }
    }

    /**
     * Consumes the SSE stream, rebuilding the assistant turn block by block so
     * it can be stored verbatim - thinking signatures included, which the API
     * requires when the history is sent back.
     */
    private fun parseStream(readLines: () -> String?, onEvent: (ClaudeEvent) -> Unit) {
        val blocks = sortedMapOf<Int, BlockBuilder>()
        var stopReason: String? = null
        var refusalExplanation: String? = null

        while (true) {
            val line = readLines() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) continue

            val json = runCatching { JSONObject(payload) }.getOrNull() ?: continue
            when (json.optString("type")) {
                "content_block_start" -> {
                    val index = json.optInt("index")
                    val block = json.optJSONObject("content_block")
                    blocks[index] = BlockBuilder(
                        type = block?.optString("type").orEmpty(),
                        id = block?.optString("id").orEmpty(),
                        name = block?.optString("name").orEmpty()
                    ).also { builder ->
                        block?.optString("text")?.let { builder.text.append(it) }
                    }
                }

                "content_block_delta" -> {
                    val index = json.optInt("index")
                    val builder = blocks.getOrPut(index) { BlockBuilder() }
                    val delta = json.optJSONObject("delta") ?: continue
                    when (delta.optString("type")) {
                        "text_delta" -> delta.optString("text").let {
                            builder.text.append(it)
                            if (it.isNotEmpty()) onEvent(ClaudeEvent.TextDelta(it))
                        }

                        "thinking_delta" -> delta.optString("thinking").let {
                            builder.thinking.append(it)
                            if (it.isNotEmpty()) onEvent(ClaudeEvent.ThinkingDelta(it))
                        }

                        "signature_delta" -> builder.signature.append(delta.optString("signature"))

                        "input_json_delta" -> builder.partialJson.append(
                            delta.optString("partial_json")
                        )
                    }
                }

                "content_block_stop" -> {
                    val builder = blocks[json.optInt("index")] ?: continue
                    if (builder.type == "tool_use") {
                        onEvent(
                            ClaudeEvent.ToolUse(
                                id = builder.id,
                                name = builder.name,
                                input = builder.parsedInput()
                            )
                        )
                    }
                }

                "message_delta" -> {
                    val delta = json.optJSONObject("delta")
                    delta?.optString("stop_reason")?.takeIf { it.isNotEmpty() }?.let {
                        stopReason = it
                    }
                    json.optJSONObject("stop_details")?.let {
                        refusalExplanation = it.optString("explanation").takeIf { e -> e.isNotEmpty() }
                    }
                }

                "message_stop" -> {
                    val content = JSONArray()
                    blocks.values.mapNotNull { it.toWireBlock() }.forEach { content.put(it) }
                    onEvent(ClaudeEvent.Completed(stopReason, content, refusalExplanation))
                    return
                }

                "error" -> {
                    val error = json.optJSONObject("error")
                    val message = error?.optString("message").orEmpty()
                    onEvent(
                        ClaudeEvent.Failed(
                            message.ifEmpty { "Claude reported an error mid-response." },
                            retryable = true
                        )
                    )
                    return
                }
            }
        }

        // Stream ended without a message_stop - deliver whatever was built.
        val content = JSONArray()
        blocks.values.mapNotNull { it.toWireBlock() }.forEach { content.put(it) }
        onEvent(ClaudeEvent.Completed(stopReason, content, refusalExplanation))
    }

    private fun describeHttpFailure(code: Int, rawBody: String?): ClaudeEvent.Failed {
        val apiMessage = rawBody
            ?.let { runCatching { JSONObject(it).optJSONObject("error")?.optString("message") }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

        return when (code) {
            401, 403 -> ClaudeEvent.Failed(
                "That API key was rejected. Check it in settings, sir.",
                retryable = false
            )

            404 -> ClaudeEvent.Failed(
                apiMessage ?: "That model isn't available to this key.",
                retryable = false
            )

            429 -> ClaudeEvent.Failed(
                "Rate limited by the API. Give it a moment.",
                retryable = true
            )

            in 500..599 -> ClaudeEvent.Failed(
                "The API is having trouble at its end. Worth retrying.",
                retryable = true
            )

            else -> ClaudeEvent.Failed(
                apiMessage ?: "The request was rejected (HTTP $code).",
                retryable = false
            )
        }
    }

    /** One content block being assembled from deltas. */
    private class BlockBuilder(
        var type: String = "text",
        var id: String = "",
        var name: String = ""
    ) {
        val text = StringBuilder()
        val thinking = StringBuilder()
        val signature = StringBuilder()
        val partialJson = StringBuilder()

        fun parsedInput(): JSONObject =
            runCatching { JSONObject(partialJson.toString().ifBlank { "{}" }) }
                .getOrElse { JSONObject() }

        /** The block in the shape the API expects it back. */
        fun toWireBlock(): JSONObject? = when (type) {
            "text" -> text.toString().takeIf { it.isNotEmpty() }?.let {
                JSONObject().put("type", "text").put("text", it)
            }

            "thinking" -> JSONObject()
                .put("type", "thinking")
                .put("thinking", thinking.toString())
                .put("signature", signature.toString())

            "tool_use" -> JSONObject()
                .put("type", "tool_use")
                .put("id", id)
                .put("name", name)
                .put("input", parsedInput())

            else -> null
        }
    }

    companion object {
        private const val TAG = "AnthropicClient"
        private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /** Server-side refusal fallbacks exist on the Opus 5 / Fable 5 tier only. */
        fun supportsServerFallback(model: String): Boolean =
            model.startsWith("claude-opus-5") || model.startsWith("claude-fable-5")
    }
}
