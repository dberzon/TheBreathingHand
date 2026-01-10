package com.breathinghand.core

/**
 * v0.2 Transition Window — rhythmic-only re-articulation.
 * Preserves the previous HarmonicState across rapid lift -> re-touch.
 * Must never influence harmony selection.
 */
class TransitionWindow {
    private var armed: Boolean = false
    private var tLiftMs: Long = 0L
    private var cx: Float = 0f
    private var cy: Float = 0f
    private var fingerCount: Int = 0

    val storedState: HarmonicState = HarmonicState()

    fun arm(nowMs: Long, centerX: Float, centerY: Float, state: HarmonicState, lastFingerCount: Int) {
        armed = true
        tLiftMs = nowMs
        cx = centerX
        cy = centerY
        fingerCount = lastFingerCount
        storedState.copyFrom(state)
    }

    fun disarm() {
        armed = false
    }

    fun consumeIfHit(nowMs: Long, centerX: Float, centerY: Float, newFingerCount: Int): Boolean {
        if (!armed) return false
        val dt = nowMs - tLiftMs
        if (dt < 0L || dt > MusicalConstants.TRANSITION_WINDOW_MS) return false
        if (newFingerCount != fingerCount) return false

        val dx = centerX - cx
        val dy = centerY - cy
        val tol = MusicalConstants.TRANSITION_TOLERANCE_PX
        if ((dx * dx + dy * dy) > (tol * tol)) return false

        armed = false
        return true
    }
}
