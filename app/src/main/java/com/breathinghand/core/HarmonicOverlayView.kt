package com.breathinghand.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class HarmonicOverlayView(context: Context, private val engine: HarmonicEngine) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 50
    }

    private val previewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 4f
        style = Paint.Style.STROKE
        alpha = 120
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) * 0.4f

        val color = engine.getCurrentColor()
        paint.color = color
        fillPaint.color = color
        previewPaint.color = color

        // 1) Breathing Circle
        canvas.drawCircle(cx, cy, r, paint)
        canvas.drawCircle(cx, cy, r, fillPaint)

        // 2) Sectors
        val sectorCount = MusicalConstants.SECTOR_COUNT
        val sectorRad = (2.0 * Math.PI / sectorCount.toDouble())
        for (i in 0 until sectorCount) {
            val a = i * sectorRad
            canvas.drawLine(
                cx, cy,
                cx + r * sin(a).toFloat(),
                cy - r * cos(a).toFloat(),
                paint
            )
        }

        // 3) Preview root indicator (thin)
        val s = engine.state
        if (s.previewRoot != s.root) {
            val previewAngle = (s.previewRoot + 0.5) * sectorRad
            canvas.drawLine(
                cx, cy,
                cx + r * sin(previewAngle).toFloat(),
                cy - r * cos(previewAngle).toFloat(),
                previewPaint
            )
        }

        // 4) Committed root indicator (thick)
        val activeAngle = (s.root + 0.5) * sectorRad
        paint.strokeWidth = 12f
        canvas.drawLine(
            cx, cy,
            cx + r * sin(activeAngle).toFloat(),
            cy - r * cos(activeAngle).toFloat(),
            paint
        )
        paint.strokeWidth = 5f // reset
    }
}
