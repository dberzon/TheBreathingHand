package com.breathinghand.core

import android.view.MotionEvent
import kotlin.math.atan2
import kotlin.math.sqrt

object TouchMath {
    fun reset() {
        // No-op: TouchMath is stateless. Kept for lifecycle symmetry.
    }

    fun update(event: MotionEvent, cx: Float, cy: Float, outResult: MutableTouchPolar) {
        val count = event.pointerCount
        val n = count.coerceAtMost(MusicalConstants.MAX_VOICES) // Track up to MAX_VOICES fingers

        if (n == 0) {
            outResult.isActive = false
            return
        }

        // 1. Calculate Centroid (Average Position)
        var sumX = 0f
        var sumY = 0f
        for (i in 0 until n) {
            sumX += event.getX(i)
            sumY += event.getY(i)
        }
        val handX = sumX / n
        val handY = sumY / n

        // 2. Calculate Radius (Average Spread from Centroid)
        // This makes the hand feel like it "breathes" as a whole
        var sumSpread = 0f
        if (n > 1) {
            for (i in 0 until n) {
                val dx = event.getX(i) - handX
                val dy = event.getY(i) - handY
                sumSpread += sqrt(dx * dx + dy * dy)
            }
            // Multiplier tunes the feel so a comfortable spread hits the thresholds
            outResult.radius = (sumSpread / n) * MusicalConstants.SPREAD_RADIUS_MULTIPLIER
        } else {
            // Single finger: Distance from screen center
            val dx = handX - cx
            val dy = handY - cy
            outResult.radius = sqrt(dx * dx + dy * dy)
        }

        // 3. Calculate Angle (Centroid relative to Screen Center)
        val dxCenter = handX - cx
        val dyCenter = handY - cy

        // ROTATION FIX: atan2(dx, -dy) ensures 0 degrees is UP (12 o'clock)
        outResult.angle = atan2(dxCenter, -dyCenter)

        outResult.isActive = true
        outResult.pointerCount = n
    }
}