package com.kristian.jarvis.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.kristian.jarvis.core.Phase
import kotlin.math.cos
import kotlin.math.sin

/**
 * The arc reactor. Rings turn slowly while Jarvis is idle, quicken when he is thinking,
 * and the core swells with the microphone level while he is listening.
 */
@Composable
fun ReactorHud(
    phase: Phase,
    level: Float,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "reactor")

    val outerSpeed = when (phase) {
        Phase.THINKING -> 3_600
        Phase.LISTENING -> 9_000
        Phase.SPEAKING -> 6_000
        Phase.IDLE -> 18_000
    }

    val outer by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(outerSpeed, easing = LinearEasing)),
        label = "outer",
    )
    val inner by transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(outerSpeed / 2, easing = LinearEasing)),
        label = "inner",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            tween(if (phase == Phase.SPEAKING) 700 else 2_600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val loudness by animateFloatAsState(
        targetValue = if (phase == Phase.LISTENING) level else 0f,
        animationSpec = tween(120),
        label = "loudness",
    )

    val accent = when (phase) {
        Phase.IDLE -> Hud.Cyan
        Phase.LISTENING -> Hud.CyanGlow
        Phase.THINKING -> Hud.Cyan
        Phase.SPEAKING -> Hud.CyanGlow
    }

    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        val centre = Offset(size.width / 2f, size.height / 2f)

        // Faint halo behind everything.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                center = centre,
                radius = radius,
            ),
            radius = radius,
            center = centre,
        )

        ring(centre, radius * 0.98f, accent.copy(alpha = 0.18f), radius * 0.012f)
        ticks(centre, radius * 0.90f, radius * 0.96f, accent.copy(alpha = 0.35f), radius * 0.008f)

        rotate(outer, centre) {
            segments(
                centre = centre,
                radius = radius * 0.82f,
                stroke = radius * 0.035f,
                colour = accent.copy(alpha = 0.85f),
                count = 3,
                sweep = 46f,
            )
        }

        rotate(inner, centre) {
            segments(
                centre = centre,
                radius = radius * 0.66f,
                stroke = radius * 0.02f,
                colour = accent.copy(alpha = 0.55f),
                count = 6,
                sweep = 14f,
            )
        }

        if (phase == Phase.THINKING) {
            rotate(-outer * 2.2f, centre) {
                arc(centre, radius * 0.74f, radius * 0.012f, Hud.Amber.copy(alpha = 0.8f), 0f, 90f)
            }
        }

        val coreRadius = radius * (0.30f + 0.10f * loudness) * pulse
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Hud.CyanGlow, accent.copy(alpha = 0.55f), Color.Transparent),
                center = centre,
                radius = coreRadius * 1.9f,
            ),
            radius = coreRadius * 1.9f,
            center = centre,
        )
        drawCircle(color = Hud.CyanGlow.copy(alpha = 0.9f), radius = coreRadius * 0.55f, center = centre)
        ring(centre, coreRadius, accent, radius * 0.02f)
    }
}

private fun DrawScope.ring(centre: Offset, radius: Float, colour: Color, stroke: Float) {
    drawCircle(color = colour, radius = radius, center = centre, style = Stroke(width = stroke))
}

private fun DrawScope.arc(
    centre: Offset,
    radius: Float,
    stroke: Float,
    colour: Color,
    startAngle: Float,
    sweep: Float,
) {
    drawArc(
        color = colour,
        startAngle = startAngle,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(centre.x - radius, centre.y - radius),
        size = Size(radius * 2, radius * 2),
        style = Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round),
    )
}

private fun DrawScope.segments(
    centre: Offset,
    radius: Float,
    stroke: Float,
    colour: Color,
    count: Int,
    sweep: Float,
) {
    val step = 360f / count
    repeat(count) { index ->
        arc(centre, radius, stroke, colour, index * step, sweep)
    }
}

private fun DrawScope.ticks(
    centre: Offset,
    from: Float,
    to: Float,
    colour: Color,
    stroke: Float,
) {
    val count = 48
    repeat(count) { index ->
        val angle = (2.0 * Math.PI * index / count).toFloat()
        val long = index % 4 == 0
        val start = if (long) from * 0.94f else from
        drawLine(
            color = colour,
            start = Offset(centre.x + cos(angle) * start, centre.y + sin(angle) * start),
            end = Offset(centre.x + cos(angle) * to, centre.y + sin(angle) * to),
            strokeWidth = stroke,
        )
    }
}
