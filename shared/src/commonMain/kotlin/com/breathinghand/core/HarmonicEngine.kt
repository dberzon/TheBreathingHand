package com.breathinghand.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * Gesture Grammar + Harmonic Map v0.1 ("Stable Gravity Map")
 * Allocation-free hot path.
 */
data class HarmonicState(
    var root: Int = 0,        // committed sector (0..11)
    var quality: Int = 2,     // spreadBand: 0=RED,1=GREEN,2=BLUE
    var density: Int = 0      // fingerCount (0..4) for v0.1
) {
    var previewRoot: Int = 0  // sector under hand (visual only)
    var triad: Int = GestureAnalyzerV01.TRIAD_FAN
    var seventh: Int = GestureAnalyzerV01.SEVENTH_COMPACT
}

class HarmonicEngine(
    private val sectorCount: Int = MusicalConstants.SECTOR_COUNT,
    private val r1: Float = MusicalConstants.BASE_RADIUS_INNER,
    private val r2: Float = MusicalConstants.BASE_RADIUS_OUTER,
    // Fix: Injectable Hysteresis for density scaling
    private val hysteresis: Float = InputTuning.RADIUS_HYSTERESIS_PX
) {
    val state = HarmonicState()

    private var latchedPreviewSector = 0
    private var committedSector = 0
    private var latchedBand = 2 // start BLUE

    var didCommitRootThisUpdate: Boolean = false
        private set

    fun update(
        angleRad: Float,
        spreadPx: Float,
        fingerCount: Int,
        triadArchetype: Int,
        seventhArchetype: Int,
        nowMs: Long
    ): Boolean {
        didCommitRootThisUpdate = false

        val prevRoot = state.root
        val prevBand = state.quality
        val prevFc = state.density
        val prevTriad = state.triad
        val prevSev = state.seventh

        // 0) Finger semantics (v0.1)
        val fc = fingerCount.coerceIn(0, 4)
        state.density = fc
        state.triad = if (fc >= 3) triadArchetype else GestureAnalyzerV01.TRIAD_NONE
        state.seventh = if (fc >= 4) seventhArchetype else GestureAnalyzerV01.SEVENTH_NONE

        // 1) Preview Root
        val twoPi = 2f * PI.toFloat()
        var angleNorm = angleRad % twoPi
        if (angleNorm < 0f) angleNorm += twoPi

        val sectorWidth = twoPi / sectorCount.toFloat()
        val maxIndex = sectorCount - 1
        val candidate = floor(angleNorm / sectorWidth).toInt().coerceIn(0, maxIndex)

        latchedPreviewSector = latchSector(angleNorm, latchedPreviewSector, candidate, sectorWidth)
        state.previewRoot = latchedPreviewSector

        // 2) SpreadBand (0=RED,1=GREEN,2=BLUE) with scalable hysteresis
        val hys = hysteresis

        if (latchedBand == 0) { // RED
            if (spreadPx > r1 + hys) latchedBand = 1
        } else if (latchedBand == 1) { // GREEN
            if (spreadPx < r1 - hys) latchedBand = 0
            else if (spreadPx > r2 + hys) latchedBand = 2
        } else { // BLUE
            if (spreadPx < r2 - hys) latchedBand = 1
        }

        state.quality = latchedBand

        // 3) Root COMMIT policy: only in RED clutch
        if (latchedBand == 0 && committedSector != latchedPreviewSector) {
            committedSector = latchedPreviewSector
            didCommitRootThisUpdate = true
        }
        state.root = committedSector

        return (state.root != prevRoot) ||
                (state.quality != prevBand) ||
                (state.density != prevFc) ||
                (state.triad != prevTriad) ||
                (state.seventh != prevSev)
    }

    private fun latchSector(angleNorm: Float, current: Int, candidate: Int, sectorWidth: Float): Int {
        if (candidate == current) return current

        val halfWidth = sectorWidth * 0.5f
        val requestedMargin = InputTuning.ANGLE_HYSTERESIS_DEG * (PI.toFloat() / 180f)
        val margin = min(requestedMargin, halfWidth - 0.0001f)

        val candCenter = (candidate + 0.5f) * sectorWidth

        var dist = abs(angleNorm - candCenter)
        val pi = PI.toFloat()
        if (dist > pi) dist = (2f * pi) - dist

        return if (dist <= (halfWidth - margin)) candidate else current
    }

    fun getCurrentColor(): Int = when (state.quality) {
        0 -> -65536      // RED
        1 -> -16711936   // GREEN
        else -> -16776961 // BLUE
    }
}