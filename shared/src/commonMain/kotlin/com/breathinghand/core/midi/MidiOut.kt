package com.breathinghand.core.midi

/**
 * KMP-safe MIDI message builder + sender.
 *
 * - Zero allocations in hot path (transport owns reusable buffers).
 * - Platform logging/clock are optional and injected.
 */
class MidiOut(
    private val sink: MidiSink,
    private val clock: MonotonicClock? = null,
    private val logger: ForensicLogger? = null
) {

    companion object {
        @JvmField var FORENSIC_TX_LOG: Boolean = true
        private const val FORENSIC_TAG = "FORENSIC_DATA"
    }

    fun sendNoteOn(ch: Int, note: Int, vel: Int) {
        if (FORENSIC_TX_LOG) {
            val tMs = clock?.nowMs()
            if (tMs != null && logger != null) {
                val midiCh = (ch and 0x0F) + 1
                logger.log(FORENSIC_TAG, "$tMs,MIDI_TX,NOTE_ON,$midiCh,$note,$vel")
            }
        }
        send3(0x90 or (ch and 0x0F), note, vel)
    }

    fun sendNoteOff(ch: Int, note: Int) {
        if (FORENSIC_TX_LOG) {
            val tMs = clock?.nowMs()
            if (tMs != null && logger != null) {
                val midiCh = (ch and 0x0F) + 1
                logger.log(FORENSIC_TAG, "$tMs,MIDI_TX,NOTE_OFF,$midiCh,$note,0")
            }
        }
        send3(0x80 or (ch and 0x0F), note, 0)
    }

    fun sendChannelPressure(ch: Int, pressure: Int) {
        val p = pressure.coerceIn(0, 127)
        sink.send2(0xD0 or (ch and 0x0F), p)
    }

    fun sendPitchBend(ch: Int, bend14: Int) {
        val v = bend14.coerceIn(0, 16383)
        val lsb = v and 0x7F
        val msb = (v shr 7) and 0x7F
        sink.send3(0xE0 or (ch and 0x0F), lsb, msb)
    }

    fun sendCC(ch: Int, cc: Int, value: Int) {
        val c = cc.coerceIn(0, 127)
        val v = value.coerceIn(0, 127)
        send3(0xB0 or (ch and 0x0F), c, v)
    }

    private fun send3(status: Int, d1: Int, d2: Int) {
        sink.send3(status, d1, d2)
    }

    fun close() {
        sink.close()
    }
}
