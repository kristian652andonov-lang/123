package com.kristian.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import com.kristian.jarvis.core.Phase

/** First run: Jarvis is inert until he has a key to speak through. */
@Composable
fun SetupScreen(onKeyEntered: (String) -> Unit) {
    var key by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Hud.Void, Hud.Deep, Hud.Void)))
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(48.dp))

        ReactorHud(phase = Phase.IDLE, level = 0f, modifier = Modifier.size(180.dp))

        Spacer(Modifier.height(24.dp))

        Text(
            text = "J A R V I S",
            color = Hud.Cyan,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            letterSpacing = 5.sp,
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Offline until you give him a voice. Paste an Anthropic API key and he wakes up.",
            color = Hud.Text,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))

        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API key", color = Hud.Muted) },
            placeholder = { Text("sk-ant-…", color = Hud.Muted) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Hud.Cyan,
                unfocusedBorderColor = Hud.CyanDim,
                focusedTextColor = Hud.Text,
                unfocusedTextColor = Hud.Text,
                cursorColor = Hud.Cyan,
                focusedLabelColor = Hud.Cyan,
                focusedContainerColor = Hud.Deep,
                unfocusedContainerColor = Hud.Deep,
            ),
        )

        Spacer(Modifier.height(20.dp))

        Box(
            Modifier
                .border(
                    1.5.dp,
                    if (key.isBlank()) Hud.CyanDim else Hud.Cyan,
                    RoundedCornerShape(24.dp),
                )
                .clickable(enabled = key.isNotBlank()) { onKeyEntered(key.trim()) }
                .padding(horizontal = 32.dp, vertical = 12.dp),
        ) {
            Text(
                text = "ENGAGE",
                style = HudLabel,
                fontSize = 13.sp,
                color = if (key.isBlank()) Hud.Muted else Hud.CyanGlow,
            )
        }

        Spacer(Modifier.height(28.dp))

        Text(
            text = "Get a key at console.anthropic.com under API keys. It is kept encrypted on " +
                "this phone, never leaves it except to reach Anthropic, and every request is " +
                "billed to your own account.",
            color = Hud.Muted,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(48.dp))
    }
}
