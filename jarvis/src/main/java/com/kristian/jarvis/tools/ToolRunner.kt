package com.kristian.jarvis.tools

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings as AndroidSettings
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.kristian.jarvis.core.Prefs
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class ToolResult(val text: String, val isError: Boolean = false)

/**
 * Executes the device-side tools. Every failure is turned into an ordinary result string
 * rather than an exception: Claude is better placed than we are to explain to the user
 * what went wrong, and a thrown exception would abandon the whole turn.
 */
class ToolRunner(context: Context, private val prefs: Prefs) {

    private val app = context.applicationContext

    suspend fun run(name: String, input: JSONObject): ToolResult = withContext(Dispatchers.Default) {
        try {
            when (name) {
                "device_status" -> deviceStatus()
                "set_torch" -> setTorch(input.optBoolean("on", true))
                "set_volume" -> setVolume(input.optString("stream"), input.optInt("percent", -1))
                "set_ringer_mode" -> setRinger(input.optString("mode"))
                "media_control" -> mediaControl(input.optString("action"))
                "list_apps" -> listApps(input.optString("query"))
                "open_app" -> openApp(input.optString("app"))
                "open_url" -> openUrl(input.optString("url"))
                "open_settings" -> openSettings(input.optString("section"))
                "find_contact" -> findContact(input.optString("name"))
                "place_call" -> placeCall(input.optString("number"), input.optString("who"))
                "send_message" -> sendMessage(input.optString("number"), input.optString("text"))
                "set_alarm" -> setAlarm(
                    input.optInt("hour", -1),
                    input.optInt("minute", 0),
                    input.optString("label"),
                )
                "set_timer" -> setTimer(input.optInt("seconds", -1), input.optString("label"))
                "create_calendar_event" -> createEvent(input)
                "get_location" -> getLocation()
                "remember" -> rememberFact(input.optString("key"), input.optString("value"))
                "forget" -> forgetFact(input.optString("key"))
                "copy_to_clipboard" -> copyToClipboard(input.optString("text"))
                else -> ToolResult("There is no tool called \"$name\" on this device.", isError = true)
            }
        } catch (t: Throwable) {
            ToolResult("The $name tool failed: ${t.message ?: t.javaClass.simpleName}", isError = true)
        }
    }

    // ---------------------------------------------------------------- device state

    private fun deviceStatus(): ToolResult {
        val lines = mutableListOf<String>()

        val battery = app.getSystemService(BatteryManager::class.java)
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = battery?.isCharging == true
        lines += "Battery: $level%${if (charging) ", charging" else ", not charging"}"

        val power = app.getSystemService(PowerManager::class.java)
        if (power?.isPowerSaveMode == true) lines += "Battery saver: on"

        lines += "Network: " + describeNetwork()

        val audio = app.getSystemService(AudioManager::class.java)
        if (audio != null) {
            fun pct(stream: Int): String {
                val max = audio.getStreamMaxVolume(stream).coerceAtLeast(1)
                return "${audio.getStreamVolume(stream) * 100 / max}%"
            }
            lines += "Volume — media ${pct(AudioManager.STREAM_MUSIC)}, " +
                "ring ${pct(AudioManager.STREAM_RING)}, " +
                "alarm ${pct(AudioManager.STREAM_ALARM)}"
            lines += "Ringer: " + when (audio.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "silent"
                AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                else -> "normal"
            }
            lines += "Music playing: " + if (audio.isMusicActive) "yes" else "no"
        }

        val notifications = app.getSystemService(NotificationManager::class.java)
        val filter = notifications?.currentInterruptionFilter
        if (filter != null && filter != NotificationManager.INTERRUPTION_FILTER_ALL) {
            lines += "Do not disturb: on"
        }

        val stat = StatFs(Environment.getDataDirectory().path)
        val freeGb = stat.availableBytes / 1_000_000_000.0
        lines += "Free storage: " + String.format(Locale.UK, "%.1f GB", freeGb)

        lines += "Torch: " + if (torchOn) "on" else "off"

        val now = Date()
        lines += "Time: " + SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.UK).format(now)
        lines += "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}"

