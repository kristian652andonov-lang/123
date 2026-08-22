package com.kristian.jarvis.llm

import android.util.Log
import com.kristian.jarvis.claude.SecureKeyStore
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

/**
 * Google's Gemini API, which has a real free tier - a Google account and a key
 * from aistudio.google.com, no card, no credits.
 *
 * Note for anyone reading this later: on the free tier Google may use prompts
 * and responses to improve their products. That is the trade for free; the
 * Anthropic path does not do this. The choice is surfaced in settings.
 *
 * Conversation history stays in the Anthropic format everywhere else in the
 * app, so everything here is translation on the way out and back.
 */
class GeminiClient(
    private val keys: SecureKeyStore,
    private val prefs: JarvisPrefs
) : LlmClient {

    override val displayName: String = "Gemini"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun stream(
        messages: JSONArray,
        systemPrompt: String,
        tools: JSONArray?,
        narrate: Boolean,
        onEvent: (LlmEvent) -> Unit
    ): TurnResult = withContext(Dispatchers.IO) {
        val apiKey = keys.geminiApiKey
            ?: return@withContext TurnResult.Failed(
                "No Gemini API key is stored yet.",
                retryable = false
            )

        val body = buildRequestBody(messages, systemPrompt, tools, narrate).toString()
        streamWithRetries(apiKey, body, onEvent)
    }

    /**
     * Sends the request, adjusting and retrying for the two things that can be
     * wrong in a way we can fix ourselves: the auth style and a retired model.
     */
    private fun streamWithRetries(
        apiKey: String,
        body: String,
        onEvent: (LlmEvent) -> Unit
    ): TurnResult {
        var model = prefs.geminiModel
        var useBearer = false
        var triedOtherAuth = false
        var triedSuggestedModel = false

        while (true) {
            val last = attempt(apiKey, body, model, useBearer, onEvent)

            // Google has shipped more than one key format and it isn't obvious
            // from a key which header it wants, so try the standard one and
            // fall back to a bearer token rather than guessing.
            if (last.retryWithOtherAuth && !triedOtherAuth) {
                useBearer = true
                triedOtherAuth = true
                continue
            }

            // Models get retired for new accounts, and the error names the
            // replacement - so take it, remember it, and carry on instead of
            // making the user go and edit a setting.
            val suggested = last.suggestedModel
            if (suggested != null && suggested != model && !triedSuggestedModel) {
                Log.i(TAG, "Model $model retired; switching to $suggested")
                model = suggested
                prefs.geminiModel = suggested
                triedSuggestedModel = true
                continue
            }

            return last.result
        }
    }

    /** One HTTP attempt, and what (if anything) is worth changing before a retry. */
    private class Attempt(
        val result: TurnResult,
        val retryWithOtherAuth: Boolean = false,
        /** Replacement model id, when the API told us this one is retired. */
        val suggestedModel: String? = null
    )

    private fun attempt(
        apiKey: String,
        body: String,
        model: String,
        useBearer: Boolean,
        onEvent: (LlmEvent) -> Unit
    ): Attempt {
        val builder = Request.Builder()
            .url("$ENDPOINT_BASE/$model:streamGenerateContent?alt=sse")
            .addHeader("content-type", "application/json")
        if (useBearer) {
            builder.addHeader("Authorization", "Bearer $apiKey")
        } else {
            builder.addHeader("x-goog-api-key", apiKey)
        }
        val request = builder.post(body.toRequestBody(JSON_MEDIA_TYPE)).build()

        return try {
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val source = response.body?.source()
                    if (source == null) {
                        Attempt(TurnResult.Failed("Empty response from Gemini.", retryable = true))
                    } else {
                        // Streaming has begun - never retry past this point.
                        Attempt(parseStream(readLine = { source.readUtf8Line() }, onEvent = onEvent))
                    }
                } else {
                    val raw = response.body?.string()
                    Attempt(
                        result = describeHttpFailure(response.code, raw),
                        retryWithOtherAuth = !useBearer && isAuthRejection(response.code, raw),
                        suggestedModel = suggestedModelFrom(raw, model)
                    )
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Request failed", e)
            Attempt(
                TurnResult.Failed(
                    "I couldn't reach Gemini - the connection failed. Check your network, sir.",
                    retryable = true
                )
            )
        }
    }

    /**
     * Pulls a replacement model id out of a retirement error, e.g. "This model
     * models/gemini-2.5-flash is no longer available… use models/gemini-3.6-flash".
     * The last id mentioned is the replacement; the first is the dead one.
     */
    private fun suggestedModelFrom(rawBody: String?, currentModel: String): String? {
        val body = rawBody ?: return null
        if (!body.contains("no longer available", ignoreCase = true) &&
            !body.contains("is not found", ignoreCase = true) &&
            !body.contains("not supported", ignoreCase = true)
        ) {
            return null
        }
        val mentioned = Regex("models/([A-Za-z0-9._-]+)")
            .findAll(body)
            .map { it.groupValues[1] }
            .toList()
        return mentioned.lastOrNull()?.takeIf { it != currentModel }
    }

    /** Google reports a bad or wrongly-presented key as 400/401/403, not just 401. */
    private fun isAuthRejection(code: Int, rawBody: String?): Boolean {
        if (code == 401 || code == 403) return true
        if (code != 400) return false
        val body = rawBody.orEmpty()
        return body.contains("API key", ignoreCase = true) ||
            body.contains("API_KEY_INVALID", ignoreCase = true) ||
            body.contains("authentication", ignoreCase = true)
    }

    private fun buildRequestBody(
        messages: JSONArray,
        systemPrompt: String,
        tools: JSONArray?,
        narrate: Boolean
    ): JSONObject = JSONObject().apply {
        put(
            "systemInstruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
        )
        put("contents", toGeminiContents(messages))

        val toolBlock = JSONArray()
        tools?.let { declarations ->
            val functions = JSONArray()
            for (i in 0 until declarations.length()) {
                val tool = declarations.optJSONObject(i) ?: continue
                // Server-side tool entries from the Anthropic path have a
                // "type" and no schema; they mean nothing here.
                if (!tool.has("input_schema")) continue
                functions.put(
                    JSONObject()
                        .put("name", tool.optString("name"))
                        .put("description", tool.optString("description"))
                        .put("parameters", toGeminiSchema(tool.optJSONObject("input_schema")))
                )
            }
            if (functions.length() > 0) {
                toolBlock.put(JSONObject().put("functionDeclarations", functions))
            }
        }
        if (prefs.webSearchEnabled) {
            // Google's own grounding, included in the free tier's quota.
            toolBlock.put(JSONObject().put("google_search", JSONObject()))
        }
        if (toolBlock.length() > 0) put("tools", toolBlock)

        put(
            "generationConfig",
            JSONObject().put(
                "maxOutputTokens",
                if (narrate) maxOf(prefs.maxTokens, 8192) else prefs.maxTokens
            )
        )
    }

    /**
     * Anthropic messages to Gemini contents.
     *
     * Two wrinkles: assistant is called "model", and tool results have to go in
     * their own content with role "function" rather than riding along in a user
     * turn the way Anthropic does it.
     */
    private fun toGeminiContents(messages: JSONArray): JSONArray {
        val contents = JSONArray()
        // Gemini identifies a tool result by name, Anthropic by id - so the
        // ids have to be resolved back to names as we go.
        val toolNamesById = mutableMapOf<String, String>()

        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: continue
            val role = message.optString("role")
            val blocks = message.optJSONArray("content") ?: continue

            val parts = JSONArray()
            val functionResponses = JSONArray()

            for (j in 0 until blocks.length()) {
                val block = blocks.optJSONObject(j) ?: continue
                when (block.optString("type")) {
                    "text" -> block.optString("text").takeIf { it.isNotEmpty() }?.let {
                        parts.put(JSONObject().put("text", it))
                    }

                    "tool_use" -> {
                        val name = block.optString("name")
                        toolNamesById[block.optString("id")] = name
                        parts.put(
                            JSONObject().put(
                                "functionCall",
                                JSONObject()
                                    .put("name", name)
                                    .put("args", block.optJSONObject("input") ?: JSONObject())
                            )
                        )
                    }

                    "tool_result" -> {
                        val name = toolNamesById[block.optString("tool_use_id")] ?: continue
                        functionResponses.put(
                            JSONObject().put(
                                "functionResponse",
                                JSONObject()
                                    .put("name", name)
                                    .put(
                                        "response",
                                        JSONObject()
                                            .put("name", name)
                                            .put(
                                                "content",
                                                JSONObject().put(
                                                    "result",
                                                    block.optString("content")
                                                )
                                            )
                                    )
                            )
                        )
                    }
                    // Thinking blocks are Anthropic-specific; Gemini rejects
                    // parts it doesn't know, so they are dropped.
                }
            }

            if (parts.length() > 0) {
                contents.put(
                    JSONObject()
                        .put("role", if (role == "assistant") "model" else "user")
                        .put("parts", parts)
                )
            }
            if (functionResponses.length() > 0) {
                contents.put(
                    JSONObject().put("role", "function").put("parts", functionResponses)
                )
            }
        }
        return contents
    }

    /**
     * JSON Schema to Gemini's OpenAPI subset. The visible difference is that
     * type names are uppercase; unsupported keywords are dropped rather than
     * risking a rejected request.
     */
    private fun toGeminiSchema(schema: JSONObject?): JSONObject {
        if (schema == null) return JSONObject().put("type", "OBJECT")
        val out = JSONObject()
        schema.optString("type").takeIf { it.isNotEmpty() }?.let {
            out.put("type", it.uppercase())
        }
        schema.optString("description").takeIf { it.isNotEmpty() }?.let {
            out.put("description", it)
        }
        schema.optJSONArray("enum")?.let { out.put("enum", it) }
        schema.optJSONArray("required")?.let { out.put("required", it) }
        schema.optJSONObject("properties")?.let { properties ->
            val converted = JSONObject()
            properties.keys().forEach { key ->
                converted.put(key, toGeminiSchema(properties.optJSONObject(key)))
            }
            out.put("properties", converted)
        }
        schema.optJSONObject("items")?.let { out.put("items", toGeminiSchema(it)) }
        return out
    }

    /** Reads the SSE stream and rebuilds the turn in the canonical format. */
    private fun parseStream(
        readLine: () -> String?,
        onEvent: (LlmEvent) -> Unit
    ): TurnResult {
        val text = StringBuilder()
        val toolUses = mutableListOf<LlmEvent.ToolUse>()
        var finishReason: String? = null
        var blockedReason: String? = null
        var toolCounter = 0

        while (true) {
            val line = readLine() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) continue

            val json = runCatching { JSONObject(payload) }.getOrNull() ?: continue

            json.optJSONObject("promptFeedback")?.optString("blockReason")
                ?.takeIf { it.isNotEmpty() }
                ?.let { blockedReason = it }

            val candidates = json.optJSONArray("candidates") ?: continue
            val candidate = candidates.optJSONObject(0) ?: continue
            candidate.optString("finishReason").takeIf { it.isNotEmpty() }?.let {
                finishReason = it
            }

            val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: continue
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue

                part.optString("text").takeIf { it.isNotEmpty() }?.let { delta ->
                    // A "thought" part is reasoning, not the answer itself.
                    if (part.optBoolean("thought", false)) {
                        onEvent(LlmEvent.ThinkingDelta(delta))
                    } else {
                        text.append(delta)
                        onEvent(LlmEvent.TextDelta(delta))
                    }
                }

                part.optJSONObject("functionCall")?.let { call ->
                    val use = LlmEvent.ToolUse(
                        id = "gemini-tool-${toolCounter++}",
                        name = call.optString("name"),
                        input = call.optJSONObject("args") ?: JSONObject()
                    )
                    toolUses += use
                    onEvent(use)
                }
            }
        }

        blockedReason?.let {
            return TurnResult.Completed(
                stopReason = "refusal",
                content = JSONArray(),
                toolUses = emptyList(),
                refusalExplanation = "Gemini blocked that request ($it)."
            )
        }

        val content = JSONArray()
        if (text.isNotEmpty()) {
            content.put(JSONObject().put("type", "text").put("text", text.toString()))
        }
        toolUses.forEach { use ->
            content.put(
                JSONObject()
                    .put("type", "tool_use")
                    .put("id", use.id)
                    .put("name", use.name)
                    .put("input", use.input)
            )
        }

        val stopReason = when {
            toolUses.isNotEmpty() -> "tool_use"
            finishReason == "MAX_TOKENS" -> "max_tokens"
            finishReason == "SAFETY" || finishReason == "PROHIBITED_CONTENT" -> "refusal"
            else -> "end_turn"
        }

        return TurnResult.Completed(
            stopReason = stopReason,
            content = content,
            toolUses = toolUses,
            refusalExplanation = if (stopReason == "refusal") {
                "Gemini declined to answer that one."
            } else {
                null
            }
        )
    }

    private fun describeHttpFailure(code: Int, rawBody: String?): TurnResult.Failed {
        val apiMessage = rawBody
            ?.let {
                runCatching { JSONObject(it).optJSONObject("error")?.optString("message") }
                    .getOrNull()
            }
            ?.takeIf { it.isNotBlank() }

        return when (code) {
            400 -> TurnResult.Failed(
                // An invalid key comes back as a 400 here, not a 401.
                if (apiMessage?.contains("API key", ignoreCase = true) == true) {
                    "That Gemini key was rejected. Check it in settings, sir."
                } else {
                    apiMessage ?: "Gemini rejected the request."
                },
                retryable = false
            )

            401, 403 -> TurnResult.Failed(
                "That Gemini key was rejected, or the API isn't enabled for it.",
                retryable = false
            )

            404 -> TurnResult.Failed(
                apiMessage ?: "That Gemini model doesn't exist for this key.",
                retryable = false
            )

            429 -> TurnResult.Failed(
                "That's the free tier's rate limit reached. It resets - give it a minute, " +
                    "or switch to a lighter model in settings.",
                retryable = true
            )

            in 500..599 -> TurnResult.Failed(
                "Google's API is having trouble at its end. Worth retrying.",
                retryable = true
            )

            else -> TurnResult.Failed(
                apiMessage ?: "The request was rejected (HTTP $code).",
                retryable = false
            )
        }
    }

    companion object {
        private const val TAG = "GeminiClient"
        private const val ENDPOINT_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
