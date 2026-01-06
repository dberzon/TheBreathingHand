package com.breathinghand.core

/**
 * Platform-agnostic snapshot of touch state.
 * Index == strict slot (0..MAX_VOICES-1).
 *
 * This is KMP-friendly: no MotionEvent, no Android APIs.
 * Arrays are preallocated once.
 */
class TouchFrame(maxVoices: Int = MusicalConstants.MAX_VOICES) {
    var activeCount: Int = 0
    var tMs: Long = 0L // monotonic milliseconds (uptime)

    val pointerIds = IntArray(maxVoices) { INVALID_ID }
    val x = FloatArray(maxVoices)
    val y = FloatArray(maxVoices)

    // Normalized "musical force" 0..1 (preferred over raw pressure/size for the engine)
    val force01 = FloatArray(maxVoices)

    // Optional raw sensors (useful for debugging; iOS can fill approximations)
    val pressure = FloatArray(maxVoices)
    val size = FloatArray(maxVoices)

    // Bitmask flags (DOWN/UP/WACK/etc). Keep layout consistent across platforms.
    val flags = IntArray(maxVoices)

    companion object {
        const val INVALID_ID = -1
        const val F_DOWN = 1 shl 0
        const val F_UP = 1 shl 1
        const val F_WACK = 1 shl 2
        const val F_PRIMARY = 1 shl 3
    }
}