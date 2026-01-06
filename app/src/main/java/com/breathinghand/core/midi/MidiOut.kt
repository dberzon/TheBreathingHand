package com.breathinghand.core.midi

import android.media.midi.MidiInputPort
import android.os.SystemClock
import android.util.Log
import java.io.IOException

class MidiOut(private val port: MidiInputPort?) {
    private val buffer = ByteArray(3)
    private val TAG = "MidiOut"

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
        } catch (e: IOException) {
            Log.w(TAG, "Failed to send Channel Pressure", e)
        }
    }

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
        } catch (e: IOException) {
            Log.w(TAG, "Failed to send Pitch Bend", e)
        }
    }

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
        } catch (e: IOException) {
            Log.w(TAG, "Failed to send MIDI message", e)
        }
    }

    fun close() {
        try { port?.close() } catch (e: IOException) {
            Log.w(TAG, "Failed to close port", e)
        }
    }
}
