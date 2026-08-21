package com.kristian.jarvis

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kristian.jarvis.claude.SecureKeyStore
import com.kristian.jarvis.core.JarvisEngine
import com.kristian.jarvis.core.JarvisViewModel
import com.kristian.jarvis.llm.LlmProvider
import com.kristian.jarvis.settings.JarvisPrefs
import com.kristian.jarvis.service.JarvisService
import com.kristian.jarvis.ui.ApiKeyScreen
import com.kristian.jarvis.ui.AssistantState
import com.kristian.jarvis.ui.ChatMessage
import com.kristian.jarvis.ui.HudDial
import com.kristian.jarvis.ui.JarvisScreen
import com.kristian.jarvis.ui.SettingsScreen
import com.kristian.jarvis.ui.rememberJarvisPermissions
import com.kristian.jarvis.ui.theme.JarvisPalette
import com.kristian.jarvis.ui.theme.JarvisTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            JarvisTheme {
                JarvisRoot()
            }
        }
    }
}

/** Key entry stands in front of everything until a key is stored. */
@Composable
private fun JarvisRoot() {
    val context = LocalContext.current
    val keys = remember { SecureKeyStore(context) }
    val prefs = remember { JarvisPrefs(context) }
    var hasKey by remember { mutableStateOf(keys.hasApiKey || keys.hasGeminiKey) }

    if (hasKey) {
        JarvisApp()
    } else {
        ApiKeyScreen(
            onSave = { key ->
                // Store against whichever service the key belongs to, and make
                // that the active provider.
                when (LlmProvider.detectFromKey(key)) {
                    LlmProvider.ANTHROPIC -> {
                        keys.apiKey = key
                        prefs.provider = LlmProvider.ANTHROPIC.name
                    }

                    LlmProvider.GEMINI -> {
                        keys.geminiApiKey = key
                        prefs.provider = LlmProvider.GEMINI.name
                    }

                    null -> Unit
                }
                hasKey = keys.hasApiKey || keys.hasGeminiKey
            }
        )
    }
}

@Composable
private fun JarvisApp(viewModel: JarvisViewModel = viewModel()) {
    val context = LocalContext.current
    val permissions = rememberJarvisPermissions()
    var boundEngine by remember { mutableStateOf<JarvisEngine?>(null) }
    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { permissions.request() }

    // With the microphone granted the service owns the engine, so listening
    // keeps working once this screen goes away. The UI just binds to it.
    val useService = permissions.resolved && permissions.microphone
    DisposableEffect(useService) {
        if (!useService) return@DisposableEffect onDispose { }

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                boundEngine = (service as? JarvisService.LocalBinder)?.service?.engine
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                boundEngine = null
            }
        }

        JarvisService.start(context)
        context.bindService(
            Intent(context, JarvisService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )

        onDispose {
            // Unbind only - the service stays up on purpose.
            runCatching { context.unbindService(connection) }
            boundEngine = null
        }
    }

    val engine = when {
        useService -> boundEngine
        permissions.resolved -> viewModel.localEngine
        else -> null
    }

    // The no-microphone fallback engine has to be started by hand; the
    // service starts its own.
    LaunchedEffect(engine, permissions.microphone) {
        if (engine != null && !useService) engine.start(micGranted = false)
    }

    if (engine == null) {
        BootingScreen()
        return
    }

    val state by engine.uiState.collectAsState()

    if (showSettings) {
        // Querying the speech engine is a binder call - do it once per visit,
        // not on every slider drag.
        val voices = remember(showSettings) { engine.voiceOptions() }
        SettingsScreen(
            prefs = engine.settings,
            voices = voices,
            maskedApiKey = engine.maskedApiKey,
            maskedGeminiKey = engine.maskedGeminiKey,
            provider = engine.activeProvider(),
            onProviderChange = { engine.setProvider(it) },
            onBack = { showSettings = false },
            onVoiceSelected = { engine.applyVoice(it) },
            onRateAndPitch = { rate, pitch -> engine.applyRateAndPitch(rate, pitch) },
            onPreviewVoice = { engine.previewVoice() },
            onApiKeyChanged = { engine.updateApiKey(it) },
            onWakeWordEnabled = { engine.setWakeWordEnabled(it) },
            onClearConversation = {
                engine.clearConversation()
                showSettings = false
            }
        )
        return
    }

    // While you speak, show the running transcription as a provisional line.
    val messages = if (state.partialTranscript.isNotBlank()) {
        state.messages + ChatMessage(
            role = ChatMessage.Role.USER,
            text = state.partialTranscript,
            id = ChatMessage.LIVE_ID,
            partial = true
        )
    } else {
        state.messages
    }

    JarvisScreen(
        state = state.state,
        messages = messages,
        inputText = input,
        onInputChange = { input = it },
        onSend = {
            val text = input.trim()
            if (text.isNotEmpty()) {
                engine.submit(text)
                input = ""
            }
        },
        onMicTap = {
            if (permissions.microphone) engine.onMicTap() else permissions.request()
        },
        micEnabled = permissions.microphone,
        narrate = state.narrate,
        onNarrateToggle = { engine.setNarrate(!state.narrate) },
        onOpenSettings = { showSettings = true },
        amplitude = state.amplitude
    )
}

/** Shown for the moment between launch and the service handing over its engine. */
@Composable
private fun BootingScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(JarvisPalette.Void),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        HudDial(state = AssistantState.IDLE, size = 180.dp)
        Text(
            text = "COMING ONLINE",
            style = MaterialTheme.typography.titleMedium,
            color = JarvisPalette.TextSecondary,
            modifier = Modifier.padding(top = 16.dp)
        )
    }
}
