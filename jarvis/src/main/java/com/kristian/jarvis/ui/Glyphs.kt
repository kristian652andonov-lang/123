package com.kristian.jarvis.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap

/** A microphone, drawn rather than imported — the icon pack it lives in is enormous. */
@Composable
fun MicGlyph(colour: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val capsuleWidth = w * 0.34f
        val stroke = w * 0.09f

        drawRoundRect(
            color = colour,
            topLeft = Offset((w - capsuleWidth) / 2f, h * 0.12f),
            size = Size(capsuleWidth, h * 0.46f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(capsuleWidth / 2f),
        )
        drawArc(
            color = colour,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.24f, h * 0.36f),
            size = Size(w * 0.52f, w * 0.52f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(
            color = colour,
            start = Offset(w / 2f, h * 0.74f),
            end = Offset(w / 2f, h * 0.88f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** A paper-plane send arrow. */
@Composable
fun SendGlyph(colour: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.12f, h * 0.18f)
            lineTo(w * 0.90f, h * 0.50f)
            lineTo(w * 0.12f, h * 0.82f)
            lineTo(w * 0.30f, h * 0.50f)
            close()
        }
        drawPath(path, colour)
    }
}

/** A square "stop" mark, used while Jarvis is mid-sentence. */
@Composable
fun StopGlyph(colour: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val side = size.minDimension * 0.44f
        drawRoundRect(
            color = colour,
            topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f),
            size = Size(side, side),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(side * 0.18f),
        )
    }
}
