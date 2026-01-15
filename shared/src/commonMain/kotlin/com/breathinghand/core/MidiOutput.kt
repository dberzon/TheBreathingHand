package com.breathinghand.core

import com.breathinghand.core.midi.MidiOut
import com.breathinghand.core.MusicalConstants

/**
 * Hot-path interface for sending note/expression data.
 * Abstraction layer to support MPE, Standard MIDI, and Internal Audio.
 */
interface MidiOutput {
    fun noteOn(slot: Int, note: Int, velocity: Int)
    fun noteOff(slot: Int, note: Int)
    fun pitchBend(slot: Int, bend14: Int)
    fun channelPressure(slot: Int, pressure: Int)
    fun cc(slot: Int, ccNum: Int, value: Int)
    fun allNotesOff()
    fun close()
}

/**
 * MPE Implementation (MIDI channels are 1-based for humans, 0-based in MidiOut API):
 * - MIDI Ch 1 is GLOBAL/RESERVED (not used for per-finger notes here).
 * - Member channels start at MIDI Ch 2.
 * - We therefore send on ch = slot + 1 (0-based), i.e.:
 *     slot 0 -> ch=1 -> MIDI Ch 2
 *     slot 1 -> ch=2 -> MIDI Ch 3
 */
class MpeMidiOutput(private val midi: MidiOut) : MidiOutput {

    override fun noteOn(slot: Int, note: Int, velocity: Int) {
        midi.sendNoteOn(slot + 1, note, velocity)
    }

    override fun noteOff(slot: Int, note: Int) {
        midi.sendNoteOff(slot + 1, note)
    }

    override fun pitchBend(slot: Int, bend14: Int) {
        midi.sendPitchBend(slot + 1, bend14)
    }

    override fun channelPressure(slot: Int, pressure: Int) {
        midi.sendChannelPressure(slot + 1, pressure)
    }

    override fun cc(slot: Int, ccNum: Int, value: Int) {
        midi.sendCC(slot + 1, ccNum, value)
    }

    override fun allNotesOff() {
        for (i in 0 until MusicalConstants.MAX_VOICES) {
            val ch = i + 1
            midi.sendCC(ch, 123, 0) // All Notes Off
            midi.sendPitchBend(ch, MusicalConstants.CENTER_PITCH_BEND)
            midi.sendChannelPressure(ch, 0)
        }
    }

    override fun close() {
        midi.close()
    }
}

/**
 * Standard Implementation: All slots -> Channel 0 (Human Ch 1).
 * CRITICAL FIX: Uses BooleanArray instead of HashSet to avoid allocations.
 */
class StandardMidiOutput(private val midi: MidiOut) : MidiOutput {

    private val TARGET_CHANNEL = 0 // Human Channel 1

    // Zero-allocation tracking of active notes to prevent stuck notes
    private val activeNotes = BooleanArray(128)

    override fun noteOn(slot: Int, note: Int, velocity: Int) {
        val safeNote = note.coerceIn(0, 127)
        activeNotes[safeNote] = true
        midi.sendNoteOn(TARGET_CHANNEL, safeNote, velocity)
    }

    override fun noteOff(slot: Int, note: Int) {
        val safeNote = note.coerceIn(0, 127)
        // In standard MIDI, if multiple fingers hold the same note,
        // one note-off usually kills it. We just send it.
        if (activeNotes[safeNote]) {
            activeNotes[safeNote] = false
            midi.sendNoteOff(TARGET_CHANNEL, safeNote)
        }
    }

    override fun pitchBend(slot: Int, bend14: Int) {
        // Reductive Logic: Only the Primary Finger (Slot 0) controls global pitch bend.
        // This prevents "fighting" where 5 fingers send 5 different bend values to one channel.
        if (slot == 0) {
            midi.sendPitchBend(TARGET_CHANNEL, bend14)
        }
    }

    override fun channelPressure(slot: Int, pressure: Int) {
        // Reductive Logic: Maximize pressure or use Primary.
        // Using Primary (Slot 0) is safer for clean control.
        if (slot == 0) {
            midi.sendChannelPressure(TARGET_CHANNEL, pressure)
        }
    }

    override fun cc(slot: Int, ccNum: Int, value: Int) {
        // Reductive Logic: Only Primary Finger controls global CC.
        if (slot == 0) {
            midi.sendCC(TARGET_CHANNEL, ccNum, value)
        }
    }

    override fun allNotesOff() {
        // Iterate primitive array - no iterator allocation
        for (i in 0..127) {
            if (activeNotes[i]) {
                midi.sendNoteOff(TARGET_CHANNEL, i)
                activeNotes[i] = false
            }
        }
        midi.sendCC(TARGET_CHANNEL, 123, 0)
        midi.sendPitchBend(TARGET_CHANNEL, MusicalConstants.CENTER_PITCH_BEND)
    }

    override fun close() {
        allNotesOff()
        midi.close()
    }
}