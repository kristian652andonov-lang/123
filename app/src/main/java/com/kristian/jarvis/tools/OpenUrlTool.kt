package com.kristian.jarvis.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import org.json.JSONObject

/** Opens a web page on the phone. */
class OpenUrlTool(context: Context) : JarvisTool {

    private val appContext = context.applicationContext

    override val name = "open_url"

    override val description =
        "Open a web page in the user's browser on their phone. Use this when the user asks " +
            "to see, open, pull up or visit a site. The page opens on their screen; you do " +
            "not get to read its contents."

    override val inputSchema = objectSchema(
        "url" to stringProperty("Full URL including https://"),
        "reason" to stringProperty("One short phrase describing why, e.g. 'the weather forecast'"),
        required = listOf("url")
    )

    override suspend fun execute(input: JSONObject): ToolOutcome {
        val raw = input.optString("url").trim()
        if (raw.isEmpty()) return ToolOutcome("No URL was given.", isError = true)

        val uri: Uri = runCatching { raw.toUri() }.getOrNull()
            ?: return ToolOutcome("That URL could not be parsed.", isError = true)

        // Only web schemes: an intent: or file: URL from a model is not something
        // to fire blindly at the system.
        if (uri.scheme?.lowercase() !in setOf("http", "https")) {
            return ToolOutcome(
                "Only http and https links can be opened. Refused: $raw",
                isError = true
            )
        }

        return runCatching {
            appContext.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            ToolOutcome(
                text = "Opened $raw in the browser.",
                notice = "Opening ${uri.host ?: raw}"
            )
        }.getOrElse {
            ToolOutcome("No app on this phone could open that link.", isError = true)
        }
    }
}
