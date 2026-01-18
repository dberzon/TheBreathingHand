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
 * NOTE: HarmonicState is now defined in HarmonicState.kt (canonical location).
 * This file retains only documentation/design contracts for reference.
 *
 * HarmonicState documentation:
 * - Continuously evolving harmonic state of the instrument (v0.2).
 * - This is NOT a chord preset. It is a layered physical state that morphs over time.
 * - functionSector is the committed sector (0..11) after inertia.
 * - rootPc is the derived pitch class (0..11) used for voicing.
 * - fingerCount is semantic layering input (0..4) for v0.2.
 * - triad/seventh are latched archetypes from GestureAnalyzer (0 = NONE).
 */

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
