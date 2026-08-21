package com.kristian.jarvis.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kristian.jarvis.core.JarvisEngine
import com.kristian.jarvis.core.Line
import com.kristian.jarvis.core.Phase
import com.kristian.jarvis.core.Who

@Composable
fun JarvisScreen(
    engine: JarvisEngine,
    onOpenSettings: () -> Unit,
    onAskForMicrophone: () -> Unit,
) {
    val phase by engine.phase.collectAsStateWithLifecycle()
    val lines by engine.transcript.collectAsStateWithLifecycle()
    val level by engine.level.collectAsStateWithLifecycle()
    val status by engine.status.collectAsStateWithLifecycle()
    val partial by engine.partial.collectAsStateWithLifecycle()
    val settings by engine.prefs.state.collectAsStateWithLifecycle()

    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size, lines.lastOrNull()?.text) {
        if (lines.isNotEmpty()) {
            runCatching { listState.animateScrollToItem(lines.lastIndex) }
        }
    }

    val hudSize by animateDpAsState(
        targetValue = if (lines.isEmpty()) 250.dp else 128.dp,
        label = "hud",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Hud.Void, Hud.Deep, Hud.Void),
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding(),
        ) {
            Header(
                phase = phase,
                status = status,
                wakeOn = settings.wakeWordEnabled,
                onNewConversation = { engine.newConversation() },
                onOpenSettings = onOpenSettings,
            )

            ReactorHud(
                phase = phase,
                level = level,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 8.dp)
                    .size(hudSize),
            )

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (lines.isEmpty()) {
                    EmptyState(Modifier.align(Alignment.TopCenter))
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(lines, key = { it.id }) { line -> Bubble(line) }
                    }
                }
            }

            if (partial.isNotBlank()) {
                Text(
                    text = partial,
                    color = Hud.Muted,
                    fontSize = 15.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp)
                        .alpha(0.8f),
                )
            }

            InputBar(
                draft = draft,
                onDraftChange = { draft = it },
                phase = phase,
                onSend = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        draft = ""
                        engine.submit(text)
                    }
                },
                onMic = {
                    when (phase) {
                        Phase.LISTENING -> engine.stopListening()
                        Phase.THINKING, Phase.SPEAKING -> engine.interrupt()
                        Phase.IDLE -> onAskForMicrophone()
                    }
                },
            )
        }
    }
}

@Composable
private fun Header(
    phase: Phase,
    status: String,
    wakeOn: Boolean,
    onNewConversation: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "J A R V I S",
                color = Hud.Cyan,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                letterSpacing = 3.sp,
            )
            Text(
                text = (status.ifBlank { phase.label() }).uppercase(),
                style = HudLabel,
                color = if (phase == Phase.IDLE) Hud.Muted else Hud.Cyan,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        if (wakeOn) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(Hud.Amber, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
        }
        IconButton(onClick = onNewConversation) {
            Icon(Icons.Filled.Refresh, contentDescription = "New conversation", tint = Hud.Muted)
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Hud.Muted)
        }
    }
}

private fun Phase.label(): String = when (this) {
    Phase.IDLE -> "standing by"
    Phase.LISTENING -> "listening"
    Phase.THINKING -> "thinking"
    Phase.SPEAKING -> "speaking"
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "At your service.",
            color = Hud.Text,
            fontSize = 18.sp,
        )
        Spacer(Modifier.height(14.dp))
        listOf(
            "\"Torch on.\"",
            "\"Set an alarm for seven.\"",
            "\"How much battery have I got?\"",
            "\"Text Mum that I'm running late.\"",
            "\"What's the weather doing tomorrow?\"",
        ).forEach {
            Text(
                text = it,
                color = Hud.Muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun Bubble(line: Line) {
    when (line.who) {
        Who.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                text = line.text,
                color = Hud.Text,
                fontSize = 16.sp,
                modifier = Modifier
                    .border(1.dp, Hud.CyanDim, RoundedCornerShape(14.dp))
                    .background(Hud.Panel.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }

        Who.JARVIS -> Text(
            text = line.text,
            color = Hud.CyanGlow,
            fontSize = 17.sp,
            lineHeight = 24.sp,
            modifier = Modifier.fillMaxWidth(),
        )

        Who.SYSTEM -> Text(
            text = line.text,
            color = Hud.Amber,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun InputBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    phase: Phase,
    onSend: () -> Unit,
    onMic: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Speak, or type here", color = Hud.Muted, fontSize = 15.sp) },
            shape = RoundedCornerShape(24.dp),
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Hud.Cyan,
                unfocusedBorderColor = Hud.CyanDim,
                focusedTextColor = Hud.Text,
                unfocusedTextColor = Hud.Text,
                cursorColor = Hud.Cyan,
                focusedContainerColor = Hud.Deep.copy(alpha = 0.7f),
                unfocusedContainerColor = Hud.Deep.copy(alpha = 0.5f),
            ),
        )

        Spacer(Modifier.width(12.dp))

        val busy = phase == Phase.THINKING || phase == Phase.SPEAKING
        val active = phase == Phase.LISTENING
        Box(
            Modifier
                .size(56.dp)
                .background(
                    brush = Brush.radialGradient(
                        listOf(
                            if (active) Hud.Cyan.copy(alpha = 0.35f) else Color.Transparent,
                            Color.Transparent,
                        ),
                    ),
                    shape = CircleShape,
                )
                .border(1.5.dp, if (active) Hud.CyanGlow else Hud.CyanDim, CircleShape)
                .clickable { if (draft.isNotBlank()) onSend() else onMic() },
            contentAlignment = Alignment.Center,
        ) {
            when {
                draft.isNotBlank() -> SendGlyph(Hud.Cyan, Modifier.size(22.dp))
                busy || active -> StopGlyph(if (active) Hud.CyanGlow else Hud.Amber, Modifier.size(24.dp))
                else -> MicGlyph(Hud.Cyan, Modifier.size(26.dp))
            }
        }
    }
}
