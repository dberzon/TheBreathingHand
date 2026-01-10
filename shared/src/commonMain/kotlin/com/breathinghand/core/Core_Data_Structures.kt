package com.breathinghand.core

/**
 * Represents the continuously evolving harmonic state of the instrument.
 * Single Source of Truth for: Engine, VoiceLeader, and UI.
 */
data class HarmonicState(
    /**
     * Root pitch class (0–11).
     * Follows circle-of-fifths logic (0=C, 7=G, etc).
     */
    var rootPc: Int = 0,

    /**
     * Current functional sector (0–11) on the circle of fifths.
     * Used for hysteresis calculation.
     */
    var functionSector: Int = 0,

    /**
     * Continuous harmonic instability factor (0.0..1.0).
     * Derived from finger spread.
     * > INSTABILITY_THRESHOLD overrides the chord quality to Diminished.
     */
    var harmonicInstability: Float = 0f,

    /**
     * Number of active fingers (0..4+).
     * Defines the number of active layers (roles).
     */
    var fingerCount: Int = 0,

    /**
     * Latched Triad Archetype (e.g. TRIAD_FAN, TRIAD_STRETCH).
     * Determined by gesture geometry at the moment of 3-finger contact.
     */
    var triad: Int = GestureAnalyzerV01.TRIAD_FAN,

    /**
     * Latched Seventh Archetype (e.g. SEVENTH_COMPACT).
     * Determined by gesture geometry at the moment of 4-finger contact.
     */
    var seventh: Int = GestureAnalyzerV01.SEVENTH_COMPACT
) {
    /**
     * Helper to copy state values without allocation churn.
     * Essential for the hot path.
     */
    fun copyFrom(other: HarmonicState) {
        this.rootPc = other.rootPc
        this.functionSector = other.functionSector
        this.harmonicInstability = other.harmonicInstability
        this.fingerCount = other.fingerCount
        this.triad = other.triad
        this.seventh = other.seventh
    }
}