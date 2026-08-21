package com.kristian.jarvis.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kristian.jarvis.ui.theme.JarvisPalette
import com.kristian.jarvis.ui.theme.JarvisTheme

/**
 * Always-visible bottom bar: type a command, or tap the dial-mic to speak one
 * without using the wake word.
 */
@Composable
fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicTap: () -> Unit,
    state: AssistantState,
    micEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    text = "Type a command",
                    style = MaterialTheme.typography.bodyMedium,
                    color = JarvisPalette.TextSecondary.copy(alpha = 0.7f)
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            singleLine = false,
            maxLines = 4,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = JarvisPalette.Cyan.copy(alpha = 0.7f),
                unfocusedBorderColor = JarvisPalette.PanelEdge,
                focusedContainerColor = JarvisPalette.Panel.copy(alpha = 0.6f),
                unfocusedContainerColor = JarvisPalette.Panel.copy(alpha = 0.4f),
                cursorColor = JarvisPalette.Cyan,
                focusedTextColor = JarvisPalette.TextPrimary,
                unfocusedTextColor = JarvisPalette.TextPrimary
            ),
            trailingIcon = {
                if (text.isNotBlank()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = JarvisPalette.Cyan,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(22.dp)
                            .clickable { onSend() }
                    )
                }
            }
        )

        MicButton(state = state, enabled = micEnabled, onTap = onMicTap)
    }
}

@Composable
private fun MicButton(
    state: AssistantState,
    enabled: Boolean,
    onTap: () -> Unit
) {
    val listening = state == AssistantState.LISTENING
    val transition = rememberInfiniteTransition(label = "mic")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (listening) 1.12f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "micPulse"
    )

    val accent = when {
        !enabled -> JarvisPalette.TextSecondary
        listening -> JarvisPalette.CyanBright
        else -> JarvisPalette.Cyan
    }

    Box(
        modifier = Modifier
            .size(52.dp)
            .scale(pulse)
            .background(
                color = accent.copy(alpha = if (listening) 0.22f else 0.10f),
                shape = CircleShape
            )
            .border(width = 1.dp, color = accent.copy(alpha = 0.6f), shape = CircleShape)
            .clickable(enabled = enabled) { onTap() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (enabled) Icons.Filled.Mic else Icons.Filled.MicOff,
            contentDescription = if (listening) "Stop listening" else "Speak a command",
            tint = accent,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF04070D, widthDp = 380)
@Composable
private fun InputBarPreview() {
    JarvisTheme {
        InputBar(
            text = "",
            onTextChange = {},
            onSend = {},
            onMicTap = {},
            state = AssistantState.IDLE,
            micEnabled = true
        )
    }
}
