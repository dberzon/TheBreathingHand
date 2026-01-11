package com.breathinghand.core

object MusicalConstants {
    // --- SYSTEM INTEGRITY ---
    /**
     * GLOBAL DEBUG FLAG.
     * MUST be false for production/performance validation.
     * When true, forensic logging is enabled (allocates strings).
     */
    const val IS_DEBUG = false


    // Music domain
    const val MIDDLE_C = 60
    const val MAX_NOTE = 96 // C7
    const val MAX_VOICES = 5

    // Geometry / UI
    const val SECTOR_COUNT = 12
    const val BASE_RADIUS_INNER = 150f  // px at 1x density
    const val BASE_RADIUS_OUTER = 350f  // px at 1x density
    const val SPREAD_RADIUS_MULTIPLIER = 2.5f
    const val MAX_POINTER_ID = 64

    // MIDI Constants
    const val CENTER_PITCH_BEND = 8192
    const val CENTER_CC74 = 64
    const val DEFAULT_VELOCITY = 90

    // Internal synth expression mapping (CC11) toggle.
    // When false, CC11 streaming is bypassed and a single CC11=127 is sent at init.
    const val INTERNAL_USE_CC11_EXPRESSION: Boolean = true

    // ----------------------------
    // v0.2 — Continuous Harmonic Field
    // ----------------------------

    // Root motion stabilization (debounce + hysteresis).
    const val DWELL_THRESHOLD_MS: Long = 90L
    const val SECTOR_HYSTERESIS_RAD: Float = 0.12f

    // "Fast throw" disabled by default.
    const val ANGULAR_SNAP_THRESHOLD_RAD_PER_SEC: Float = 9999f

    // Spread -> instability (continuous).
    const val SPREAD_MIN_PX: Float = 35f
    const val SPREAD_MAX_PX: Float = 220f
    const val INSTABILITY_THRESHOLD: Float = 0.60f

    // Transition Window (rhythmic-only re-articulation).
    const val TRANSITION_WINDOW_MS: Long = 120L
    const val TRANSITION_TOLERANCE_PX: Float = 40f

    // Initial-only vertical bias.
    const val BIAS_UPPER_CUTOFF_NORM: Float = 0.33f
    const val BIAS_LOWER_CUTOFF_NORM: Float = 0.66f
    const val BIAS_SECTOR_CENTER: Int = 0
    const val BIAS_SECTOR_LOWER: Int = 2
    const val BIAS_SECTOR_UPPER: Int = 4
}