package com.breathinghand.core

import android.media.midi.MidiInputPort
import android.os.SystemClock
import android.util.Log
import java.io.IOException

class MidiOut(private val port: MidiInputPort?) {
    private val buffer = ByteArray(3)

    companion object {
        @JvmField var FORENSIC_TX_LOG: Boolean = true
        private const val FORENSIC_TAG = "FORENSIC_DATA"
    }

    fun sendNoteOn(ch: Int, note: Int, vel: Int) {
        if (port == null) return
        if (FORENSIC_TX_LOG) {
            val tMs = SystemClock.uptimeMillis()
            val midiCh = (ch and 0x0F) + 1
            Log.d(FORENSIC_TAG, "$tMs,MIDI_TX,NOTE_ON,$midiCh,$note,$vel")
        }
        send(0x90 or (ch and 0x0F), note, vel)
    }

    fun sendNoteOff(ch: Int, note: Int) {
        if (port == null) return
        if (FORENSIC_TX_LOG) {
            val tMs = SystemClock.uptimeMillis()
            val midiCh = (ch and 0x0F) + 1
            Log.d(FORENSIC_TAG, "$tMs,MIDI_TX,NOTE_OFF,$midiCh,$note,0")
        }
        send(0x80 or (ch and 0x0F), note, 0)
    }

    fun sendChannelPressure(ch: Int, pressure: Int) {
        if (port == null) return
        val p = pressure.coerceIn(0, 127)
        try {
            buffer[0] = (0xD0 or (ch and 0x0F)).toByte()
            buffer[1] = p.toByte()
            port.send(buffer, 0, 2)
        } catch (_: IOException) {}
    }

    /**
     * Standard 14-bit Pitch Bend.
     * bend14: 0..16383 (8192 = center)
     */
    fun sendPitchBend(ch: Int, bend14: Int) {
        if (port == null) return
        val v = bend14.coerceIn(0, 16383)
        val lsb = v and 0x7F
        val msb = (v shr 7) and 0x7F
        try {
            buffer[0] = (0xE0 or (ch and 0x0F)).toByte()
            buffer[1] = lsb.toByte()
            buffer[2] = msb.toByte()
            port.send(buffer, 0, 3)
        } catch (_: IOException) {}
    }

    /**
     * Standard 7-bit Control Change.
     */
    fun sendCC(ch: Int, cc: Int, value: Int) {
        if (port == null) return
        val c = cc.coerceIn(0, 127)
        val v = value.coerceIn(0, 127)
        send(0xB0 or (ch and 0x0F), c, v)
    }

    private fun send(status: Int, d1: Int, d2: Int) {
        try {
            buffer[0] = status.toByte()
            buffer[1] = d1.toByte()
            buffer[2] = d2.toByte()
            port?.send(buffer, 0, 3)
        } catch (_: IOException) {}
    }

    fun close() {
        try { port?.close() } catch (_: IOException) {}
    }
}

/**
 * Voice now tracks WHICH pointer owns it.
 * Slot index == voice index in this refactor.
 */
data class MPEVoice(
    val channel: Int,
    var note: Int = 0,
    var active: Boolean = false,
    var assignedPointerId: Int = -1
)

class VoiceLeader(private val midi: MidiOut) {

    // Voice index == slot index. Channels are fixed per slot for true stickiness.
    // (0-based in MIDI bytes => channel=1 means "MIDI channel 2" in human terms)
    private val voices = Array(5) { i -> MPEVoice(channel = i + 1) }

    private val currentState = HarmonicState()
    private val targetNotes = IntArray(5)

    // PointerId -> velocity lookup table (no allocations, O(1)).
    private val velocityByPointerId = IntArray(64) { 90 }

    // Continuous expression buffers (indexed by SLOT/VOICE index 0..4)
    private val pendingAftertouch = IntArray(5) { 0 }
    private val lastSentAftertouch = IntArray(5) { -1 }

    private val pendingPitchBend = IntArray(5) { 8192 } // center
    private val lastSentPitchBend = IntArray(5) { -1 }

    // CC74 Timbre (MPE convention)
    private val pendingCC74 = IntArray(5) { 64 } // neutral
    private val lastSentCC74 = IntArray(5) { -1 }

    fun update(input: HarmonicState, activePointerIds: IntArray) {
        updateHarmonicTargets(input)
        updateAllocationBySlot(activePointerIds)
        solveAndSendBySlot()
    }

