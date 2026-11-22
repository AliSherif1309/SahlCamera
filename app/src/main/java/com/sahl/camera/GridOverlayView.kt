package com.sahl.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class GridOverlayView(context: Context) : View(context) {
    var gridMode: GridMode = GridMode.OFF
        set(value) {
            field = value
            invalidate()
        }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 255, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0E6B4E")
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val focusInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255)
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    private var focusX = -1f
    private var focusY = -1f
    private var focusVisible = false
    private var flashAlpha = 0

    private val hideFocusRunnable = Runnable {
        focusVisible = false
        invalidate()
    }

    fun showFocusRing(x: Float, y: Float) {
        removeCallbacks(hideFocusRunnable)
        focusX = x
        focusY = y
        focusVisible = true
        invalidate()
        postDelayed(hideFocusRunnable, 1300)
    }

    fun triggerShutterBlink() {
        flashAlpha = 160
        invalidate()
        postDelayed({
            flashAlpha = 0
            invalidate()
        }, 70)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        when (gridMode) {
            GridMode.THREE_BY_THREE -> {
                val x1 = w / 3f
                val x2 = 2f * w / 3f
                val y1 = h / 3f
                val y2 = 2f * h / 3f
                canvas.drawLine(x1, 0f, x1, h, gridPaint)
                canvas.drawLine(x2, 0f, x2, h, gridPaint)
                canvas.drawLine(0f, y1, w, y1, gridPaint)
                canvas.drawLine(0f, y2, w, y2, gridPaint)
            }
            GridMode.GOLDEN_RATIO -> {
                val r1 = 0.381966f
                val r2 = 0.618034f
                canvas.drawLine(w * r1, 0f, w * r1, h, gridPaint)
                canvas.drawLine(w * r2, 0f, w * r2, h, gridPaint)
                canvas.drawLine(0f, h * r1, w, h * r1, gridPaint)
                canvas.drawLine(0f, h * r2, w, h * r2, gridPaint)
            }
            GridMode.OFF -> Unit
        }

        if (focusVisible && focusX >= 0f && focusY >= 0f) {
            val radius = 56f
            canvas.drawCircle(focusX, focusY, radius, focusPaint)
            canvas.drawCircle(focusX, focusY, radius - 5f, focusInnerPaint)
        }

        if (flashAlpha > 0) {
            canvas.drawColor(Color.argb(flashAlpha, 255, 255, 255))
        }
    }
}
