/**
 * Core_Data_Structures.kt
 *
 * IMPORTANT — DESIGN CONTRACT (v0.2)
 *
 * This file must comply with:
 *  - BREATHING HAND — GOLDEN RULES
 *  - Implementation Blueprint v0.2
 *  - Gesture Grammar Spec v0.2 — Continuous Harmonic Field
 *
 * Key invariants:
 * 1) NO commit / preview / clutch / permission logic.
 * 2) Harmony is always active once fingers touch.
 * 3) Stability is achieved via harmonic inertia (hysteresis + dwell), never gates.
 * 4) Gestures define harmonic meaning only; time is rhythmic coherence only.
 */

/**
 * Continuously evolving harmonic state of the instrument (v0.2).
 *
 * This is NOT a chord preset. It is a layered physical state that morphs over time.
 *
 * Notes:
 * - functionSector is the committed sector (0..11) after inertia.
 * - rootPc is the derived pitch class (0..11) used for voicing.
 * - fingerCount is semantic layering input (0..4) for v0.2.
 * - triad/seventh are latched archetypes from GestureAnalyzer (0 = NONE).
 */
data class HarmonicState(
    /** Root pitch class (0..11). */
    var rootPc: Int = 0,

    /** Committed functional sector (0..11) used for inertia + dwell. */
    var functionSector: Int = 0,

    /** Continuous harmonic instability factor: 0.0 (stable) .. 1.0 (max unstable). */
    var harmonicInstability: Float = 0f,

    /** Active finger count (0..4 for v0.2 layering). */
    var fingerCount: Int = 0,

    /** Latched triad archetype (GestureAnalyzerV01.TRIAD_*). 0 == NONE. */
    var triad: Int = 0,

    /** Latched seventh archetype (GestureAnalyzerV01.SEVENTH_*). 0 == NONE. */
    var seventh: Int = 0
) {
    /**
     * Zero-allocation copy used by TransitionWindow / restores.
     */
    fun copyFrom(other: HarmonicState) {
        rootPc = other.rootPc
        functionSector = other.functionSector
        harmonicInstability = other.harmonicInstability
        fingerCount = other.fingerCount
        triad = other.triad
        seventh = other.seventh
    }

    /**
     * Optional helper (still zero-alloc): reset to baseline.
     */
    fun reset() {
        rootPc = 0
        functionSector = 0
        harmonicInstability = 0f
        fingerCount = 0
        triad = 0
        seventh = 0
    }
}

/**
 * NOTE ON TEMPORAL CONSTANTS
 *
 * Any timing constants (Transition Window, cascades, etc.) must be used ONLY for rhythmic coherence
 * (note retriggering / release coalescing / landing coalescing).
 *
 * They must never:
 *  - enable harmony change
 *  - block harmony change
 *  - gate root/quality selection
 */
