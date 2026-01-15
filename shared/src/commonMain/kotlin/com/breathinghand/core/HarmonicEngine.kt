package com.breathinghand.core

import kotlin.math.PI
import kotlin.math.abs

class HarmonicEngine {
    // Single Source of Truth from Core_Data_Structures.kt
    val state: HarmonicState = HarmonicState()

    private var hasTouch: Boolean = false
    private var candidateSector: Int = 0
    private var dwellStartMs: Long = 0L

    private var lastAngleRad: Float = 0f
    private var lastAngleTimeMs: Long = 0L

    fun onAllFingersLift(nowMs: Long) {
        hasTouch = false
        lastAngleTimeMs = 0L
    }

    fun beginFromRestoredState(nowMs: Long, restored: HarmonicState, angleRad: Float) {
        state.copyFrom(restored)
        hasTouch = true
        candidateSector = state.functionSector
        dwellStartMs = nowMs
        lastAngleRad = angleRad
        lastAngleTimeMs = 0L
    }

    fun update(
        nowMs: Long,
        angleRad: Float,
        spreadPx: Float,
        centerYNorm: Float,
        fingerCount: Int,
        triadArchetype: Int,
        seventhArchetype: Int
    ): Boolean {
        val prevSector = state.functionSector
        val prevPc = state.rootPc
        val prevInst = state.harmonicInstability
        val prevFc = state.fingerCount
        val prevTriad = state.triad
        val prevSev = state.seventh

        if (!hasTouch) {
            // LANDING SEED (critical):
            // Seed from the first valid angle immediately to avoid a "bias chord" flash
            // that only corrects after dwell. Vertical bias is gravity, not an audible
            // wrong sector on attack.
            val seedSector = quantizeAngleToSector(angleRad)
            state.functionSector = seedSector
            state.rootPc = sectorToPitchClass(seedSector)
            candidateSector = seedSector
            dwellStartMs = nowMs
            // Seed angular velocity integrator so first-frame velocity is 0.
            lastAngleRad = angleRad
            lastAngleTimeMs = nowMs
            hasTouch = true
        }

        val fc = fingerCount.coerceIn(0, 4)
        state.fingerCount = fc
        state.triad = if (fc >= 3) triadArchetype else GestureAnalyzerV01.TRIAD_NONE
        state.seventh = if (fc >= 4) seventhArchetype else GestureAnalyzerV01.SEVENTH_NONE

        state.harmonicInstability = spreadToInstability(spreadPx)

        val rawSector = quantizeAngleToSector(angleRad)
        val hysteresisSector = applyAngularHysteresis(state.functionSector, rawSector, angleRad)

        if (hysteresisSector != candidateSector) {
            candidateSector = hysteresisSector
            dwellStartMs = nowMs
        }

        val angVel = computeAngularVelocity(nowMs, angleRad)
        val dwellMs = nowMs - dwellStartMs
        val shouldAdvance =
            dwellMs >= MusicalConstants.DWELL_THRESHOLD_MS ||
                    abs(angVel) >= MusicalConstants.ANGULAR_SNAP_THRESHOLD_RAD_PER_SEC

        if (shouldAdvance && candidateSector != state.functionSector) {
            state.functionSector = candidateSector
            state.rootPc = sectorToPitchClass(candidateSector)
        }

        return prevSector != state.functionSector ||
                prevPc != state.rootPc ||
                prevInst != state.harmonicInstability ||
                prevFc != state.fingerCount ||
                prevTriad != state.triad ||
                prevSev != state.seventh
    }

    private fun computeAngularVelocity(nowMs: Long, angleRad: Float): Float {
        val t0 = lastAngleTimeMs
        lastAngleTimeMs = nowMs
        val a0 = lastAngleRad
        lastAngleRad = angleRad

        if (t0 == 0L) return 0f
        val dt = (nowMs - t0).toFloat() / 1000f
        if (dt <= 0f) return 0f

        val da = shortestAngleDelta(a0, angleRad)
        return da / dt
    }

    private fun shortestAngleDelta(a0: Float, a1: Float): Float {
        var d = a1 - a0
        val twoPi = (2.0 * PI).toFloat()
        val pi = PI.toFloat()
        while (d > pi) d -= twoPi
        while (d < -pi) d += twoPi
        return d
    }

    private fun quantizeAngleToSector(angleRad: Float): Int {
        val twoPi = (2.0 * PI).toFloat()
        var a = angleRad % twoPi
        if (a < 0f) a += twoPi

        val sectorFloat = a / twoPi * 12f
        var s = sectorFloat.toInt()
        if (s < 0) s = 0
        if (s > 11) s = 11
        return s
    }

    private fun applyAngularHysteresis(currentSector: Int, rawSector: Int, angleRad: Float): Int {
        if (rawSector == currentSector) return rawSector

        val margin = MusicalConstants.SECTOR_HYSTERESIS_RAD
        val twoPi = (2.0 * PI).toFloat()
        val sectorWidth = twoPi / 12f

        val rawCenter = (rawSector + 0.5f) * sectorWidth
        val delta = abs(shortestAngleDelta(rawCenter, angleRad))
        return if (delta < (sectorWidth * 0.5f - margin)) rawSector else currentSector
    }

    private fun sectorToPitchClass(sector: Int): Int {
        return (sector * 7) % 12
    }

    private fun spreadToInstability(spreadPx: Float): Float {
        val min = MusicalConstants.SPREAD_MIN_PX
        val max = MusicalConstants.SPREAD_MAX_PX
        if (max <= min) return 0f
        val t = ((spreadPx - min) / (max - min)).coerceIn(0f, 1f)
        return 1f - t
    }

    private fun initialBiasSector(centerYNorm: Float): Int {
        return when {
            centerYNorm < MusicalConstants.BIAS_UPPER_CUTOFF_NORM -> MusicalConstants.BIAS_SECTOR_UPPER
            centerYNorm > MusicalConstants.BIAS_LOWER_CUTOFF_NORM -> MusicalConstants.BIAS_SECTOR_LOWER
            else -> MusicalConstants.BIAS_SECTOR_CENTER
        }
    }
}