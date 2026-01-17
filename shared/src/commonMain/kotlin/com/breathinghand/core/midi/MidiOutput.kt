package com.breathinghand.core.midi

/**
 * Platform-agnostic interface for sending MIDI messages.
 */
interface MidiOutput {
    fun sendNoteOn(channel: Int, note: Int, velocity: Int)
    fun sendNoteOff(channel: Int, note: Int, velocity: Int)
    fun sendPitchBend(channel: Int, value14Bit: Int)
    fun sendControlChange(channel: Int, controller: Int, value: Int)
    fun sendChannelPressure(channel: Int, pressure: Int)
}
