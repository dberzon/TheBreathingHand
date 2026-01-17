package com.breathinghand.core.midi

import android.media.midi.MidiReceiver

/**
 * ADAPTER: 0-BASED MIDI CONTRACT
 * Wraps Android's MidiReceiver to enforce strict 0-15 channel indexing.
 * Zero-allocation: Reuses byte buffers.
 */
class AndroidMidiOutput(
    private val receiver: MidiReceiver?,
    private val sink: MidiSink? = null
) : MidiOutput {

    constructor(sink: MidiSink) : this(receiver = null, sink = sink)

    // Zero-allocation buffers (reused)
    private val buffer3 = ByteArray(3)
    private val buffer2 = ByteArray(2)

    private fun send3(status: Int, data1: Int, data2: Int) {
        // Sink path (KMP-friendly) first/always
        sink?.send3(status, data1, data2)

        // Android MidiReceiver path (optional)
        val r = receiver ?: return
        buffer3[0] = status.toByte()
        buffer3[1] = data1.toByte()
        buffer3[2] = data2.toByte()
        try {
            r.send(buffer3, 0, 3, 0L)
        } catch (_: Exception) {
            // Never crash from MIDI I/O
        }
    }

    private fun send2(status: Int, data1: Int) {
        // Sink path (KMP-friendly) first/always
        sink?.send2(status, data1)

        // Android MidiReceiver path (optional)
        val r = receiver ?: return
        buffer2[0] = status.toByte()
        buffer2[1] = data1.toByte()
        try {
            r.send(buffer2, 0, 2, 0L)
        } catch (_: Exception) {
            // Never crash from MIDI I/O
        }
    }

    override fun sendNoteOn(channel: Int, note: Int, velocity: Int) {
        val c = channel.coerceIn(0, 15)
        val n = note.coerceIn(0, 127)
        val v = velocity.coerceIn(0, 127)
        send3(0x90 + c, n, v)
    }

    override fun sendNoteOff(channel: Int, note: Int, velocity: Int) {
        val c = channel.coerceIn(0, 15)
        val n = note.coerceIn(0, 127)
        val v = velocity.coerceIn(0, 127)
        send3(0x80 + c, n, v)
    }

    override fun sendPitchBend(channel: Int, value14Bit: Int) {
        val c = channel.coerceIn(0, 15)
        val v = value14Bit.coerceIn(0, 16383)
        val lsb = v and 0x7F
        val msb = (v shr 7) and 0x7F
        send3(0xE0 + c, lsb, msb)
    }

    override fun sendControlChange(channel: Int, controller: Int, value: Int) {
        val c = channel.coerceIn(0, 15)
        val cc = controller.coerceIn(0, 127)
        val v = value.coerceIn(0, 127)
        send3(0xB0 + c, cc, v)
    }

    override fun sendChannelPressure(channel: Int, pressure: Int) {
        val c = channel.coerceIn(0, 15)
        val p = pressure.coerceIn(0, 127)
        send2(0xD0 + c, p)
    }
}
