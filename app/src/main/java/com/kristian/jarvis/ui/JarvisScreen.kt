package com.kristian.jarvis.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kristian.jarvis.ui.theme.JarvisPalette
import com.kristian.jarvis.ui.theme.JarvisTheme

/**
 * The whole face of the assistant: dial and status up top, transcript in the
 * middle, input bar pinned at the bottom. Purely presentational — every
 * interaction is handed back out through the callbacks.
 */
@Composable
fun JarvisScreen(
    state: AssistantState,
    messages: List<ChatMessage>,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicTap: () -> Unit,
    micEnabled: Boolean,
    amplitude: Float = 0f,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        JarvisPalette.Void,
                        JarvisPalette.Panel.copy(alpha = 0.6f),
                        JarvisPalette.Void
                    )
                )
            )
            .systemBarsPadding()
            .imePadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            HudDial(state = state, amplitude = amplitude, size = 200.dp)
        }

        StatusReadout(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 8.dp)
        )

        HorizontalDivider(color = JarvisPalette.PanelEdge)

        Transcript(
            messages = messages,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        HorizontalDivider(color = JarvisPalette.PanelEdge)

        InputBar(
            text = inputText,
            onTextChange = onInputChange,
            onSend = onSend,
            onMicTap = onMicTap,
            state = state,
            micEnabled = micEnabled
        )
    }
}

@Composable
private fun StatusReadout(state: AssistantState, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "status")
    val blink by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (state == AssistantState.IDLE) 2200 else 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "blink"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "J A R V I S",
            style = MaterialTheme.typography.titleLarge,
            color = JarvisPalette.TextPrimary
        )
        Row(
            modifier = Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .alpha(blink)
                    .background(state.accent, CircleShape)
            )
            Text(
                text = state.label,
                style = MaterialTheme.typography.titleMedium,
                color = state.accent
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF04070D, widthDp = 400, heightDp = 860)
@Composable
private fun JarvisScreenPreview() {
    JarvisTheme {
        JarvisScreen(
            state = AssistantState.SPEAKING,
            messages = listOf(
                ChatMessage(ChatMessage.Role.USER, "Jarvis, run a diagnostic."),
                ChatMessage(
                    ChatMessage.Role.ASSISTANT,
                    "All systems nominal, sir. Though I'd note the battery is " +
                        "at 12 per cent, which I suspect matters more."
                )
            ),
            inputText = "",
            onInputChange = {},
            onSend = {},
            onMicTap = {},
            micEnabled = true
        )
    }
}
