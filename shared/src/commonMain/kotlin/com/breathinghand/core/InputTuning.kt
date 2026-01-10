package com.breathinghand.core

/**
 * HCI Research Results (Chapters 3C + Gesture Grammar v0.1)
 * scientifically derived biomechanical constants.
 */
object InputTuning {
    // -----------------------------------------------------------------
    // 1) SIGNAL CONDITIONING (1 Euro Filter)
    // -----------------------------------------------------------------
    const val FILTER_MIN_CUTOFF = 1.0f  // Hz (Silences <0.4px jitter)
    const val FILTER_BETA = 0.02f       // (Eliminates lag at >500px/s)

    // -----------------------------------------------------------------
    // 2) SPATIAL LOGIC (Hysteresis)
    // -----------------------------------------------------------------
    const val ANGLE_HYSTERESIS_DEG = 2.0f    // Precision increased 300%
    const val RADIUS_HYSTERESIS_PX = 100.0f  // Compensates for ~73px breath

    // -----------------------------------------------------------------
    // 3) TEMPORAL LOGIC (Legacy gates; keep for future experiments)
    // -----------------------------------------------------------------
    const val CHORD_ASSEMBLY_MS = 80L
    const val CHORD_RETENTION_MS = 250L

    // -----------------------------------------------------------------
    // 4) GESTURE GRAMMAR v0.1 (Grip Archetype thresholds)
    // -----------------------------------------------------------------
    // TRIAD (3 fingers)
    // - CLUSTER if spread is tight relative to RED clutch radius.
    const val GRIP_CLUSTER_SPREAD_FACTOR = 0.90f

    // - STRETCH if triangle is very elongated.
    //   pairRatio = maxPair / minPair
    //   meanRatio = maxPair / meanPair
    const val GRIP_STRETCH_PAIR_RATIO = 1.80f
    const val GRIP_STRETCH_MEAN_RATIO = 1.35f

    // SEVENTH (4 fingers)
    // - WIDE spread relative to BLUE radius => minor 7th; otherwise major 7th.
    const val GRIP_WIDE_SPREAD_FACTOR = 1.15f
}
