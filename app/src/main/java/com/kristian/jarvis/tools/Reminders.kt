package com.kristian.jarvis.tools

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kristian.jarvis.MainActivity
import com.kristian.jarvis.R
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Notification plumbing shared by reminders and (later) the always-on service. */
object JarvisNotifications {

    const val CHANNEL_REMINDERS = "jarvis_reminders"
    const val CHANNEL_SERVICE = "jarvis_service"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                "Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Reminders you asked JARVIS to set" }
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                "Always listening",
                // Low: the ongoing notification is a requirement, not news.
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shown while JARVIS is listening in the background" }
        )
    }

    fun buildReminder(context: Context, text: String): Notification {
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("JARVIS")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
    }
}

/** Fires when a scheduled reminder comes due. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val id = intent.getIntExtra(EXTRA_ID, text.hashCode())
        JarvisNotifications.ensureChannels(context)
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(id, JarvisNotifications.buildReminder(context, text))
        }
    }

    companion object {
        const val EXTRA_TEXT = "reminder_text"
        const val EXTRA_ID = "reminder_id"
    }
}

/** Schedules a local notification. No network, no calendar - just this phone. */
class ReminderTool(context: Context) : JarvisTool {

    private val appContext = context.applicationContext

    override val name = "set_reminder"

    override val description =
        "Set a reminder that pops up as a notification on the user's phone. Give either " +
            "in_minutes (relative) or at (an absolute 24-hour time like \"18:30\", which means " +
            "today if still ahead, otherwise tomorrow). This only notifies on this phone; it " +
            "does not add anything to a calendar."

    override val inputSchema = objectSchema(
        "text" to stringProperty("What the reminder should say"),
        "in_minutes" to integerProperty("Minutes from now, e.g. 20"),
        "at" to stringProperty("Absolute local time in 24-hour HH:mm form, e.g. 18:30"),
        required = listOf("text")
    )

    override suspend fun execute(input: JSONObject): ToolOutcome {
        val text = input.optString("text").trim()
        if (text.isEmpty()) return ToolOutcome("The reminder needs some text.", isError = true)

        val triggerAt = when {
            input.has("in_minutes") && input.optInt("in_minutes", -1) > 0 ->
                System.currentTimeMillis() + input.optInt("in_minutes") * 60_000L

            input.optString("at").isNotBlank() -> parseClockTime(input.optString("at"))
                ?: return ToolOutcome(
                    "I couldn't read \"${input.optString("at")}\" as a time. Use HH:mm.",
                    isError = true
                )

            else -> return ToolOutcome(
                "Give either in_minutes or an absolute time in at.",
                isError = true
            )
        }

        val alarms = appContext.getSystemService(AlarmManager::class.java)
            ?: return ToolOutcome("This phone has no alarm service available.", isError = true)

        JarvisNotifications.ensureChannels(appContext)

        val id = (triggerAt / 1000L).toInt() xor text.hashCode()
        val pending = PendingIntent.getBroadcast(
            appContext,
            id,
            Intent(appContext, ReminderReceiver::class.java)
                .putExtra(ReminderReceiver.EXTRA_TEXT, text)
                .putExtra(ReminderReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // setAndAllowWhileIdle needs no special permission and still fires in
        // Doze. Exact alarms would need SCHEDULE_EXACT_ALARM, which is a bigger
        // ask than a reminder warrants; expect it within a few minutes.
        runCatching {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }.onFailure {
            return ToolOutcome("The reminder could not be scheduled.", isError = true)
        }

        val when24 = SimpleDateFormat("HH:mm", Locale.UK).format(triggerAt)
        return ToolOutcome(
            text = "Reminder set for $when24: \"$text\".",
            notice = "Reminder set for $when24"
        )
    }

    /** "18:30" to the next moment that clock time occurs. */
    private fun parseClockTime(raw: String): Long? {
        val match = Regex("^\\s*(\\d{1,2})[:.](\\d{2})\\s*$").find(raw) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null

        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
    }
}
