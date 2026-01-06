package com.breathinghand.core

/**
 * HCI Research Results (Chapter 3C)
 * scientifically derived biomechanical constants.
 */
object InputTuning {
    // 1. SIGNAL CONDITIONING (1 Euro Filter)
    const val FILTER_MIN_CUTOFF = 1.0f  // Hz (Silences <0.4px jitter)
    const val FILTER_BETA = 0.02f       // (Eliminates lag at >500px/s)

    // 2. SPATIAL LOGIC (Hysteresis)
    const val ANGLE_HYSTERESIS_DEG = 2.0f   // Precision increased 300%
    const val RADIUS_HYSTERESIS_PX = 100.0f // Compensates for 73px breath

    // 3. TEMPORAL LOGIC (New Time Gates)
    const val CHORD_ASSEMBLY_MS = 80L   // Waits for sloppy fingers
    const val CHORD_RETENTION_MS = 250L // Ignores accidental lifts
}