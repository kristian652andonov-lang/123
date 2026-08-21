package com.kristian.jarvis.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * One dark HUD palette for the whole app: near-black backdrop, cyan/blue glow,
 * amber reserved for warnings, red for errors. Nothing else.
 */
object JarvisPalette {
    val Void = Color(0xFF04070D)        // app background
    val Panel = Color(0xFF0A1119)       // cards / raised surfaces
    val PanelEdge = Color(0xFF16263A)   // hairline borders

    val Cyan = Color(0xFF3FD0FF)        // primary accent
    val CyanBright = Color(0xFF8FEBFF)  // highlights, active glow
    val CyanDim = Color(0xFF1B5C7A)     // inactive rings, dividers

    val TextPrimary = Color(0xFFDCEEF9)
    val TextSecondary = Color(0xFF7E9AAE)

    val Amber = Color(0xFFFFB84D)       // "thinking" / warnings
    val Danger = Color(0xFFFF5C5C)      // errors
}
