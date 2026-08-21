package com.kristian.jarvis.core

import android.content.Context
import com.kristian.jarvis.tools.ToolCatalog
import com.kristian.jarvis.tools.ToolRunner
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Talks to the Messages API and runs the tool loop.
 *
 * Replies stream, because a voice assistant that waits for the whole answer before
 * speaking feels broken. Each text delta is emitted as it arrives so the caller can start
 * the speech synthesiser on the first finished sentence.
 */
class ClaudeClient(
    context: Context,
    private val prefs: Prefs,
    private val tools: ToolRunner,
) {

    sealed interface Event {
        /** A fragment of the spoken reply. */
        data class Text(val delta: String) : Event

        /** Something worth showing on the HUD while it happens, e.g. "searching the web". */
        data class Working(val label: String) : Event

        /** The turn finished cleanly. */
        data class Finished(val reply: String) : Event

        /** The turn failed; [message] is already phrased for the user. */
        data class Failed(val message: String) : Event
    }

    private val app = context.applicationContext

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** The running conversation, in Messages API shape. */
    private val messages = JSONArray()

    @Volatile
    private var activeCall: Call? = null

    /**
     * Set if the API rejects the server-side web search tool — some keys and models cannot
     * use it. We drop it for the rest of the session rather than failing every request,
     * and leave the user's own setting alone.
     */
    @Volatile
    private var webSearchBlocked = false

    /** True while a turn is in flight. */
    @Volatile
    var busy: Boolean = false
        private set

    fun reset() {
        cancel()
        while (messages.length() > 0) messages.remove(0)
    }

    fun cancel() {
        activeCall?.cancel()
        activeCall = null
    }

    fun ask(userText: String): Flow<Event> = flow {
        val settings = prefs.current
        if (!settings.isConfigured) {
            emit(Event.Failed("No API key has been set. Open settings and paste one in."))
            return@flow
        }

        messages.put(message("user", JSONArray().put(textBlock(userText))))
        trimHistory()

        busy = true
        val spoken = StringBuilder()
        var allowBetas = true

        try {
            var round = 0
            while (round < MAX_ROUNDS) {
                round++

                val turn = try {
                    streamTurn(settings, allowBetas) { event ->
                        if (event is Event.Text) spoken.append(event.delta)
                        emit(event)
                    }
                } catch (e: BetaRejected) {
                    // The account cannot use one of the opt-in features; drop them and retry
                    // once rather than failing the whole request.
                    allowBetas = false
                    round--
                    continue
                } catch (e: ApiFailure) {
                    emit(Event.Failed(e.message ?: "The request failed."))
                    return@flow
                }

                if (turn.stopReason == "tool_use") {
                    val results = JSONArray()
                    for (i in 0 until turn.content.length()) {
                        val block = turn.content.optJSONObject(i) ?: continue
                        if (block.optString("type") != "tool_use") continue
                        val name = block.optString("name")
                        if (!ToolCatalog.isClientTool(name)) continue

                        emit(Event.Working(describe(name)))
                        val outcome = tools.run(name, block.optJSONObject("input") ?: JSONObject())
                        results.put(
                            JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", block.optString("id"))
                                .put("content", outcome.text)
                                .apply { if (outcome.isError) put("is_error", true) },
                        )
                    }
                    // Only keep the tool calls in the history if they are answered on the
                    // very next message — the API rejects an unpaired tool_use block.
                    if (results.length() > 0) {
                        messages.put(message("assistant", turn.content))
                        messages.put(message("user", results))
                        trimHistory()
                        continue
                    }
                }

                val assistant = withoutPendingToolUse(turn.content)
                if (assistant.length() > 0) messages.put(message("assistant", assistant))
                trimHistory()

                when (turn.stopReason) {
                    // A server-side tool (web search) needs another round trip to continue.
                    "pause_turn" -> Unit

                    "refusal" -> {
                        emit(Event.Failed("I have to decline that one."))
                        return@flow
                    }

                    else -> {
                        emit(Event.Finished(spoken.toString().trim()))
                        return@flow
                    }
                }
            }
            emit(Event.Finished(spoken.toString().trim()))
        } finally {
            busy = false
            activeCall = null
        }
    }.flowOn(Dispatchers.IO)

    // ---------------------------------------------------------------- one streamed turn

    private class Turn(val content: JSONArray, val stopReason: String)

    private class ApiFailure(message: String) : Exception(message)

    private class BetaRejected : Exception()

    private suspend fun streamTurn(
        settings: Settings,
        allowBetas: Boolean,
        emit: suspend (Event) -> Unit,
    ): Turn {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return streamOnce(settings, allowBetas, emit)
            } catch (e: Retryable) {
                if (attempt >= MAX_ATTEMPTS) throw ApiFailure(e.userMessage)
                delay(e.delayMillis * attempt)
            }
        }
    }

    private class Retryable(val userMessage: String, val delayMillis: Long) : Exception()

    private suspend fun streamOnce(
        settings: Settings,
        allowBetas: Boolean,
        emit: suspend (Event) -> Unit,
    ): Turn {
        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("x-api-key", settings.apiKey)
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("content-type", "application/json")
            .apply { if (allowBetas) addHeader("anthropic-beta", FALLBACK_BETA) }
            .post(body(settings, allowBetas).toString().toRequestBody(JSON_MEDIA))
            .build()

        val call = http.newCall(request)
        activeCall = call

        val response = try {
            call.execute()
        } catch (e: IOException) {
            if (call.isCanceled()) throw ApiFailure("Stopped.")
            throw Retryable("I can't reach the network at the moment.", 1_500L)
        }

        response.use {
            if (!response.isSuccessful) {
                val payload = response.body?.string().orEmpty()
                throw translate(response.code, payload, allowBetas)
            }

            val blocks = sortedMapOf<Int, BlockBuilder>()
            var stopReason = "end_turn"
            var received = false
            val source = response.body?.source()
                ?: throw Retryable("The reply came back empty.", 1_000L)

            try {
                while (currentCoroutineContext().isActive) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty() || payload == "[DONE]") continue

                    val event = runCatching { JSONObject(payload) }.getOrNull() ?: continue
                    when (event.optString("type")) {
                        "content_block_start" -> {
                            val index = event.optInt("index")
                            val block = event.optJSONObject("content_block") ?: JSONObject()
                            blocks[index] = BlockBuilder(block)
                            if (block.optString("type") == "server_tool_use") {
                                emit(Event.Working("searching the web"))
                            }
                        }

                        "content_block_delta" -> {
                            val builder = blocks[event.optInt("index")] ?: continue
                            val delta = event.optJSONObject("delta") ?: continue
                            when (delta.optString("type")) {
                                "text_delta" -> {
                                    val text = delta.optString("text")
                                    builder.text.append(text)
                                    if (text.isNotEmpty()) {
                                        received = true
                                        emit(Event.Text(text))
                                    }
                                }
                                "input_json_delta" ->
                                    builder.json.append(delta.optString("partial_json"))
                                "thinking_delta" ->
                                    builder.thinking.append(delta.optString("thinking"))
                                "signature_delta" ->
                                    builder.signature = delta.optString("signature")
                            }
                        }

                        "message_delta" -> {
                            val stop = event.optJSONObject("delta")?.optString("stop_reason").orEmpty()
                            if (stop.isNotEmpty()) stopReason = stop
                        }

                        "error" -> {
                            val detail = event.optJSONObject("error")?.optString("message").orEmpty()
                            throw ApiFailure(detail.ifBlank { "The stream broke off." })
                        }
                    }
                }
            } catch (e: IOException) {
                // The connection died part way through. Retrying would repeat whatever has
                // already been spoken, so only retry when nothing was said yet.
                if (call.isCanceled()) throw ApiFailure("Stopped.")
                if (!received) throw Retryable("The connection dropped.", 1_500L)
                stopReason = "end_turn"
            }

            val content = JSONArray()
            blocks.values.forEach { content.put(it.build()) }
            return Turn(content, stopReason)
        }
    }

    private fun translate(code: Int, payload: String, sentBetas: Boolean): Exception {
        val detail = runCatching {
            JSONObject(payload).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")

        val mentionsBeta = detail.contains("beta", ignoreCase = true) ||
            detail.contains("fallback", ignoreCase = true)

        return when {
            code == 400 && sentBetas && mentionsBeta -> BetaRejected()
            code == 400 && !webSearchBlocked && detail.contains("web_search", ignoreCase = true) -> {
                webSearchBlocked = true
                Retryable("Retrying without web search.", 0L)
            }
            code == 400 -> ApiFailure("The request was rejected: ${detail.ifBlank { "bad request" }}")
            code == 401 || code == 403 ->
                ApiFailure("That API key was rejected. Check it in settings.")
            code == 404 ->
                ApiFailure("The model \"${prefs.current.model}\" is not available on this key.")
            code == 413 -> ApiFailure("That was too much to send in one go.")
            code == 429 -> Retryable("The API is rate limiting us.", 4_000L)
            code == 529 -> Retryable("The API is overloaded.", 3_000L)
            code >= 500 -> Retryable("The API had a server error.", 2_000L)
            else -> ApiFailure("The API returned $code. ${detail.take(160)}")
        }
    }

    // ---------------------------------------------------------------- request shaping

    private fun body(settings: Settings, allowBetas: Boolean): JSONObject {
        val system = JSONArray()
            .put(
                JSONObject()
                    .put("type", "text")
                    .put("text", Persona.stable(settings))
                    // Everything before this point is identical on every turn, so it can be
                    // served from the prompt cache.
                    .put("cache_control", JSONObject().put("type", "ephemeral")),
            )
            .put(
                JSONObject()
                    .put("type", "text")
                    .put("text", Persona.live(app, settings)),
            )

        val json = JSONObject()
            .put("model", settings.model)
            .put("max_tokens", 16_000)
            .put("stream", true)
            .put("system", system)
            .put(
                "tools",
                ToolCatalog.definitions(
                    settings.copy(webSearch = settings.webSearch && !webSearchBlocked),
                ),
            )
            .put("messages", messages)

        // Adaptive thinking and effort exist on the current Opus/Sonnet models but not on
        // the older Haiku line, which rejects both.
        if (!settings.model.startsWith("claude-haiku")) {
            json.put("thinking", JSONObject().put("type", "adaptive"))
            json.put("output_config", JSONObject().put("effort", settings.effort))
        }

        // If a safety classifier declines the turn, let the API rescue it on another model
        // rather than leaving the user with silence.
        if (allowBetas) json.put("fallbacks", "default")

        return json
    }

    private fun message(role: String, content: JSONArray): JSONObject =
        JSONObject().put("role", role).put("content", content)

    private fun textBlock(text: String): JSONObject =
        JSONObject().put("type", "text").put("text", text)

    /**
     * Keeps the conversation bounded without ever splitting a tool_use block from the
     * tool_result that answers it — the API rejects a history where those do not pair up.
     */
    private fun trimHistory() {
        while (messages.length() > MAX_MESSAGES) messages.remove(0)
        while (messages.length() > 0 && !isCleanStart(messages.optJSONObject(0))) {
            messages.remove(0)
        }
    }

    private fun isCleanStart(entry: JSONObject?): Boolean {
        if (entry == null) return false
        if (entry.optString("role") != "user") return false
        val first = entry.optJSONArray("content")?.optJSONObject(0) ?: return false
        return first.optString("type") != "tool_result"
    }

    private fun withoutPendingToolUse(content: JSONArray): JSONArray {
        val kept = JSONArray()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "tool_use") continue
            kept.put(block)
        }
        return kept
    }

    private fun describe(tool: String): String = when (tool) {
        "device_status" -> "checking the phone"
        "set_torch" -> "switching the torch"
        "set_volume", "set_ringer_mode" -> "adjusting the sound"
        "media_control" -> "controlling playback"
        "list_apps", "open_app" -> "reaching for an app"
        "open_url" -> "opening a link"
        "open_settings" -> "opening settings"
        "find_contact" -> "looking up a contact"
        "place_call" -> "getting the phone ready"
        "send_message" -> "writing a message"
        "set_alarm" -> "setting an alarm"
        "set_timer" -> "starting a timer"
        "create_calendar_event" -> "opening the calendar"
        "get_location" -> "locating the phone"
        "remember" -> "making a note"
        "forget" -> "forgetting that"
        "copy_to_clipboard" -> "copying that"
        else -> "working"
    }

    private class BlockBuilder(val start: JSONObject) {
        val text = StringBuilder()
        val json = StringBuilder()
        val thinking = StringBuilder()
        var signature: String? = null

        /**
         * Rebuilds the block exactly as the API sent it. Anything we do not recognise —
         * server tool results, for instance — is echoed back untouched, because the next
         * request has to carry the assistant turn verbatim.
         */
        fun build(): JSONObject = start.apply {
            when (optString("type")) {
                "text" -> put("text", text.toString())
                "thinking" -> {
                    put("thinking", thinking.toString())
                    signature?.let { put("signature", it) }
                }
                "tool_use", "server_tool_use", "mcp_tool_use" -> {
                    val raw = json.toString().ifBlank { "{}" }
                    put("input", runCatching { JSONObject(raw) }.getOrDefault(JSONObject()))
                }
            }
        }
    }

    private companion object {
        const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        const val API_VERSION = "2023-06-01"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        const val MAX_ROUNDS = 8
        const val MAX_ATTEMPTS = 3
        const val MAX_MESSAGES = 40
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
