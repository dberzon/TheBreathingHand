package com.breathinghand.core

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Gesture Grammar v0.1: archetype classifier.
 *
 * IMPORTANT:
 * - Evaluated ONLY on semantic events (landing / add-finger).
 * - Outputs are LATCHED and stable between semantic events.
 * - Triad archetype is determined when we *reach* 3 fingers (or land with >=3).
 * - Adding the 4th finger does NOT re-evaluate the triad (only adds the 7th).
 *
 * Allocation-free: uses fixed scratch arrays.
 */
class GestureAnalyzerV01(
    private val r1Px: Float,
    private val r2Px: Float
) {

    companion object {
        // Semantic events
        const val EVENT_NONE = 0
        const val EVENT_LANDING = 1
        const val EVENT_ADD_FINGER = 2

        // Triad archetypes (3 fingers)
        const val TRIAD_NONE = 0
        const val TRIAD_FAN = 1       // +M3
        const val TRIAD_STRETCH = 2   // +m3
        const val TRIAD_CLUSTER = 3   // sus4 (v0.1)

        // Seventh archetypes (4 fingers)
        const val SEVENTH_NONE = 0
        const val SEVENTH_COMPACT = 1 // +M7
        const val SEVENTH_WIDE = 2    // +m7
    }

    // Latched outputs
    var latchedTriad: Int = TRIAD_FAN
        private set

    var latchedSeventh: Int = SEVENTH_COMPACT
        private set

    /**
     * Transition Window re-touch: preserve semantic layers verbatim.
     */
    fun seedFromState(state: HarmonicState) {
        latchedTriad = state.triad
        latchedSeventh = state.seventh
    }

    // Scratch: active slots (max 5)
    private val activeSlots = IntArray(MusicalConstants.MAX_VOICES)

    private fun isSlotActive(frame: TouchFrame, i: Int): Boolean {
        val pid = frame.pointerIds[i]
        if (pid == TouchFrame.INVALID_ID) return false
        return (frame.flags[i] and TouchFrame.F_UP) == 0
    }

    /**
     * Update archetypes ONLY when a semantic event occurs.
     *
     * @param frame Current TouchFrame (slot arrays).
     * @param pointerCount Active pointer count (already computed).
     * @param spreadPx Mean spread (TouchMath.radius after smoothing is OK).
     * @param event One of EVENT_*.
     */
    fun onSemanticEvent(frame: TouchFrame, pointerCount: Int, spreadPx: Float, event: Int) {
        if (event == EVENT_NONE) return

        // Landing resets to defaults (stable baseline).
        if (event == EVENT_LANDING) {
            latchedTriad = TRIAD_FAN
            latchedSeventh = SEVENTH_COMPACT
        }

        // Build active slot list (allocation-free)
        var n = 0
        for (i in 0 until MusicalConstants.MAX_VOICES) {
            if (isSlotActive(frame, i)) {
                activeSlots[n] = i
                n++
            }
        }

        val pc = pointerCount.coerceIn(0, 5)

        // TRIAD: evaluate on LANDING with >=3 fingers, or on ADD when the count becomes 3.
        val shouldEvalTriad =
            (pc >= 3) && (n >= 3) && (event == EVENT_LANDING || pc == 3)

        if (shouldEvalTriad) {
            // Pass only the two arguments the function is built to receive
            latchedTriad = classifyTriad(frame, spreadPx)
        }


        // SEVENTH: evaluate on LANDING with >=4, or on ADD when the count becomes 4.
        val shouldEvalSeventh =
            (pc >= 4) && (event == EVENT_LANDING || pc == 4)

        if (shouldEvalSeventh) {
            latchedSeventh = classifySeventh(spreadPx)
        }
    }

    /**
     * Classify triad using all active slots (order-independent).
     * Computes pairwise distances for geometric analysis.
     */
    private fun classifyTriad(frame: TouchFrame, spreadPx: Float): Int {
        // 1) CLUSTER: tight spread relative to minimum threshold.
        val clusterThresh = r1Px * InputTuning.GRIP_CLUSTER_SPREAD_FACTOR
        if (spreadPx < clusterThresh) return TRIAD_CLUSTER

        // Collect all pairwise distances (order-independent)
        val distances = computeAllPairwiseDistances(frame)
        if (distances.count == 0) return TRIAD_FAN

        val dMax = distances.max
        val dMin = distances.min
        val dMean = distances.sum / distances.count

        // 2) STRETCH: elongated triangle.
        if (dMin > 0.0001f) {
            val pairRatio = dMax / dMin
            if (pairRatio >= InputTuning.GRIP_STRETCH_PAIR_RATIO) return TRIAD_STRETCH
        }

        if (dMean > 0.0001f) {
            val meanRatio = dMax / dMean
            if (meanRatio >= InputTuning.GRIP_STRETCH_MEAN_RATIO) return TRIAD_STRETCH
        }

        // 3) FAN: default.
        return TRIAD_FAN
    }

    // Scratch structure for pairwise distance computation (zero-allocation)
    private class DistanceStats {
        var min: Float = Float.MAX_VALUE
        var max: Float = 0f
        var sum: Float = 0f
        var count: Int = 0

        fun reset() {
            min = Float.MAX_VALUE
            max = 0f
            sum = 0f
            count = 0
        }

        fun add(d: Float) {
            if (d < min) min = d
            if (d > max) max = d
            sum += d
            count++
        }
    }

    private val distanceStats = DistanceStats()

    private fun computeAllPairwiseDistances(frame: TouchFrame): DistanceStats {
        distanceStats.reset()
        for (i in 0 until MusicalConstants.MAX_VOICES) {
            if (frame.pointerIds[i] == TouchFrame.INVALID_ID) continue
            for (j in i + 1 until MusicalConstants.MAX_VOICES) {
                if (frame.pointerIds[j] == TouchFrame.INVALID_ID) continue
                val d = dist(frame, i, j)
                distanceStats.add(d)
            }
        }
        return distanceStats
    }

    private fun classifySeventh(spreadPx: Float): Int {
        // Wide spread relative to BLUE radius -> minor 7th.
        val wideThresh = r2Px * InputTuning.GRIP_WIDE_SPREAD_FACTOR
        return if (spreadPx >= wideThresh) SEVENTH_WIDE else SEVENTH_COMPACT
    }

    private fun dist(frame: TouchFrame, a: Int, b: Int): Float {
        val dx = frame.x[a] - frame.x[b]
        val dy = frame.y[a] - frame.y[b]
        return sqrt(dx * dx + dy * dy)
    }
}