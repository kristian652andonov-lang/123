package com.kristian.jarvis.tools

import android.content.Context
import android.util.Log
import com.kristian.jarvis.settings.JarvisPrefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every tool Claude may call, and the dispatch for running them.
 *
 * Local tools run on the phone. Web search is different: it runs on
 * Anthropic's side, so it is only declared here - results come back inside the
 * response and never reach [execute].
 */
class ToolRegistry(
    context: Context,
    private val prefs: JarvisPrefs
) {

    private val tools: List<JarvisTool> = listOf(
        OpenUrlTool(context),
        ReminderTool(context),
        NotesTool(context)
    )

    private val byName = tools.associateBy { it.name }

    /** The tools block for a request, or null when everything is switched off. */
    fun definitions(): JSONArray? {
        val array = JSONArray()
        tools.forEach { tool ->
            array.put(
                JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.description)
                    .put("input_schema", tool.inputSchema)
            )
        }
        if (prefs.webSearchEnabled) {
            // Server-side: Anthropic runs the search and bills it to the same
            // key, so there is no second provider or second key to manage.
            array.put(
                JSONObject()
                    .put("type", WEB_SEARCH_TYPE)
                    .put("name", "web_search")
                    .put("max_uses", 4)
            )
        }
        return array.takeIf { it.length() > 0 }
    }

    /** Runs one tool call and returns the tool_result block to send back. */
    suspend fun execute(id: String, name: String, input: JSONObject): Pair<JSONObject, String?> {
        val tool = byName[name]
        if (tool == null) {
            Log.w(TAG, "Claude asked for unknown tool $name")
            return result(id, "There is no tool called \"$name\" on this device.", true) to null
        }

        val outcome = runCatching { tool.execute(input) }
            .getOrElse { error ->
                Log.w(TAG, "Tool $name failed", error)
                ToolOutcome(
                    "The tool failed: ${error.message ?: error::class.java.simpleName}",
                    isError = true
                )
            }

        return result(id, outcome.text, outcome.isError) to outcome.notice
    }

    private fun result(id: String, text: String, isError: Boolean): JSONObject =
        JSONObject()
            .put("type", "tool_result")
            .put("tool_use_id", id)
            .put("content", text)
            .apply { if (isError) put("is_error", true) }

    companion object {
        private const val TAG = "ToolRegistry"

        /** Current server-side web search tool version. */
        private const val WEB_SEARCH_TYPE = "web_search_20260209"
    }
}
