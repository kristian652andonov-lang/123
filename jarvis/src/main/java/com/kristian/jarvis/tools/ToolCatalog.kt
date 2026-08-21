package com.kristian.jarvis.tools

import com.kristian.jarvis.core.Settings
import org.json.JSONArray
import org.json.JSONObject

/**
 * The tool definitions sent to the Messages API.
 *
 * Order matters: this array is the very front of the cached prompt prefix, so it must be
 * built deterministically. Nothing in here may depend on the clock or on anything else
 * that varies between turns.
 */
object ToolCatalog {

    const val WEB_SEARCH = "web_search"

    fun definitions(settings: Settings): JSONArray {
        val tools = JSONArray()

        tools.put(
            tool(
                "device_status",
                """
                Read the phone's own state: battery level and charging, network, signal,
                volume levels, ringer mode, do-not-disturb, free storage, power saving,
                and the current date and time. Call this for any question about the phone
                itself. It reports nothing about the outside world — no weather, no news.
                """,
                obj(),
                required = emptyList(),
            ),
        )

        tools.put(
            tool(
                "set_torch",
                "Turn the phone's flashlight (torch) on or off.",
                obj("on" to bool("True to switch the torch on, false to switch it off.")),
                required = listOf("on"),
            ),
        )

        tools.put(
            tool(
                "set_volume",
                "Set the volume of one audio stream as a percentage of its maximum.",
                obj(
                    "stream" to str(
                        "Which stream to change.",
                        enum = listOf("media", "ring", "alarm", "notification", "call"),
                    ),
                    "percent" to int("Target volume, 0 to 100."),
                ),
                required = listOf("stream", "percent"),
            ),
        )

        tools.put(
            tool(
                "set_ringer_mode",
                "Switch the phone between normal, vibrate and silent.",
                obj("mode" to str("The ringer mode.", enum = listOf("normal", "vibrate", "silent"))),
                required = listOf("mode"),
            ),
        )

        tools.put(
            tool(
                "media_control",
                "Control whatever is currently playing audio or video on the phone.",
                obj(
                    "action" to str(
                        "The transport command to send.",
                        enum = listOf("play_pause", "play", "pause", "next", "previous", "stop"),
                    ),
                ),
                required = listOf("action"),
            ),
        )

        tools.put(
            tool(
                "list_apps",
                """
                List the apps installed on the phone that can be opened. Use this when you
                are unsure of an app's exact name before calling open_app.
                """,
                obj("query" to str("Optional substring to filter app names by.")),
                required = emptyList(),
            ),
        )

        tools.put(
            tool(
                "open_app",
                """
                Launch an installed app by name. Matching is fuzzy, so the user's everyday
                name for the app ("maps", "whatsapp", "spotify") is usually enough.
                """,
                obj("app" to str("The app's name, or its package name if you know it.")),
                required = listOf("app"),
            ),
        )

        tools.put(
            tool(
                "open_url",
                "Open a web address, or any other URI the phone can handle, in the right app.",
                obj("url" to str("The full URL, including https://")),
                required = listOf("url"),
            ),
        )

        tools.put(
            tool(
                "open_settings",
                "Open a page of the phone's system settings for the user.",
                obj(
                    "section" to str(
                        "Which settings page to open.",
                        enum = listOf(
                            "wifi", "bluetooth", "mobile_data", "airplane_mode", "sound",
                            "display", "battery", "storage", "location", "apps", "security",
                            "accessibility", "date_time", "nfc", "dnd_access", "main",
                        ),
                    ),
                ),
                required = listOf("section"),
            ),
        )

        tools.put(
            tool(
                "find_contact",
                "Look someone up in the phone's contacts and get their phone numbers.",
                obj("name" to str("All or part of the contact's name.")),
                required = listOf("name"),
            ),
        )

        tools.put(
            tool(
                "place_call",
                """
                Call a phone number. If calling is switched off in Jarvis's settings, or the
                permission was declined, this opens the dialer with the number filled in
                instead of dialling — the result will say which happened, so report it
                accurately.
                """,
                obj(
                    "number" to str("The number to call, in whatever format the contact uses."),
                    "who" to str("Optional name of the person, used only when confirming."),
                ),
                required = listOf("number"),
            ),
        )

        tools.put(
            tool(
                "send_message",
                """
                Send a text message. If sending is switched off in Jarvis's settings, or the
                permission was declined, this opens the messaging app with the message ready
                to send instead — the result will say which happened, so report it accurately.
                """,
                obj(
                    "number" to str("The recipient's phone number."),
                    "text" to str("The message body."),
                ),
                required = listOf("number", "text"),
            ),
        )

        tools.put(
            tool(
                "set_alarm",
                "Set an alarm on the phone's clock for a given time of day.",
                obj(
                    "hour" to int("Hour on a 24-hour clock, 0 to 23."),
                    "minute" to int("Minute, 0 to 59."),
                    "label" to str("Optional label for the alarm."),
                ),
                required = listOf("hour", "minute"),
            ),
        )

        tools.put(
            tool(
                "set_timer",
                "Start a countdown timer on the phone's clock.",
                obj(
                    "seconds" to int("How long the timer should run, in seconds."),
                    "label" to str("Optional label for the timer."),
                ),
                required = listOf("seconds"),
            ),
        )

        tools.put(
            tool(
                "create_calendar_event",
                "Put an event in the phone's calendar.",
                obj(
                    "title" to str("The event title."),
                    "start" to str("Start time as local ISO-8601, e.g. 2026-08-21T18:30."),
                    "end" to str("Optional end time in the same format. Defaults to an hour after the start."),
                    "location" to str("Optional location."),
                    "notes" to str("Optional description."),
                ),
                required = listOf("title", "start"),
            ),
        )

        tools.put(
            tool(
                "get_location",
                """
                Get the phone's last known position, with a street address where one can be
                resolved. Use it before searching for anything local ("nearby", "near me",
                "the weather here").
                """,
                obj(),
                required = emptyList(),
            ),
        )

        tools.put(
            tool(
                "remember",
                """
                Store a fact about the user so it survives restarts and is available in every
                future conversation. Use short, stable keys such as "home address" or
                "coffee order". Storing an existing key overwrites it.
                """,
                obj(
                    "key" to str("Short name for the fact."),
                    "value" to str("The fact itself."),
                ),
                required = listOf("key", "value"),
            ),
        )

        tools.put(
            tool(
                "forget",
                "Delete a stored fact by its key.",
                obj("key" to str("The key of the fact to delete.")),
                required = listOf("key"),
            ),
        )

        tools.put(
            tool(
                "copy_to_clipboard",
                "Put text on the phone's clipboard so the user can paste it.",
                obj("text" to str("The text to copy.")),
                required = listOf("text"),
            ),
        )

        if (settings.webSearch) {
            tools.put(
                JSONObject()
                    .put("type", "web_search_20260209")
                    .put("name", WEB_SEARCH)
                    .put("max_uses", 5),
            )
        }

        return tools
    }

    /** Names handled on the device. Anything else in a tool_use block runs server-side. */
    fun isClientTool(name: String): Boolean = name != WEB_SEARCH

    private fun tool(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String>,
    ): JSONObject = JSONObject()
        .put("name", name)
        .put("description", description.trimIndent().trim())
        .put(
            "input_schema",
            JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", JSONArray(required)),
        )

    private fun obj(vararg fields: Pair<String, JSONObject>): JSONObject =
        JSONObject().apply { fields.forEach { (key, value) -> put(key, value) } }

    private fun str(description: String, enum: List<String>? = null): JSONObject =
        JSONObject().put("type", "string").put("description", description).also {
            if (enum != null) it.put("enum", JSONArray(enum))
        }

    private fun int(description: String): JSONObject =
        JSONObject().put("type", "integer").put("description", description)

    private fun bool(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)
}
