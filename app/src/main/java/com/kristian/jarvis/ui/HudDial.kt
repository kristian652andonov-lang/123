package com.kristian.jarvis.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kristian.jarvis.ui.theme.JarvisTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * The arc-reactor dial that anchors the HUD.
 *
 * It is drawn procedurally so it can react to [state] — breathing slowly on
 * standby, spinning up while listening, pulsing while speaking. If a bitmap
 * named `jarvis_hud` is dropped into `res/drawable`, it is layered underneath
 * the live rings automatically; no code change needed.
 */
@Composable
fun HudDial(
    state: AssistantState,
    modifier: Modifier = Modifier,
    /** 0f..1f microphone level, used to make the dial react to your voice. */
    amplitude: Float = 0f,
    size: Dp = 220.dp
) {
    val transition = rememberInfiniteTransition(label = "hud")

    // Outer ring sweeps continuously; faster when JARVIS is busy.
    val spinDuration = when (state) {
        AssistantState.IDLE -> 24000
        AssistantState.LISTENING -> 7000
        AssistantState.THINKING -> 3200
        AssistantState.SPEAKING -> 9000
        AssistantState.ERROR -> 16000
    }
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(spinDuration, easing = LinearEasing)
        ),
        label = "spin"
    )

    // Slow "breathing" of the core, independent of the spin.
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )

    // How energised the dial looks overall, eased so state changes glide.
    val targetEnergy = when (state) {
        AssistantState.IDLE -> 0.25f
        AssistantState.LISTENING -> 0.85f
        AssistantState.THINKING -> 0.6f
        AssistantState.SPEAKING -> 1f
        AssistantState.ERROR -> 0.45f
    }
    val energy by animateFloatAsState(
        targetValue = targetEnergy,
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "energy"
    )

    val accent = state.accent
    val context = LocalContext.current
    // Optional user-supplied HUD artwork, resolved by name so its absence is fine.
    val hudResId = remember {
        context.resources.getIdentifier("jarvis_hud", "drawable", context.packageName)
    }

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (hudResId != 0) {
            Image(
                painter = painterResource(id = hudResId),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                alpha = 0.55f + 0.35f * energy,
                modifier = Modifier.fillMaxSize()
            )
        }
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawDial(
                accent = accent,
                spin = spin,
                breath = breath,
                energy = energy,
                amplitude = amplitude.coerceIn(0f, 1f),
                drawStaticRings = hudResId == 0
            )
        }
    }
}

private fun DrawScope.drawDial(
    accent: Color,
    spin: Float,
    breath: Float,
    energy: Float,
    amplitude: Float,
    drawStaticRings: Boolean
) {
    val center = Offset(this.size.width / 2f, this.size.height / 2f)
    val radius = kotlin.math.min(this.size.width, this.size.height) / 2f

    // Ambient glow behind everything.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                accent.copy(alpha = 0.22f * (0.4f + energy)),
                Color.Transparent
            ),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )

    if (drawStaticRings) {
        // Outer hairline ring
        drawCircle(
            color = accent.copy(alpha = 0.35f),
            radius = radius * 0.96f,
            center = center,
            style = Stroke(width = 1.5f)
        )
        // Tick marks
        val ticks = 60
        for (i in 0 until ticks) {
            val major = i % 5 == 0
            val angle = Math.toRadians((i * 360f / ticks).toDouble())
            val inner = radius * if (major) 0.82f else 0.87f
            val outer = radius * 0.92f
            drawLine(
                color = accent.copy(alpha = if (major) 0.55f else 0.22f),
                start = Offset(
                    center.x + (cos(angle) * inner).toFloat(),
                    center.y + (sin(angle) * inner).toFloat()
                ),
                end = Offset(
                    center.x + (cos(angle) * outer).toFloat(),
                    center.y + (sin(angle) * outer).toFloat()
                ),
                strokeWidth = if (major) 2.5f else 1.2f
            )
        }
        // Static inner ring
        drawCircle(
            color = accent.copy(alpha = 0.28f),
            radius = radius * 0.58f,
            center = center,
            style = Stroke(width = 1.2f)
        )
    }

    // Two counter-rotating arc segments — the "live" part of the dial.
    val arcRadius = radius * 0.74f
    val arcSize = Size(arcRadius * 2, arcRadius * 2)
    val arcTopLeft = Offset(center.x - arcRadius, center.y - arcRadius)
    listOf(
        Triple(spin, 110f, 0.9f),
        Triple(-spin * 1.4f + 45f, 60f, 0.55f)
    ).forEach { (start, sweep, alpha) ->
        drawArc(
            color = accent.copy(alpha = alpha * (0.35f + 0.65f * energy)),
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = 3.5f)
        )
    }

    // Inner ring that swells with mic level / breathing.
    val pulse = 0.44f + 0.03f * breath * (1f + energy) + 0.06f * amplitude
    drawCircle(
        color = accent.copy(alpha = 0.5f + 0.4f * energy),
        radius = radius * pulse,
        center = center,
        style = Stroke(width = 2f)
    )

    // Core.
    val coreRadius = radius * (0.2f + 0.03f * breath + 0.05f * amplitude)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.55f * (0.4f + energy)),
                accent.copy(alpha = 0.75f * (0.3f + energy)),
                Color.Transparent
            ),
            center = center,
            radius = coreRadius * 2.4f
        ),
        radius = coreRadius * 2.4f,
        center = center
    )
    drawCircle(
        color = accent.copy(alpha = 0.85f),
        radius = coreRadius,
        center = center
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF04070D)
@Composable
private fun HudDialPreview() {
    JarvisTheme {
        Box(
            modifier = Modifier.size(260.dp),
            contentAlignment = Alignment.Center
        ) {
            HudDial(state = AssistantState.LISTENING)
        }
    }
}
