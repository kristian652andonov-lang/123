package com.kristian.jarvis.tools

import org.json.JSONObject

/** Result of running a tool, as JARVIS will report it back to Claude. */
data class ToolOutcome(
    val text: String,
    val isError: Boolean = false,
    /** Short phrase for the transcript, e.g. "Opening the BBC". */
    val notice: String? = null
)

/**
 * One thing JARVIS can actually do.
 *
 * Adding a tool is: implement this, register it in [ToolRegistry]. Nothing
 * else in the app needs to change - the request builder and the loop pick it
 * up from the registry.
 */
interface JarvisTool {

    /** Must match ^[a-zA-Z0-9_-]{1,64}$ - this is what Claude calls. */
    val name: String

    /**
     * Written for Claude, not for the user. Say when to use it and what it
     * does; vague descriptions are the usual cause of a tool going unused.
     */
    val description: String

    /** JSON Schema for the arguments. */
    val inputSchema: JSONObject

    /**
     * True if this can destroy or send something. Such tools must refuse
     * unless the call carries explicit confirmation, so an ambiguous request
     * can never quietly do damage.
     */
    val destructive: Boolean get() = false

    suspend fun execute(input: JSONObject): ToolOutcome
}

/** Small helper for building schemas without a pile of boilerplate. */
fun objectSchema(
    vararg properties: Pair<String, JSONObject>,
    required: List<String> = emptyList()
): JSONObject = JSONObject().apply {
    put("type", "object")
    put(
        "properties",
        JSONObject().apply { properties.forEach { (key, value) -> put(key, value) } }
    )
    if (required.isNotEmpty()) {
        put("required", org.json.JSONArray().apply { required.forEach { put(it) } })
    }
}

fun stringProperty(description: String, enum: List<String>? = null): JSONObject =
    JSONObject().apply {
        put("type", "string")
        put("description", description)
        enum?.let { values ->
            put("enum", org.json.JSONArray().apply { values.forEach { put(it) } })
        }
    }

fun integerProperty(description: String): JSONObject = JSONObject().apply {
    put("type", "integer")
    put("description", description)
}

fun booleanProperty(description: String): JSONObject = JSONObject().apply {
    put("type", "boolean")
    put("description", description)
}