        return ToolResult(lines.joinToString("\n"))
    }

    private fun describeNetwork(): String {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "offline"
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "connected"
        }
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return if (online) kind else "$kind (no internet)"
    }

    // ---------------------------------------------------------------- hardware

    private fun setTorch(on: Boolean): ToolResult {
        val cameras = app.getSystemService(CameraManager::class.java)
            ?: return ToolResult("No camera service on this device.", isError = true)
        val id = cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return ToolResult("This phone has no flash.", isError = true)
        cameras.setTorchMode(id, on)
        torchOn = on
        return ToolResult("Torch is now ${if (on) "on" else "off"}.")
    }

    private fun setVolume(stream: String, percent: Int): ToolResult {
        if (percent !in 0..100) return ToolResult("Volume must be between 0 and 100.", isError = true)
        val audio = app.getSystemService(AudioManager::class.java)
            ?: return ToolResult("No audio service available.", isError = true)
        val id = when (stream.lowercase(Locale.UK)) {
            "media", "music" -> AudioManager.STREAM_MUSIC
            "ring", "ringer" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            "call", "voice" -> AudioManager.STREAM_VOICE_CALL
            else -> return ToolResult("Unknown audio stream \"$stream\".", isError = true)
        }
        val max = audio.getStreamMaxVolume(id)
        val target = (percent * max + 50) / 100
        return try {
            audio.setStreamVolume(id, target, 0)
            ToolResult("$stream volume set to $percent%.")
        } catch (e: SecurityException) {
            ToolResult(
                "Android blocked the volume change because Do Not Disturb access has not been " +
                    "granted to Jarvis. The user can grant it under Settings, Apps, Special app access, " +
                    "Do Not Disturb access.",
                isError = true,
            )
        }
    }

    private fun setRinger(mode: String): ToolResult {
        val audio = app.getSystemService(AudioManager::class.java)
            ?: return ToolResult("No audio service available.", isError = true)
        val value = when (mode.lowercase(Locale.UK)) {
            "normal", "loud" -> AudioManager.RINGER_MODE_NORMAL
            "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
            "silent", "mute" -> AudioManager.RINGER_MODE_SILENT
            else -> return ToolResult("Unknown ringer mode \"$mode\".", isError = true)
        }
        return try {
            audio.ringerMode = value
            ToolResult("Ringer set to $mode.")
        } catch (e: SecurityException) {
            ToolResult(
                "Android blocked that because Jarvis does not have Do Not Disturb access. The user " +
                    "can grant it under Settings, Apps, Special app access, Do Not Disturb access.",
                isError = true,
            )
        }
    }

    private fun mediaControl(action: String): ToolResult {
        val audio = app.getSystemService(AudioManager::class.java)
            ?: return ToolResult("No audio service available.", isError = true)
        val code = when (action.lowercase(Locale.UK)) {
            "play_pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return ToolResult("Unknown media action \"$action\".", isError = true)
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return ToolResult("Sent $action to the media player.")
    }

    // ---------------------------------------------------------------- apps and intents

    private data class AppEntry(val label: String, val packageName: String)

    private fun launchableApps(): List<AppEntry> {
        val pm = app.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase(Locale.UK) }
    }

    private fun listApps(query: String): ToolResult {
        val all = launchableApps()
        val filtered = if (query.isBlank()) all else all.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, true)
        }
        if (filtered.isEmpty()) return ToolResult("No installed app matches \"$query\".")
        val shown = filtered.take(120).joinToString("\n") { "${it.label} (${it.packageName})" }
        val extra = if (filtered.size > 120) "\n…and ${filtered.size - 120} more." else ""
        return ToolResult("Installed apps:\n$shown$extra")
    }

    private fun openApp(name: String): ToolResult {
        if (name.isBlank()) return ToolResult("No app name given.", isError = true)
        val apps = launchableApps()
        val needle = name.trim().lowercase(Locale.UK)
        val match = apps.firstOrNull { it.packageName.equals(name.trim(), ignoreCase = true) }
            ?: apps.firstOrNull { it.label.lowercase(Locale.UK) == needle }
            ?: apps.firstOrNull { it.label.lowercase(Locale.UK).startsWith(needle) }
            ?: apps.firstOrNull { it.label.contains(needle, ignoreCase = true) }
            ?: apps.firstOrNull { it.packageName.contains(needle, ignoreCase = true) }
            ?: return ToolResult(
                "No installed app matches \"$name\". Use list_apps to see what is installed.",
                isError = true,
            )
        val launch = app.packageManager.getLaunchIntentForPackage(match.packageName)
            ?: return ToolResult("${match.label} cannot be launched directly.", isError = true)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startActivitySafely(launch, "Opened ${match.label}.")
    }

    private fun openUrl(url: String): ToolResult {
        if (url.isBlank()) return ToolResult("No URL given.", isError = true)
        val normalised = if (url.contains("://")) url else "https://$url"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalised))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startActivitySafely(intent, "Opened it.")
    }

    private fun openSettings(section: String): ToolResult {
        val action = when (section.lowercase(Locale.UK)) {
            "wifi" -> AndroidSettings.ACTION_WIFI_SETTINGS
            "bluetooth" -> AndroidSettings.ACTION_BLUETOOTH_SETTINGS
            "mobile_data" -> AndroidSettings.ACTION_DATA_ROAMING_SETTINGS
            "airplane_mode" -> AndroidSettings.ACTION_AIRPLANE_MODE_SETTINGS
            "sound" -> AndroidSettings.ACTION_SOUND_SETTINGS
            "display" -> AndroidSettings.ACTION_DISPLAY_SETTINGS
            "battery" -> AndroidSettings.ACTION_BATTERY_SAVER_SETTINGS
            "storage" -> AndroidSettings.ACTION_INTERNAL_STORAGE_SETTINGS
            "location" -> AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS
            "apps" -> AndroidSettings.ACTION_APPLICATION_SETTINGS
            "security" -> AndroidSettings.ACTION_SECURITY_SETTINGS
            "accessibility" -> AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS
            "date_time" -> AndroidSettings.ACTION_DATE_SETTINGS
            "nfc" -> AndroidSettings.ACTION_NFC_SETTINGS
            "dnd_access" -> AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
            else -> AndroidSettings.ACTION_SETTINGS
        }
        return startActivitySafely(
            Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            "Opened the $section settings.",
        )
    }

    private fun startActivitySafely(intent: Intent, success: String): ToolResult = try {
        app.startActivity(intent)
        ToolResult(success)
    } catch (e: ActivityNotFoundException) {
        ToolResult("Nothing on this phone can handle that.", isError = true)
    } catch (e: SecurityException) {
        ToolResult(
            "Android refused to open that from the background. Ask the user to open the Jarvis " +
                "app on screen and try again, or to grant Jarvis \"Display over other apps\".",
            isError = true,
        )
    }

    // ---------------------------------------------------------------- people

    private fun findContact(name: String): ToolResult {
        if (!has(Manifest.permission.READ_CONTACTS)) {
            return ToolResult(
                "Jarvis has not been granted contacts access, so the user's address book cannot be " +
                    "searched. They can grant it in the Jarvis app.",
                isError = true,
            )
        }
        if (name.isBlank()) return ToolResult("No name given.", isError = true)

        val results = mutableListOf<String>()
        app.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%${name.trim()}%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext() && results.size < 10) {
                results += "${cursor.getString(0)}: ${cursor.getString(1)}"
            }
        }
        return if (results.isEmpty()) {
            ToolResult("No contact matches \"$name\".")
        } else {
            ToolResult(results.distinct().joinToString("\n"))
        }
    }

    private fun placeCall(number: String, who: String): ToolResult {
        if (number.isBlank()) return ToolResult("No number given.", isError = true)
        val target = Uri.parse("tel:" + Uri.encode(number.trim()))
        val label = if (who.isBlank()) number.trim() else who.trim()

        val allowed = prefs.current.allowCalls && has(Manifest.permission.CALL_PHONE)
        return if (allowed) {
            startActivitySafely(
                Intent(Intent.ACTION_CALL, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                "Calling $label now.",
            )
        } else {
            val why = if (!prefs.current.allowCalls) {
                "direct calling is switched off in Jarvis's settings"
            } else {
                "the phone permission was declined"
            }
            startActivitySafely(
                Intent(Intent.ACTION_DIAL, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                "Did not dial because $why. The dialer is now open with $label's number ready, " +
                    "so the user only has to press call.",
            )
        }
    }

    private fun sendMessage(number: String, text: String): ToolResult {
        if (number.isBlank() || text.isBlank()) {
            return ToolResult("A recipient and a message are both required.", isError = true)
        }
        val allowed = prefs.current.allowMessages && has(Manifest.permission.SEND_SMS)
        if (allowed) {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                app.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            } ?: return ToolResult("No SMS service on this device.", isError = true)

            val parts = sms.divideMessage(text)
            if (parts.size > 1) {
                sms.sendMultipartTextMessage(number.trim(), null, parts, null, null)
            } else {
                sms.sendTextMessage(number.trim(), null, text, null, null)
            }
            return ToolResult("Message sent to ${number.trim()}.")
        }

        val why = if (!prefs.current.allowMessages) {
            "sending messages is switched off in Jarvis's settings"
        } else {
            "the SMS permission was declined"
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number.trim())))
            .putExtra("sms_body", text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startActivitySafely(
            intent,
            "Did not send it because $why. The messaging app is open with the text written out, " +
                "so the user only has to press send.",
        )
    }

    // ---------------------------------------------------------------- clock and calendar

    private fun setAlarm(hour: Int, minute: Int, label: String): ToolResult {
        if (hour !in 0..23 || minute !in 0..59) {
            return ToolResult("That is not a valid time of day.", isError = true)
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (label.isNotBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        val pretty = String.format(Locale.UK, "%02d:%02d", hour, minute)
        return startActivitySafely(intent, "Alarm set for $pretty.")
    }

    private fun setTimer(seconds: Int, label: String): ToolResult {
        if (seconds <= 0) return ToolResult("A timer needs a positive length.", isError = true)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (label.isNotBlank()) intent.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        return startActivitySafely(intent, "Timer running for $seconds seconds.")
    }

    private fun createEvent(input: JSONObject): ToolResult {
        val title = input.optString("title")
        val startMillis = parseLocal(input.optString("start"))
            ?: return ToolResult(
                "The start time could not be read. Use local ISO-8601, like 2026-08-21T18:30.",
                isError = true,
            )
        val endMillis = parseLocal(input.optString("end")) ?: (startMillis + 3_600_000L)

        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMillis)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMillis)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        input.optString("location").takeIf { it.isNotBlank() }
            ?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        input.optString("notes").takeIf { it.isNotBlank() }
            ?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION, it) }

        return startActivitySafely(
            intent,
            "The calendar is open with \"$title\" filled in. Android does not let an app write " +
                "events silently, so the user needs to tap save.",
        )
    }

    private fun parseLocal(value: String): Long? {
        if (value.isBlank()) return null
        return runCatching {
            LocalDateTime.parse(value.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    // ---------------------------------------------------------------- location

    private fun getLocation(): ToolResult {
        val fine = has(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = has(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) {
            return ToolResult(
                "Jarvis has not been granted location access. The user can grant it in the Jarvis app.",
                isError = true,
            )
        }
        val manager = app.getSystemService(LocationManager::class.java)
            ?: return ToolResult("No location service on this device.", isError = true)

        val newest: Location? = try {
            manager.getProviders(true)
                .mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
        val best: Location = newest
            ?: return ToolResult(
                "The phone has no recent position stored. Ask the user to open a maps app for a " +
                    "moment so the phone gets a fix.",
                isError = true,
            )

        val lat = String.format(Locale.UK, "%.5f", best.latitude)
        val lon = String.format(Locale.UK, "%.5f", best.longitude)
        val ageMinutes = ((System.currentTimeMillis() - best.time) / 60_000L).coerceAtLeast(0)
        val address = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(app, Locale.UK).getFromLocation(best.latitude, best.longitude, 1)
                ?.firstOrNull()
                ?.getAddressLine(0)
        }.getOrNull()

        return ToolResult(
            buildString {
                append("Latitude $lat, longitude $lon")
                if (address != null) append("\nAddress: $address")
                append("\nAccuracy: about ${best.accuracy.toInt()} m, fixed $ageMinutes minutes ago")
            },
        )
    }

    // ---------------------------------------------------------------- memory and clipboard

    private fun rememberFact(key: String, value: String): ToolResult {
        if (key.isBlank() || value.isBlank()) {
            return ToolResult("A key and a value are both required.", isError = true)
        }
        prefs.remember(key, value)
        return ToolResult("Stored \"${key.trim()}\".")
    }

    private fun forgetFact(key: String): ToolResult =
        if (prefs.forget(key)) {
            ToolResult("Forgotten \"${key.trim()}\".")
        } else {
            ToolResult("Nothing is stored under \"${key.trim()}\".")
        }

    private fun copyToClipboard(text: String): ToolResult {
        if (text.isBlank()) return ToolResult("Nothing to copy.", isError = true)
        val clipboard = app.getSystemService(ClipboardManager::class.java)
            ?: return ToolResult("No clipboard service available.", isError = true)
        clipboard.setPrimaryClip(ClipData.newPlainText("Jarvis", text))
        return ToolResult("Copied to the clipboard.")
    }

    // ---------------------------------------------------------------- helpers

    private fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        @Volatile
        var torchOn: Boolean = false
    }
}
