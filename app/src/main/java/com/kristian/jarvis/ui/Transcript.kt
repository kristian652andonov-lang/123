package com.kristian.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kristian.jarvis.ui.theme.JarvisPalette
import com.kristian.jarvis.ui.theme.JarvisTheme

/**
 * The conversation log. Newest at the bottom; it follows along as lines are
 * appended or a partial transcript grows.
 */
@Composable
fun Transcript(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Follow the tail whenever a line is added or the last line changes.
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            MessageRow(message)
        }
    }
}

@Composable
private fun MessageRow(message: ChatMessage) {
    val isUser = message.role == ChatMessage.Role.USER
    val isSystem = message.role == ChatMessage.Role.SYSTEM

    val speaker = when (message.role) {
        ChatMessage.Role.USER -> "YOU"
        ChatMessage.Role.ASSISTANT -> "JARVIS"
        ChatMessage.Role.SYSTEM -> "SYSTEM"
    }
    val accent = when (message.role) {
        ChatMessage.Role.USER -> JarvisPalette.TextSecondary
        ChatMessage.Role.ASSISTANT -> JarvisPalette.Cyan
        ChatMessage.Role.SYSTEM -> JarvisPalette.Amber
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(if (isSystem) 1f else 0.92f)
                .background(
                    color = if (isUser) Color.Transparent else JarvisPalette.Panel.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(if (isUser) 12.dp else 4.dp)
                )
                .border(
                    width = 1.dp,
                    color = accent.copy(alpha = if (isUser) 0.25f else 0.18f),
                    shape = RoundedCornerShape(if (isUser) 12.dp else 4.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                text = speaker,
                style = MaterialTheme.typography.labelSmall,
                color = accent
            )
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = if (isSystem) FontFamily.Monospace else FontFamily.SansSerif
                ),
                color = if (isSystem) JarvisPalette.Amber else JarvisPalette.TextPrimary,
                modifier = Modifier
                    .padding(top = 3.dp)
                    .alpha(if (message.partial) 0.7f else 1f)
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF04070D, widthDp = 380, heightDp = 320)
@Composable
private fun TranscriptPreview() {
    JarvisTheme {
        Transcript(
            messages = listOf(
                ChatMessage(ChatMessage.Role.SYSTEM, "Wake word armed."),
                ChatMessage(ChatMessage.Role.USER, "Jarvis, what's on my plate today?"),
                ChatMessage(
                    ChatMessage.Role.ASSISTANT,
                    "Three things, sir. A build to review, a call at four, " +
                        "and the small matter of dinner."
                )
            )
        )
    }
}