    /**
     * Velocity is stored by pointerId (as your MainActivity already provides).
     * We also "prime" the slot with this pointerId if the voice is not active yet,
     * so early gesture/force data isn't lost before the next commit.
     */
    fun setSlotVelocity(slotIndex: Int, pointerId: Int, velocity: Int) {
        if (slotIndex !in 0 until 5) return
        val v = velocity.coerceIn(1, 127)

        if (pointerId in 0 until velocityByPointerId.size) {
            velocityByPointerId[pointerId] = v
        }

        // Prime mapping only if this slot isn't currently sounding a note.
        val voice = voices[slotIndex]
        if (!voice.active && pointerId != SmartInputHAL.INVALID_ID) {
            voice.assignedPointerId = pointerId
        }
    }

    fun setSlotAftertouch(slotIndex: Int, pointerId: Int, value: Int) {
        if (slotIndex !in 0 until 5) return
        val v = value.coerceIn(0, 127)
        val voice = voices[slotIndex]

        // Prevent a new finger from modulating an old still-sounding note in this slot.
        if (voice.active && voice.assignedPointerId != pointerId) return

        if (!voice.active && pointerId != SmartInputHAL.INVALID_ID) {
            voice.assignedPointerId = pointerId
        }

        pendingAftertouch[slotIndex] = v
    }

    fun setSlotPitchBend(slotIndex: Int, pointerId: Int, bend14: Int) {
        if (slotIndex !in 0 until 5) return
        val v = bend14.coerceIn(0, 16383)
        val voice = voices[slotIndex]

        if (voice.active && voice.assignedPointerId != pointerId) return

        if (!voice.active && pointerId != SmartInputHAL.INVALID_ID) {
            voice.assignedPointerId = pointerId
        }

        pendingPitchBend[slotIndex] = v
    }

    /**
     * CC74 Timbre. value: 0..127 (64 = neutral baseline in our mapping)
     */
    fun setSlotCC74(slotIndex: Int, pointerId: Int, value: Int) {
        if (slotIndex !in 0 until 5) return
        val v = value.coerceIn(0, 127)
        val voice = voices[slotIndex]

        if (voice.active && voice.assignedPointerId != pointerId) return

        if (!voice.active && pointerId != SmartInputHAL.INVALID_ID) {
            voice.assignedPointerId = pointerId
        }

        pendingCC74[slotIndex] = v
    }

    /**
     * Call this once per frame to flush continuous data.
     * (Name kept to avoid breaking MainActivity.)
     */
    fun flushAftertouch() {
        for (i in 0 until 5) {
            val voice = voices[i]
            if (voice.active) {
                val at = pendingAftertouch[i]
                if (at != lastSentAftertouch[i]) {
                    midi.sendChannelPressure(voice.channel, at)
                    lastSentAftertouch[i] = at
                }

                val pb = pendingPitchBend[i]
                if (pb != lastSentPitchBend[i]) {
                    midi.sendPitchBend(voice.channel, pb)
                    lastSentPitchBend[i] = pb
                }

                val t = pendingCC74[i]
                if (t != lastSentCC74[i]) {
                    midi.sendCC(voice.channel, 74, t)
                    lastSentCC74[i] = t
                }
            } else {
                // Reset sent-state so next activation will re-send fresh values.
                lastSentAftertouch[i] = -1
                pendingAftertouch[i] = 0

                lastSentPitchBend[i] = -1
                pendingPitchBend[i] = 8192

                lastSentCC74[i] = -1
                pendingCC74[i] = 64
            }
        }
    }

    // --- Private helpers ---

    private fun updateHarmonicTargets(input: HarmonicState) {
        currentState.root = input.root
        currentState.quality = input.quality
        currentState.density = input.density

        val fifthsRoot = (input.root * 7) % 12
        val chromaticRoot = 60 + fifthsRoot
        val intervals = ChordTable.get(input.quality, input.density)

        val count = intervals.size
        for (i in 0 until 5) {
            targetNotes[i] = if (i < count) chromaticRoot + intervals[i] else 0
        }

        // strictly ascending
        for (i in 1 until count) {
            while (targetNotes[i] <= targetNotes[i - 1]) targetNotes[i] += 12
        }

        // keep in a sane range
        if (count > 0 && targetNotes[count - 1] > 96) {
            for (i in 0 until count) targetNotes[i] -= 12
        }
    }

