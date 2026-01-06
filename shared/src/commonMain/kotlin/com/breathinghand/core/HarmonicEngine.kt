package com.breathinghand.core

import kotlin.math.*

data class HarmonicState(var root: Int = 0, var quality: Int = 1, var density: Int = 3)

class HarmonicEngine(
    private val sectorCount: Int = MusicalConstants.SECTOR_COUNT,
    private val r1: Float = MusicalConstants.BASE_RADIUS_INNER,
    private val r2: Float = MusicalConstants.BASE_RADIUS_OUTER
) {
    val state = HarmonicState()

    // Internal State for Hysteresis
    private var latchedSector = 0
    private var latchedQuality = 1 // Start at Neutral/Blue

    // Internal State for Temporal Logic (Time Gates)
    private var lastFingerChangeTime = 0L
    private var stableFingerCount = 0
    private var rawFingerCount = 0

    fun update(angleRad: Float, radius: Float, currentFingerCount: Int, nowMs: Long): Boolean {
        val now = nowMs
        val prevRoot = state.root
        val prevQual = state.quality
        val prevDens = state.density

        // --- 1. TEMPORAL LOGIC (The State Machine) ---

        if (currentFingerCount != rawFingerCount) {
            // Finger count just changed. Start the timer.
            lastFingerChangeTime = now
            rawFingerCount = currentFingerCount
        }

        val timeSinceChange = now - lastFingerChangeTime

        // LOGIC: "The Flam" & "The Twitch" Handler
        if (currentFingerCount > stableFingerCount) {
            // ATTACK (Finger Count Increasing)
            // Wait for "Assembly Window" (80ms) to ensure all fingers land
            if (timeSinceChange >= InputTuning.CHORD_ASSEMBLY_MS) {
                stableFingerCount = currentFingerCount
            }
        } else if (currentFingerCount < stableFingerCount) {
            // RELEASE / GLITCH (Finger Count Decreasing)
            // Wait for "Retention Window" (250ms) before accepting the drop
            // This ignores accidental lifts ("The Twitch")
            if (timeSinceChange >= InputTuning.CHORD_RETENTION_MS) {
                stableFingerCount = currentFingerCount
            }
        }

        // Clamp density for musical mapping (3 to 5 voices)
        state.density = stableFingerCount.coerceIn(3, 5)


        // --- 2. SPATIAL LOGIC (Hysteresis) ---

        // A. Angular Latching (Root)
        val angleNorm = (angleRad + 2 * PI).rem(2 * PI).toFloat()
        val sectorWidth = (2 * PI / sectorCount).toFloat()
        val maxIndex = sectorCount - 1
        val candidate = floor(angleNorm / sectorWidth).toInt().coerceIn(0, maxIndex)

        // Use new scientific constant: 2.0 degrees
        latchedSector = latchSector(angleNorm, latchedSector, candidate, sectorWidth)
        state.root = latchedSector


        // B. Radial Hysteresis (Quality)
        // Use new scientific constant: 100px buffer
        // Logic: Only switch if we cross the threshold +/- buffer
        val hys = InputTuning.RADIUS_HYSTERESIS_PX

        // Define Thresholds
        val minorThresh = r1  // Boundary between Red/Blue
        val majorThresh = r2  // Boundary between Blue/Green

        if (latchedQuality == 0) { // Currently RED
            if (radius > minorThresh + hys) latchedQuality = 1
        } else if (latchedQuality == 1) { // Currently BLUE
            if (radius < minorThresh - hys) latchedQuality = 0
            else if (radius > majorThresh + hys) latchedQuality = 2
        } else { // Currently GREEN
            if (radius < majorThresh - hys) latchedQuality = 1
        }

        state.quality = latchedQuality

        // Return TRUE only if the "Musical State" changed (Input -> Audio)
        return (state.root != prevRoot) || (state.quality != prevQual) || (state.density != prevDens)
    }

    private fun latchSector(angleNorm: Float, current: Int, candidate: Int, sectorWidth: Float): Int {
        if (candidate == current) return current

        val halfWidth = sectorWidth * 0.5f

        // Use Scientific Constant
        val requestedMargin = Math.toRadians(InputTuning.ANGLE_HYSTERESIS_DEG.toDouble()).toFloat()
        val margin = min(requestedMargin, halfWidth - 0.0001f)

        // Wrap-around distance logic
        val candCenter = (candidate + 0.5f) * sectorWidth
        var dist = abs(angleNorm - candCenter)
        if (dist > PI.toFloat()) dist = (2 * PI).toFloat() - dist

        return if (dist <= (halfWidth - margin)) candidate else current
    }

    fun getCurrentColor(): Int = when(state.quality) {
        0 -> -65536 // Red
        1 -> -16776961 // Blue
        else -> -16711936 // Green
    }
}