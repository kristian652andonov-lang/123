package com.kristian.jarvis.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val JarvisColorScheme = darkColorScheme(
    primary = JarvisPalette.Cyan,
    onPrimary = JarvisPalette.Void,
    secondary = JarvisPalette.CyanBright,
    onSecondary = JarvisPalette.Void,
    background = JarvisPalette.Void,
    onBackground = JarvisPalette.TextPrimary,
    surface = JarvisPalette.Panel,
    onSurface = JarvisPalette.TextPrimary,
    surfaceVariant = JarvisPalette.Panel,
    onSurfaceVariant = JarvisPalette.TextSecondary,
    outline = JarvisPalette.PanelEdge,
    error = JarvisPalette.Danger,
    onError = JarvisPalette.Void
)

/**
 * Always dark — JARVIS has exactly one look, regardless of the system setting.
 * [isSystemInDarkTheme] is deliberately ignored.
 */
@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JarvisColorScheme,
        typography = JarvisTypography,
        content = content
    )
}
