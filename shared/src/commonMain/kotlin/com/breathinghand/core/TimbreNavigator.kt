package com.breathinghand.core

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Translationally invariant gesture layer.
 * Zero-allocation version using MutableGesture.
 */
class TimbreNavigator(
    private val maxPointerId: Int = 64,
    private val deadzonePx: Float = 6f,
    private val rangeXPx: Float = 220f,
    private val rangeYPx: Float = 220f
) {
    private val originX = FloatArray(maxPointerId) { Float.NaN }
    private val originY = FloatArray(maxPointerId) { Float.NaN }

    // Reusable mutable holder to avoid allocation per compute()
    data class MutableGesture(var dxNorm: Float = 0f, var dyNorm: Float = 0f, var distNorm: Float = 0f)

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

    /**
     * Clear all tracked origins. Call on pause/reset to prevent stale state.
     */
    fun resetAll() {
        for (i in 0 until maxPointerId) {
            originX[i] = Float.NaN
            originY[i] = Float.NaN
        }
    }

    /**
     * Computes gesture data into the provided MutableGesture.
     * Returns true if a valid gesture was computed (origin exists), false otherwise.
     */
    fun compute(pointerId: Int, x: Float, y: Float, outGesture: MutableGesture): Boolean {
        if (pointerId !in 0 until maxPointerId) return false

        val ox = originX[pointerId]
        val oy = originY[pointerId]
        if (ox.isNaN() || oy.isNaN()) {
            outGesture.dxNorm = 0f
            outGesture.dyNorm = 0f
            outGesture.distNorm = 0f
            return false
        }

        var dx = x - ox
        var dy = y - oy

        // Circular deadzone (instead of square) for consistent feel in all directions
        val rawDist = sqrt(dx * dx + dy * dy)
        if (rawDist < deadzonePx) {
            dx = 0f
            dy = 0f
        }
        val dxNorm = (dx / rangeXPx).coerceIn(-1f, 1f)
        val dyNorm = (dy / rangeYPx).coerceIn(-1f, 1f)

        // Euclidean distance in normalized space, clamped to 0..1
        val dist = sqrt(dxNorm * dxNorm + dyNorm * dyNorm).coerceIn(0f, 1f)

        outGesture.dxNorm = dxNorm
        outGesture.dyNorm = dyNorm
        outGesture.distNorm = dist
        return true
    }
}