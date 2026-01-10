package com.breathinghand.core

/**
 * HCI Research Results (Chapters 3C + Gesture Grammar v0.2)
 * Scientifically derived biomechanical constants.
 *
 * IMPORTANT:
 * - This file must NOT contain temporal gating for harmony.
 * - Any timing used in the project must be for rhythmic coherence only (Transition Window etc),
 * and must live with those timing definitions (e.g. MusicalConstants).
 */
object InputTuning {

    // -----------------------------------------------------------------
    // 1) SIGNAL CONDITIONING (1 Euro Filter)
    // -----------------------------------------------------------------
    const val FILTER_MIN_CUTOFF = 1.0f  // Hz (silences <0.4px jitter)
    const val FILTER_BETA = 0.02f       // (reduces lag at high velocity)

    // -----------------------------------------------------------------
    // 2) SPATIAL LOGIC (Hysteresis)
    // -----------------------------------------------------------------
    // FIX: Removed unused constants (ANGLE_HYSTERESIS_DEG, RADIUS_HYSTERESIS_PX)
    // Logic is now handled by HarmonicEngine parameters.

    // -----------------------------------------------------------------
    // 3) GESTURE GRAMMAR (Grip archetype thresholds)
    // -----------------------------------------------------------------
    // TRIAD (3 fingers)
    // - CLUSTER if spread is tight relative to the small-radius stability zone.
    const val GRIP_CLUSTER_SPREAD_FACTOR = 0.90f

    // - STRETCH if triangle is very elongated.
    //   pairRatio = maxPair / minPair
    //   meanRatio = maxPair / meanPair
    const val GRIP_STRETCH_PAIR_RATIO = 1.80f
    const val GRIP_STRETCH_MEAN_RATIO = 1.35f

    // SEVENTH (4 fingers)
    // - WIDE spread at large radius => minor 7th; otherwise major 7th.
    const val GRIP_WIDE_SPREAD_FACTOR = 1.15f
}
