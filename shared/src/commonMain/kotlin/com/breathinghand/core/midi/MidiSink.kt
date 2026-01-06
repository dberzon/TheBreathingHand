package com.breathinghand.core.midi

/**
 * Zero-allocation MIDI transport contract (KMP-friendly).
 */
interface MidiSink {
    fun send3(status: Int, data1: Int, data2: Int)
    fun send2(status: Int, data1: Int)
    fun close()
}

interface ForensicLogger {
    fun log(tag: String, message: String)
}

interface MonotonicClock {
    fun nowMs(): Long
}
