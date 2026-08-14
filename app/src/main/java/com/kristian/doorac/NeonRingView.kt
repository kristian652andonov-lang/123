package com.kristian.doorac

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class NeonRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var ringColor: Int = Color.GREEN
        set(value) {
            field = value
            shader = null
            invalidate()
        }

    var strokeWidthDp: Float = 7f
        set(value) {
            field = value
            strokeWidthPx = resources.displayMetrics.density * value
            paint.strokeWidth = strokeWidthPx
            recomputeRect()
        }

    var cornerRadiusDp: Float = 36f
        set(value) {
            field = value
            cornerRadiusPx = resources.displayMetrics.density * value
            invalidate()
        }

    private var strokeWidthPx = resources.displayMetrics.density * strokeWidthDp
    private var cornerRadiusPx = resources.displayMetrics.density * cornerRadiusDp

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.ROUND
    }

    private val rect = RectF()
    private val matrix = Matrix()
    private var shader: SweepGradient? = null
    private var angle = 0f

    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 3200L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeRect()
    }

    private fun recomputeRect() {
        if (width == 0 || height == 0) return
        val inset = strokeWidthPx / 2f
        rect.set(inset, inset, width - inset, height - inset)
        shader = null
        invalidate()
    }

    private fun buildShader() {
        if (width == 0 || height == 0) return
        val cx = width / 2f
        val cy = height / 2f
        val r = Color.red(ringColor)
        val g = Color.green(ringColor)
        val b = Color.blue(ringColor)
        val transparent = Color.argb(0, r, g, b)
        val faint = Color.argb(50, r, g, b)
        val bright = ringColor
        val colors = intArrayOf(transparent, bright, bright, faint, transparent)
        val positions = floatArrayOf(0f, 0.12f, 0.30f, 0.55f, 1f)
        shader = SweepGradient(cx, cy, colors, positions)
        paint.shader = shader
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (shader == null) buildShader()
        matrix.setRotate(angle, width / 2f, height / 2f)
        shader?.setLocalMatrix(matrix)
        canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)
    }
}
