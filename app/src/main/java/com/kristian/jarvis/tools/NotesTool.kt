package com.kristian.jarvis.tools

import android.content.Context
import org.json.JSONObject

/**
 * A tiny on-device notebook: titled notes in SharedPreferences.
 *
 * Deleting is marked destructive, so Claude has to pass confirmed=true - which
 * the persona only does after asking. That keeps "clear that note" from
 * quietly wiping the wrong one.
 */
class NotesTool(context: Context) : JarvisTool {

    private val prefs = context.applicationContext
        .getSharedPreferences("jarvis_notes", Context.MODE_PRIVATE)

    override val name = "notes"

    override val description =
        "Read and write the user's private notes on this phone. Actions: list (all titles), " +
            "read (one note), write (create or replace), append (add a line to an existing " +
            "note, creating it if needed), delete (remove one, requires confirmed=true). " +
            "Use this to remember things the user asks you to keep."

    override val inputSchema = objectSchema(
        "action" to stringProperty(
            "What to do",
            enum = listOf("list", "read", "write", "append", "delete")
        ),
        "title" to stringProperty("Note title; required for everything but list"),
        "body" to stringProperty("Text for write or append"),
        "confirmed" to booleanProperty("Must be true to delete; ask the user first"),
        required = listOf("action")
    )

    override val destructive = true

    override suspend fun execute(input: JSONObject): ToolOutcome {
        val action = input.optString("action").lowercase()
        val title = input.optString("title").trim()
        val body = input.optString("body")

        return when (action) {
            "list" -> {
                val titles = notes().keys().asSequence().toList().sorted()
                if (titles.isEmpty()) {
                    ToolOutcome("There are no notes yet.")
                } else {
                    ToolOutcome("Notes: ${titles.joinToString(", ")}")
                }
            }

            "read" -> {
                if (title.isEmpty()) return missingTitle()
                val note = notes().optString(title, "")
                if (note.isEmpty()) {
                    ToolOutcome("There's no note called \"$title\".", isError = true)
                } else {
                    ToolOutcome("$title: $note")
                }
            }

            "write" -> {
                if (title.isEmpty()) return missingTitle()
                save(title, body)
                ToolOutcome("Saved note \"$title\".", notice = "Note saved: $title")
            }

            "append" -> {
                if (title.isEmpty()) return missingTitle()
                val existing = notes().optString(title, "")
                val combined = if (existing.isEmpty()) body else "$existing\n$body"
                save(title, combined)
                ToolOutcome("Appended to \"$title\".", notice = "Note updated: $title")
            }

            "delete" -> {
                if (title.isEmpty()) return missingTitle()
                if (!input.optBoolean("confirmed", false)) {
                    ToolOutcome(
                        "Deleting is not confirmed. Ask the user to confirm, then call again " +
                            "with confirmed=true.",
                        isError = true
                    )
                } else {
                    val all = notes()
                    if (!all.has(title)) {
                        ToolOutcome("There's no note called \"$title\".", isError = true)
                    } else {
                        all.remove(title)
                        persist(all)
                        ToolOutcome("Deleted note \"$title\".", notice = "Note deleted: $title")
                    }
                }
            }

            else -> ToolOutcome("Unknown action \"$action\".", isError = true)
        }
    }

    private fun missingTitle() = ToolOutcome("A note title is required.", isError = true)

    private fun notes(): JSONObject =
        runCatching { JSONObject(prefs.getString(KEY_NOTES, "{}") ?: "{}") }
            .getOrElse { JSONObject() }

    private fun save(title: String, body: String) {
        persist(notes().put(title, body))
    }

    private fun persist(all: JSONObject) {
        prefs.edit().putString(KEY_NOTES, all.toString()).apply()
    }

    /** Exposed for a future notes screen. */
    fun titles(): List<String> = notes().keys().asSequence().toList().sorted()

    private companion object {
        const val KEY_NOTES = "notes_json"
    }
}
