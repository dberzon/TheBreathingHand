package com.breathinghand.core

import android.media.midi.MidiInputPort
import android.os.SystemClock
import android.util.Log
import java.io.IOException

class MidiOut(private val port: MidiInputPort?) {
    private val buffer = ByteArray(3)

    companion object {
        /**
         * Turn ON only during forensic runs. Logging allocates and can affect timing.
         */
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

    /**
     * Channel Aftertouch (Channel Pressure)
     * MIDI: 0xD0 + channel, data1 = pressure (0..127)
     * 2-byte message
     */
    fun sendChannelPressure(ch: Int, pressure: Int) {
        if (port == null) return
        val p = pressure.coerceIn(0, 127)
        try {
            buffer[0] = (0xD0 or (ch and 0x0F)).toByte()
            buffer[1] = p.toByte()
            port.send(buffer, 0, 2)
        } catch (_: IOException) {}
    }

    private fun send(status: Int, d1: Int, d2: Int) {
        try {
            buffer[0] = status.toByte()
            buffer[1] = d1.toByte()
            buffer[2] = d2.toByte()
            port?.send(buffer, 0, 3)
        } catch (e: IOException) {
            // Ignore broken pipe
        }
    }

    fun close() {
        try { port?.close() } catch(_: IOException) {}
    }
}

data class MPEVoice(val channel: Int, var note: Int = 0, var active: Boolean = false)

class VoiceLeader(private val midi: MidiOut) {
    private val voices = Array(5) { i -> MPEVoice(channel = i + 1) }
    private val currentState = HarmonicState()
    private val targetNotes = IntArray(5)
    private val targetVel = IntArray(5) { 90 }

    // --- Chapter 3D: Aftertouch (continuous expression) ---
    private val targetAT = IntArray(5) { 0 }
    private val lastSentAT = IntArray(5) { -1 }
    private var atDirty = false
    private var atCount = 0

    fun update(input: HarmonicState) {
        if (input.root == currentState.root &&
            input.quality == currentState.quality &&
            input.density == currentState.density) return

        currentState.root = input.root
        currentState.quality = input.quality
        currentState.density = input.density

        // CIRCLE OF FIFTHS MAPPING
        val fifthsRoot = (input.root * 7) % 12
        val chromaticRoot = 60 + fifthsRoot

        val intervals = get(input.quality, input.density)
        val safeCount = kotlin.math.min(input.density, intervals.size)

        for (i in 0 until safeCount) {
            if (i < targetNotes.size) {
                targetNotes[i] = chromaticRoot + intervals[i]
            }
        }

        // Monotonic sorting (prevents voice crossing)
        for (i in 1 until safeCount) {
            while (targetNotes[i] <= targetNotes[i-1]) {
                targetNotes[i] += 12
            }
        }

        if (safeCount > 0 && targetNotes[safeCount-1] > 96) {
            val shift = 12
            for(i in 0 until safeCount) targetNotes[i] -= shift
        }

        solveAndSend(safeCount)
    }

    /**
     * Sets per-voice target velocities (MVP dynamics).
     * Caller must pass a stable/reused array to avoid allocations.
     */
    fun setVelocities(velocities: IntArray, count: Int) {
        val n = kotlin.math.min(count, targetVel.size)
        for (i in 0 until n) {
            targetVel[i] = velocities[i].coerceIn(1, 127)
        }
        // Fill remaining with default to avoid stale values if density shrinks
        for (i in n until targetVel.size) {
            targetVel[i] = 90
        }
    }

    /**
     * Set per-voice aftertouch targets (0..127).
     * Values are sent later via flushAftertouch().
     */
    fun setAftertouch(values: IntArray, count: Int) {
        val n = kotlin.math.min(count, targetAT.size)
        for (i in 0 until n) targetAT[i] = values[i].coerceIn(0, 127)
        for (i in n until targetAT.size) targetAT[i] = 0
        atCount = n
        atDirty = true
    }

    /**
     * Flush aftertouch once per frame.
     * Sends only if value changed (no MIDI spam).
     */
    fun flushAftertouch() {
        if (!atDirty) return
        atDirty = false

        for (i in 0 until atCount) {
            val v = targetAT[i]
            if (v != lastSentAT[i]) {
                midi.sendChannelPressure(voices[i].channel, v)
                lastSentAT[i] = v
            }
        }
    }

    private fun solveAndSend(activeCount: Int) {
        for (i in voices.indices) {
            val voice = voices[i]
            if (i < activeCount) {
                val finalNote = targetNotes[i]
                if (!voice.active || voice.note != finalNote) {
                    if (voice.active) midi.sendNoteOff(voice.channel, voice.note)
                    midi.sendNoteOn(voice.channel, finalNote, targetVel[i])
                    voice.note = finalNote
                    voice.active = true
                }
            } else {
                if (voice.active) {
                    midi.sendNoteOff(voice.channel, voice.note)
                    voice.active = false
                }
            }
        }
    }



    fun allNotesOff() {
        voices.forEach {
            if (it.active) midi.sendNoteOff(it.channel, it.note)
            it.active = false
        }
        // Reset aftertouch state
        for (i in lastSentAT.indices) {
            lastSentAT[i] = -1
            targetAT[i] = 0
        }
    }

    fun close() {
        allNotesOff()
        midi.close()
    }

    companion object ChordTable {
        private val DATA = arrayOf(
            arrayOf(intArrayOf(0,3,6), intArrayOf(0,3,6,9), intArrayOf(0,3,6,9,12)),
            arrayOf(intArrayOf(0,3,7), intArrayOf(0,3,7,10), intArrayOf(0,3,7,10,14)),
            arrayOf(intArrayOf(0,4,7), intArrayOf(0,4,7,11), intArrayOf(0,4,7,11,14))
        )
        fun get(q: Int, d: Int): IntArray {
            val qIndex = q.coerceIn(0, 2)
            val dIndex = (d - 3).coerceIn(0, 2)
            return DATA[qIndex][dIndex]
        }
    }
}
