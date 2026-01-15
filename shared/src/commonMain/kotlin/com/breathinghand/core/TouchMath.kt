package com.breathinghand.core

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Platform-agnostic geometry extraction from TouchFrame.
 *
 * NOTE: Single-finger radius uses distance from screen center,
 * while multi-finger uses average spread from centroid × multiplier.
 * This discontinuity is intentional for different interaction modes,
 * but may cause a jump when transitioning.
 */
object TouchMath {
    fun reset() {
        // No-op: TouchMath is stateless.
        // Kept for lifecycle symmetry.
    }

    /**
     * Platform-agnostic geometry extraction.
     * Uses slot arrays from TouchFrame (index == slot 0..MAX_VOICES-1).
     * No allocations.
     *
     * IMPORTANT: Treat a slot as active only if:
     * - pointerId is valid AND
     * - slot is NOT flagged F_UP
     *
     * This matches the deferred invalidation snapshot model (pointerId may persist with F_UP set).
     */
    fun update(frame: TouchFrame, cx: Float, cy: Float, outResult: MutableTouchPolar) {
        val max = MusicalConstants.MAX_VOICES

        // 0) Count active pointers (excluding F_UP slots)
        var n = 0
        for (i in 0 until max) {
            val pid = frame.pointerIds[i]
            if (pid == TouchFrame.INVALID_ID) continue
            if ((frame.flags[i] and TouchFrame.F_UP) != 0) continue
            n++
        }

        if (n == 0) {
            outResult.isActive = false
            outResult.pointerCount = 0
            outResult.radius = 0f
            outResult.angle = 0f
            outResult.centerX = 0f
            outResult.centerY = 0f
            return
        }

        // 1) Centroid (average position), excluding F_UP slots
        var sumX = 0f
        var sumY = 0f
        for (i in 0 until max) {
            val pid = frame.pointerIds[i]
            if (pid == TouchFrame.INVALID_ID) continue
            if ((frame.flags[i] and TouchFrame.F_UP) != 0) continue
            sumX += frame.x[i]
            sumY += frame.y[i]
        }
        val handX = sumX / n
        val handY = sumY / n

        // 2) Radius
        var sumSpread = 0f
        if (n > 1) {
            for (i in 0 until max) {
                val pid = frame.pointerIds[i]
                if (pid == TouchFrame.INVALID_ID) continue
                if ((frame.flags[i] and TouchFrame.F_UP) != 0) continue
                val dx = frame.x[i] - handX
                val dy = frame.y[i] - handY
                sumSpread += sqrt(dx * dx + dy * dy)
            }
            outResult.radius = (sumSpread / n) * MusicalConstants.SPREAD_RADIUS_MULTIPLIER
        } else {
            val dx = handX - cx
            val dy = handY - cy
            outResult.radius = sqrt(dx * dx + dy * dy)
        }

        // 3) Angle (centroid relative to screen center)
        val dxCenter = handX - cx
        val dyCenter = handY - cy
        // 0 degrees is UP (12 o'clock)
        outResult.angle = atan2(dxCenter, -dyCenter)

        // 4) Populate result
        outResult.centerX = handX
        outResult.centerY = handY
        outResult.isActive = true
        outResult.pointerCount = n
    }
}
