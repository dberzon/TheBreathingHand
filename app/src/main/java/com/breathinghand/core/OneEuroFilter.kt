package com.breathinghand.core

import kotlin.math.PI
import kotlin.math.abs

class OneEuroFilter(
    // UPDATED DEFAULTS based on Chapter 3C
    private val minCutoff: Float = InputTuning.FILTER_MIN_CUTOFF,
    private val beta: Float = InputTuning.FILTER_BETA,
    private val dCutoff: Float = 1.0f
) {
    // ... (Rest of the file remains exactly the same) ...
    private var xPrev: Float? = null
    private var dxPrev: Float? = null
    private var tPrev: Float? = null

    fun reset() {
        xPrev = null
        dxPrev = null
        tPrev = null
    }

    fun filter(x: Float, t: Float): Float {
        if (tPrev == null) {
            tPrev = t
            xPrev = x
            dxPrev = 0f
            return x
        }

        val dt = t - tPrev!!
        if (dt <= 0f) return xPrev!!

        val alphaD = smoothingFactor(dt, dCutoff)
        val dx = (x - xPrev!!) / dt
        val dxHat = exponentialSmoothing(alphaD, dx, dxPrev!!)

        val cutoff = minCutoff + beta * abs(dxHat)
        val alpha = smoothingFactor(dt, cutoff)
        val xHat = exponentialSmoothing(alpha, x, xPrev!!)

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