package com.kristian.jarvis.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

object Hud {
    val Void = Color(0xFF04070C)
    val Deep = Color(0xFF081420)
    val Panel = Color(0xFF0B1A26)
    val Cyan = Color(0xFF4DD9FF)
    val CyanDim = Color(0xFF1D5C74)
    val CyanGlow = Color(0xFFBDF2FF)
    val Amber = Color(0xFFFFB454)
    val Text = Color(0xFFD7EAF4)
    val Muted = Color(0xFF7C97A6)
}

val HudLabel = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    letterSpacing = 4.sp,
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Hud.Cyan,
            onPrimary = Hud.Void,
            secondary = Hud.CyanDim,
            background = Hud.Void,
            onBackground = Hud.Text,
            surface = Hud.Deep,
            onSurface = Hud.Text,
            surfaceVariant = Hud.Panel,
            onSurfaceVariant = Hud.Muted,
            outline = Hud.CyanDim,
            error = Hud.Amber,
        ),
        content = content,
    )
}
