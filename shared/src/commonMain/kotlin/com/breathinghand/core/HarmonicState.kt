package com.breathinghand.core

/**
 * v0.2 Harmonic State (Continuous Harmonic Field).
 *
 * Must stay KMP-safe and allocation-free in hot paths.
 * No commit/preview/clutch semantics.
 */
data class HarmonicState(
    /** Derived pitch class (0..11) used for voicing. */
    var rootPc: Int = 0,

    /** Committed function sector (0..11) after inertia. */
    var functionSector: Int = 0,

    /** Continuous instability factor: 0.0 (stable) .. 1.0 (max unstable). */
    var harmonicInstability: Float = 0f,

    /** Active finger count (0..4 for v0.2 layering). */
    var fingerCount: Int = 0,

    /** Latched triad archetype (GestureAnalyzer.TRIAD_*). 0 == NONE. */
    var triad: Int = 0,

    /** Latched seventh archetype (GestureAnalyzer.SEVENTH_*). 0 == NONE. */
    var seventh: Int = 0
) {
    /** Zero-allocation copy used by TransitionWindow/restores. */
    fun copyFrom(other: HarmonicState) {
        rootPc = other.rootPc
        functionSector = other.functionSector
        harmonicInstability = other.harmonicInstability
        fingerCount = other.fingerCount
        triad = other.triad
        seventh = other.seventh
    }

    /** Optional helper (still zero-alloc). */
    fun reset() {
        rootPc = 0
        functionSector = 0
        harmonicInstability = 0f
        fingerCount = 0
        triad = 0
        seventh = 0
    }
}
