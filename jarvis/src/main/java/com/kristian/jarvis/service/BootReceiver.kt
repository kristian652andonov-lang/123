package com.kristian.jarvis.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.kristian.jarvis.core.Prefs

/**
 * Brings the wake word back after a reboot.
 *
 * Android 12 onwards forbids starting a microphone foreground service from the background,
 * so on those versions the user has to open Jarvis once after a restart. There is no way
 * around that, and pretending otherwise would just fail silently.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return
        if (!Prefs(context).current.wakeWordEnabled) return
        WakeWordService.start(context)
    }
}
