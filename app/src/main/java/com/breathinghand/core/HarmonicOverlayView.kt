package com.breathinghand.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.*

class HarmonicOverlayView(context: Context, private val engine: HarmonicEngine) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 5f; style = Paint.Style.STROKE }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; alpha = 50 }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) * 0.4f

        // Get Color from Engine Helper
        val color = engine.getCurrentColor()
        paint.color = color
        fillPaint.color = color

        // 1. Draw "Breathing" Circle
        canvas.drawCircle(cx, cy, r, paint)
        canvas.drawCircle(cx, cy, r, fillPaint)

        // 2. Draw Sectors
        val sectorCount = MusicalConstants.SECTOR_COUNT
        val sectorRad = (2.0 * Math.PI / sectorCount.toDouble())
        for (i in 0 until sectorCount) {
            val a = i * sectorRad
            canvas.drawLine(cx, cy, cx + r * sin(a).toFloat(), cy - r * cos(a).toFloat(), paint)
        }

        // 3. Draw Active Sector Indicator (Thick Line)
        val activeAngle = (engine.state.root + 0.5) * sectorRad
        paint.strokeWidth = 12f
        canvas.drawLine(cx, cy, cx + r * sin(activeAngle).toFloat(), cy - r * cos(activeAngle).toFloat(), paint)
        paint.strokeWidth = 5f // Reset
    }
}