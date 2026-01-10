package com.breathinghand.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val NOTE_NAMES = arrayOf("C","C#","D","D#","E","F","F#","G","G#","A","A#","B")

private fun midiToName(m: Int): String {
    val pc = (m % 12 + 12) % 12
    val octave = m / 12 - 1
    return "${NOTE_NAMES[pc]}$octave"
}

class HarmonicOverlayView(context: Context, private val engine: HarmonicEngine) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 50
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) * 0.4f

        val s = engine.state
        val unstable = s.harmonicInstability >= MusicalConstants.INSTABILITY_THRESHOLD
        val color = if (unstable) -65536 else -16776961
        paint.color = color
        fillPaint.color = color

        // 1) (Removed) Breathing Circle — replaced by chord display

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

        // 3) Active functional sector indicator (thick)
        val activeAngle = (s.functionSector + 0.5) * sectorRad
        paint.strokeWidth = 12f
        canvas.drawLine(
            cx, cy,
            cx + r * sin(activeAngle).toFloat(),
            cy - r * cos(activeAngle).toFloat(),
            paint
        )
        paint.strokeWidth = 5f // reset

        // 4) Chord display — show real-time chord notes computed from HarmonicFieldMapV01
        val roleNotes = IntArray(4)
        val roleCount = HarmonicFieldMapV01.fillRoleNotes(s, roleNotes)
        if (roleCount > 0) {
            val names = mutableListOf<String>()
            for (i in 0 until roleCount) {
                val m = roleNotes[i]
                names.add(midiToName(m))
            }

            val quality = when {
                s.harmonicInstability > MusicalConstants.INSTABILITY_THRESHOLD -> "dim"
                s.triad == GestureAnalyzerV01.TRIAD_STRETCH -> "min"
                s.triad == GestureAnalyzerV01.TRIAD_CLUSTER -> "sus4"
                else -> "maj"
            }

            val chordText = "Chord: ${names.joinToString(" ")} ($quality)"

            // Background for readability
            fillPaint.alpha = 160
            val padding = 12f
            val textSize = 40f
            paint.textSize = textSize
            val textWidth = paint.measureText(chordText)
            val left = cx - textWidth / 2 - padding
            val top = cy - r - 24f - textSize
            val right = cx + textWidth / 2 + padding
            val bottom = top + textSize + padding * 1.5f
            val bgColor = if (unstable) -65536 else -16776961
            fillPaint.color = bgColor
            canvas.drawRoundRect(left, top, right, bottom, 8f, 8f, fillPaint)

            paint.style = Paint.Style.FILL
            paint.color = -1 // white text
            canvas.drawText(chordText, cx - textWidth / 2, top + textSize, paint)
            paint.style = Paint.Style.STROKE
            paint.color = color // restore
        }
    }
}
