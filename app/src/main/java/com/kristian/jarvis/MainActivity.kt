package com.kristian.jarvis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kristian.jarvis.claude.SecureKeyStore
import com.kristian.jarvis.core.JarvisViewModel
import com.kristian.jarvis.ui.ApiKeyScreen
import com.kristian.jarvis.ui.ChatMessage
import com.kristian.jarvis.ui.JarvisScreen
import com.kristian.jarvis.ui.rememberJarvisPermissions
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
    var hasKey by remember { mutableStateOf(keys.hasApiKey) }

    if (hasKey) {
        JarvisApp()
    } else {
        ApiKeyScreen(
            onSave = { key ->
                keys.apiKey = key
                hasKey = keys.hasApiKey
            }
        )
    }
}

@Composable
private fun JarvisApp(viewModel: JarvisViewModel = viewModel()) {
    val permissions = rememberJarvisPermissions()
    val state by viewModel.uiState.collectAsState()
    var input by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { permissions.request() }

    // Start (and keep in step with) whatever the microphone permission is now.
    LaunchedEffect(permissions.resolved, permissions.microphone) {
        if (permissions.resolved) viewModel.engine.start(permissions.microphone)
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
                viewModel.engine.submit(text)
                input = ""
            }
        },
        onMicTap = {
            if (permissions.microphone) viewModel.engine.onMicTap() else permissions.request()
        },
        micEnabled = permissions.microphone,
        narrate = state.narrate,
        onNarrateToggle = { viewModel.engine.setNarrate(!state.narrate) },
        amplitude = state.amplitude
    )
}
