package com.breathinghand.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Translationally invariant gesture layer.
 * Touch-down defines an origin per pointerId.
 *
 * Outputs:
 * - dxNorm in [-1..+1]
 * - dyNorm in [-1..+1]
 * - distNorm in [0..1]
 *
 * No allocations in hot path.
 */
class TimbreNavigator(
    private val maxPointerId: Int = 64,
    private val deadzonePx: Float = 6f,
    private val rangeXPx: Float = 220f,
    private val rangeYPx: Float = 220f
) {
    private val originX = FloatArray(maxPointerId) { Float.NaN }
    private val originY = FloatArray(maxPointerId) { Float.NaN }

    data class Gesture(val dxNorm: Float, val dyNorm: Float, val distNorm: Float) {
        companion object { val ZERO = Gesture(0f, 0f, 0f) }
    }

    fun onPointerDown(pointerId: Int, x: Float, y: Float) {
        if (pointerId !in 0 until maxPointerId) return
        originX[pointerId] = x
        originY[pointerId] = y
    }

    fun onPointerUp(pointerId: Int) {
        if (pointerId !in 0 until maxPointerId) return
        originX[pointerId] = Float.NaN
        originY[pointerId] = Float.NaN
    }

    fun compute(pointerId: Int, x: Float, y: Float): Gesture {
        if (pointerId !in 0 until maxPointerId) return Gesture.ZERO

        val ox = originX[pointerId]
        val oy = originY[pointerId]
        if (ox.isNaN() || oy.isNaN()) return Gesture.ZERO

        var dx = x - ox
        var dy = y - oy

        if (abs(dx) < deadzonePx) dx = 0f
        if (abs(dy) < deadzonePx) dy = 0f

        val dxNorm = (dx / rangeXPx).coerceIn(-1f, 1f)
        val dyNorm = (dy / rangeYPx).coerceIn(-1f, 1f)

        // Euclidean distance in normalized space, clamped to 0..1
        val dist = sqrt(dxNorm * dxNorm + dyNorm * dyNorm).coerceIn(0f, 1f)

        return Gesture(dxNorm, dyNorm, dist)
    }
}
