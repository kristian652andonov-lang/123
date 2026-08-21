package com.kristian.jarvis.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** What JARVIS is allowed to do right now. */
class JarvisPermissions(
    val microphone: Boolean,
    val notifications: Boolean,
    /** False until the first prompt has been answered one way or the other. */
    val resolved: Boolean,
    /** Asks for anything still missing. Safe to call repeatedly. */
    val request: () -> Unit
)

/**
 * Runtime permission state for the two things the assistant needs: the
 * microphone (voice in) and notifications (so the always-on service can show
 * its required ongoing notification).
 *
 * Notifications are only requested on Android 13+; below that they are granted
 * by installing.
 */
@Composable
fun rememberJarvisPermissions(): JarvisPermissions {
    val context = LocalContext.current
    var mic by remember { mutableStateOf(context.hasPermission(Manifest.permission.RECORD_AUDIO)) }
    var notifications by remember { mutableStateOf(context.hasNotificationPermission()) }
    var resolved by remember { mutableStateOf(context.hasPermission(Manifest.permission.RECORD_AUDIO)) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        result[Manifest.permission.RECORD_AUDIO]?.let { mic = it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result[Manifest.permission.POST_NOTIFICATIONS]?.let { notifications = it }
        }
        // Re-read rather than trust the map: a permission may already have been held.
        mic = context.hasPermission(Manifest.permission.RECORD_AUDIO)
        notifications = context.hasNotificationPermission()
        resolved = true
    }

    return JarvisPermissions(
        microphone = mic,
        notifications = notifications,
        resolved = resolved,
        request = {
            val wanted = buildList {
                if (!context.hasPermission(Manifest.permission.RECORD_AUDIO)) {
                    add(Manifest.permission.RECORD_AUDIO)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
                ) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (wanted.isNotEmpty()) launcher.launch(wanted.toTypedArray()) else resolved = true
        }
    )
}

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.hasNotificationPermission(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        hasPermission(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        true
    }
