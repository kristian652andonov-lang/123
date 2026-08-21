package com.kristian.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kristian.jarvis.core.JarvisEngine
import com.kristian.jarvis.service.WakeWordService
import com.kristian.jarvis.ui.JarvisTheme
import com.kristian.jarvis.ui.JarvisScreen
import com.kristian.jarvis.ui.SettingsScreen
import com.kristian.jarvis.ui.SetupScreen

class MainActivity : ComponentActivity() {

    private lateinit var engine: JarvisEngine

    /** Bumped whenever a permission result comes back, to re-read the grant state. */
    private var permissionEpoch by mutableIntStateOf(0)

    private var listenOnceReady = false

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            permissionEpoch++
            if (grants[Manifest.permission.RECORD_AUDIO] == true && listenOnceReady) {
                listenOnceReady = false
                engine.listen()
            }
            if (engine.prefs.current.wakeWordEnabled && granted(Manifest.permission.RECORD_AUDIO)) {
                WakeWordService.start(this)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        engine = JarvisEngine.get(this)

        setContent {
            JarvisTheme {
                val settings by engine.prefs.state.collectAsStateWithLifecycle()
                var showSettings by remember { mutableStateOf(false) }

                when {
                    !settings.isConfigured -> SetupScreen { key ->
                        engine.prefs.update { it.copy(apiKey = key) }
                        askForEssentials()
                    }

                    showSettings -> SettingsScreen(
                        engine = engine,
                        isGranted = { permission ->
                            // Reading the epoch here is what makes this recompose once a
                            // permission dialog has been answered.
                            permissionEpoch.let { granted(permission) }
                        },
                        onRequestPermission = { permission ->
                            requestPermissions.launch(arrayOf(permission))
                        },
                        onWakeWordChanged = ::setWakeWord,
                        onOpenOverlaySettings = ::openOverlaySettings,
                        onBack = { showSettings = false },
                    )

                    else -> JarvisScreen(
                        engine = engine,
                        onOpenSettings = { showSettings = true },
                        onAskForMicrophone = ::listenOrAsk,
                    )
                }
            }
        }

        askForEssentials()
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        permissionEpoch++
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        val wantsVoice = intent.getBooleanExtra(EXTRA_START_LISTENING, false) ||
            intent.action == Intent.ACTION_ASSIST ||
            intent.action == "android.intent.action.VOICE_COMMAND"
        if (wantsVoice && engine.prefs.current.isConfigured) listenOrAsk()
    }

    private fun listenOrAsk() {
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            engine.listen()
        } else {
            listenOnceReady = true
            requestPermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    /** The two permissions Jarvis is useless without: hearing you, and telling you things. */
    private fun askForEssentials() {
        val wanted = mutableListOf<String>()
        if (!granted(Manifest.permission.RECORD_AUDIO)) {
            wanted += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        if (wanted.isNotEmpty()) requestPermissions.launch(wanted.toTypedArray())
    }

    private fun openOverlaySettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }
    }

    private fun setWakeWord(enabled: Boolean) {
        if (!enabled) {
            WakeWordService.stop(this)
            return
        }
        if (granted(Manifest.permission.RECORD_AUDIO)) {
            WakeWordService.start(this)
        } else {
            requestPermissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val EXTRA_START_LISTENING = "com.kristian.jarvis.START_LISTENING"
    }
}
