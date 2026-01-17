package com.breathinghand.core.midi

object MusicalConstants {
    /**
     * VOICE ALLOCATION
     */
    
    /** Maximum number of simultaneous voice slots (fingers). Mapped to MIDI Channels 2-6 (Slots 0-4). */
    const val MAX_VOICES = 5
    
    /** Maximum pointer ID that timbre navigator will track */
    const val MAX_POINTER_ID = 9
    
    /**
     * GESTURE GEOMETRY (DP)
     */
    
    /** Inner radius threshold for gesture detection (dp) */
    const val BASE_RADIUS_INNER = 80f
    
    /** Outer radius threshold for gesture detection (dp) */
    const val BASE_RADIUS_OUTER = 200f
    
    /**
     * RHYTHMIC CASCADE WINDOWS (MILLISECONDS)
     */
    
    /** Duration to suppress new attacks after finger lift (ms) */
    const val RELEASE_CASCADE_MS = 100L
    
    /** Duration to suppress new attacks after finger landing (ms) */
    const val LANDING_CASCADE_MS = 50L
    
    /**
     * MIDI CONTROL CENTERS
     */
    
    /** Center value for pitch bend (14-bit: 0-16383) */
    const val CENTER_PITCH_BEND = 8192
    
    /** Center value for CC74 (brightness/timbre) */
    const val CENTER_CC74 = 64
    
    /**
     * HARMONIC STABILITY
     */
    
    /** Threshold for harmonic instability (0.0-1.0) */
    const val INSTABILITY_THRESHOLD = 0.5f
    
    /**
     * DEBUG & EXPRESSION CONTROL
     */
    
    /** Enable debug logging (forensic data) */
    const val IS_DEBUG = false
    
    /** CC number for expression (set to 11 for internal synth usage) */
    const val INTERNAL_USE_CC11_EXPRESSION = 11
}
