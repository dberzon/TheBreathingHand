package com.breathinghand.core

object MusicalConstants {
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
}