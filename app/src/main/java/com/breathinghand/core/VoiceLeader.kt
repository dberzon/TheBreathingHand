package com.breathinghand.core

import android.media.midi.MidiInputPort
import java.io.IOException

class MidiOut(private val port: MidiInputPort?) {
    private val buffer = ByteArray(3)

    fun sendNoteOn(ch: Int, note: Int, vel: Int) {
        if (port == null) return
        send(0x90 or (ch and 0x0F), note, vel)
    }

    fun sendNoteOff(ch: Int, note: Int) {
        if (port == null) return
        send(0x80 or (ch and 0x0F), note, 0)
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

    private fun solveAndSend(activeCount: Int) {
        for (i in voices.indices) {
            val voice = voices[i]
            if (i < activeCount) {
                val finalNote = targetNotes[i]
                if (!voice.active || voice.note != finalNote) {
                    if (voice.active) midi.sendNoteOff(voice.channel, voice.note)
                    midi.sendNoteOn(voice.channel, finalNote, 90)
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