    /**
     * Slot-stable allocation:
     * voice[i] corresponds to slot i.
     *
     * activePointerIds is expected to be IntArray(5) where index == slot.
     */
    private fun updateAllocationBySlot(activePointerIds: IntArray) {
        val n = if (activePointerIds.size < 5) activePointerIds.size else 5

        for (slot in 0 until 5) {
            val voice = voices[slot]
            val wantPid = if (slot < n) activePointerIds[slot] else SmartInputHAL.INVALID_ID
            val havePid = voice.assignedPointerId

            if (wantPid == SmartInputHAL.INVALID_ID) {
                // Slot is currently empty.
                if (havePid != SmartInputHAL.INVALID_ID) {
                    // Pointer disappeared: release any sounding note.
                    releaseVoice(slot, resetPid = havePid)
                }
                continue
            }

            // Slot is occupied by wantPid.
            if (havePid == wantPid) {
                // No change in ownership.
                continue
            }

            // Slot owner changed (or was unassigned).
            // If we were sounding a note for the old pointer, stop it.
            if (havePid != SmartInputHAL.INVALID_ID) {
                // If a note is active, stop it cleanly; also reset old pid velocity.
                releaseVoice(slot, resetPid = havePid)
            }

            // Assign new pointer to this slot/voice.
            voice.assignedPointerId = wantPid
            voice.note = 0
            voice.active = false

            // Make sure next flush sends fresh expression for this channel.
            lastSentAftertouch[slot] = -1
            lastSentPitchBend[slot] = -1
            lastSentCC74[slot] = -1
        }
    }

    /**
     * Chord-tone assignment by slot:
     * slot i -> targetNotes[i]
     *
     * No sorting, no allocations.
     */
    private fun solveAndSendBySlot() {
        for (slot in 0 until 5) {
            val voice = voices[slot]
            val pid = voice.assignedPointerId
            if (pid == SmartInputHAL.INVALID_ID) continue

            val newNote = targetNotes[slot]

            // If this chord doesn't use this slot (density < slot), stop any existing note.
            if (newNote == 0) {
                if (voice.active) {
                    midi.sendNoteOff(voice.channel, voice.note)
                    // reset channel expression to neutral so the next note is clean
                    midi.sendChannelPressure(voice.channel, 0)
                    midi.sendPitchBend(voice.channel, 8192)
                    midi.sendCC(voice.channel, 74, 64)
                }
                voice.note = 0
                voice.active = false
                continue
            }

            // If note changed (or we weren't active), retrigger.
            if (!voice.active || voice.note != newNote) {
                if (voice.active) {
                    midi.sendNoteOff(voice.channel, voice.note)
                }

                val vel = if (pid in 0 until velocityByPointerId.size) velocityByPointerId[pid] else 90
                midi.sendNoteOn(voice.channel, newNote, vel)

                voice.note = newNote
                voice.active = true
            }
        }
    }

    private fun releaseVoice(slot: Int, resetPid: Int) {
        val voice = voices[slot]
        if (voice.active) {
            midi.sendNoteOff(voice.channel, voice.note)
            midi.sendChannelPressure(voice.channel, 0)
            midi.sendPitchBend(voice.channel, 8192)
            midi.sendCC(voice.channel, 74, 64)
        }

        if (resetPid in 0 until velocityByPointerId.size) {
            velocityByPointerId[resetPid] = 90
        }

        voice.note = 0
        voice.active = false
        voice.assignedPointerId = SmartInputHAL.INVALID_ID

        // Reset expression buffers for this slot so we don't "inherit" the old finger’s values.
        pendingAftertouch[slot] = 0
        pendingPitchBend[slot] = 8192
        pendingCC74[slot] = 64

        lastSentAftertouch[slot] = -1
        lastSentPitchBend[slot] = -1
        lastSentCC74[slot] = -1
    }

    // --- Lifecycle ---

    fun allNotesOff() {
        for (i in 0 until 5) {
            val v = voices[i]
            if (v.active) {
                midi.sendNoteOff(v.channel, v.note)
                midi.sendChannelPressure(v.channel, 0)
                midi.sendPitchBend(v.channel, 8192)
                midi.sendCC(v.channel, 74, 64)
            }
            v.note = 0
            v.active = false
            v.assignedPointerId = SmartInputHAL.INVALID_ID

            pendingAftertouch[i] = 0
            lastSentAftertouch[i] = -1

            pendingPitchBend[i] = 8192
            lastSentPitchBend[i] = -1

            pendingCC74[i] = 64
            lastSentCC74[i] = -1
        }
    }

    fun close() {
        allNotesOff()
        midi.close()
    }

    companion object ChordTable {
        private val DATA = arrayOf(
            arrayOf(intArrayOf(0, 3, 6), intArrayOf(0, 3, 6, 9), intArrayOf(0, 3, 6, 9, 12)),
            arrayOf(intArrayOf(0, 3, 7), intArrayOf(0, 3, 7, 10), intArrayOf(0, 3, 7, 10, 14)),
            arrayOf(intArrayOf(0, 4, 7), intArrayOf(0, 4, 7, 11), intArrayOf(0, 4, 7, 11, 14))
        )
        fun get(q: Int, d: Int): IntArray {
            val qIndex = q.coerceIn(0, 2)
            val dIndex = (d - 3).coerceIn(0, 2)
            return DATA[qIndex][dIndex]
        }
    }
}
