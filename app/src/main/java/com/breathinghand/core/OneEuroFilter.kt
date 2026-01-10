package com.breathinghand.core

import kotlin.math.PI
import kotlin.math.abs

class OneEuroFilter(
    private val minCutoff: Float = InputTuning.FILTER_MIN_CUTOFF,
    private val beta: Float = InputTuning.FILTER_BETA,
    private val dCutoff: Float = 1.0f
) {
    // Zero-allocation: no boxed Floats in the hot path.
    private var hasPrev = false
    private var xPrev = 0f
    private var dxPrev = 0f
    private var tPrev = 0f

    fun reset() {
        hasPrev = false
        xPrev = 0f
        dxPrev = 0f
        tPrev = 0f
    }

    fun filter(x: Float, t: Float): Float {
        if (!hasPrev) {
            hasPrev = true
            tPrev = t
            xPrev = x
            dxPrev = 0f
            return x
        }

        val dt = t - tPrev
        if (dt <= 0f) return xPrev

        val alphaD = smoothingFactor(dt, dCutoff)
        val dx = (x - xPrev) / dt
        val dxHat = exponentialSmoothing(alphaD, dx, dxPrev)

        val cutoff = minCutoff + beta * abs(dxHat)
        val alpha = smoothingFactor(dt, cutoff)
        val xHat = exponentialSmoothing(alpha, x, xPrev)

        xPrev = xHat
        dxPrev = dxHat
        tPrev = t
        return xHat
    }

    private fun smoothingFactor(dt: Float, cutoff: Float): Float {
        val r = 2 * PI * cutoff * dt
        return (r / (r + 1)).toFloat()
    }

    private fun exponentialSmoothing(alpha: Float, current: Float, previous: Float): Float {
        return alpha * current + (1 - alpha) * previous
    }
}