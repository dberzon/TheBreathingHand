package com.breathinghand.core.midi

/**
 * KMP-safe MIDI message builder + sender.
 *
 * Hot-path contract:
 * - If FORENSIC_TX_LOG is false (default), this class is allocation-free in steady-state use.
 * - Forensic logging is opt-in and must remain OFF for performance validation.
 */
class MidiOut(
    private val sink: MidiSink,
    private val clock: MonotonicClock? = null,
    private val logger: ForensicLogger? = null
) {

    companion object {
        // Must remain FALSE by default (Rule 8: no hot-path overhead unless explicitly enabled)
        // FIX: Removed @JvmField (caused KMP compilation error)
        var FORENSIC_TX_LOG: Boolean = false
        private const val FORENSIC_TAG = "FORENSIC_MIDI"
    }

    fun sendNoteOn(ch: Int, note: Int, vel: Int) {
        val c = ch and 0x0F
        val n = note.coerceIn(0, 127)
        val v = vel.coerceIn(0, 127)
        logTx("NOTE_ON", c, n, v)
        sink.send3(0x90 or c, n, v)
    }

    fun sendNoteOff(ch: Int, note: Int) {
        val c = ch and 0x0F
        val n = note.coerceIn(0, 127)
        logTx("NOTE_OFF", c, n, 0)
        sink.send3(0x80 or c, n, 0)
    }

    fun sendPitchBend(ch: Int, bend14: Int) {
        val c = ch and 0x0F
        val b = bend14.coerceIn(0, 16383)
        val lsb = b and 0x7F
        val msb = (b shr 7) and 0x7F
        logTx("PITCH_BEND", c, lsb, msb)
        sink.send3(0xE0 or c, lsb, msb)
    }

    fun sendChannelPressure(ch: Int, pressure: Int) {
        val c = ch and 0x0F
        val p = pressure.coerceIn(0, 127)
        logTx("CH_AFTERTOUCH", c, p, -1)
        sink.send2(0xD0 or c, p)
    }

    fun sendCC(ch: Int, cc: Int, value: Int) {
        val c = ch and 0x0F
        val num = cc.coerceIn(0, 127)
        val v = value.coerceIn(0, 127)
        logTx("CC", c, num, v)
        sink.send3(0xB0 or c, num, v)
    }

    fun close() {
        sink.close()
    }

    private fun logTx(kind: String, ch: Int, a: Int, b: Int) {
        if (!FORENSIC_TX_LOG) return
        val tMs = clock?.nowMs() ?: return
        val lg = logger ?: return

        // midiCh is 1-based for humans/logs
        val midiCh = (ch and 0x0F) + 1

        // NOTE: This allocates when enabled, by design (forensic mode only).
        if (b >= 0) {
            lg.log(FORENSIC_TAG, "$tMs,MIDI_TX,$kind,$midiCh,$a,$b")
        } else {
            lg.log(FORENSIC_TAG, "$tMs,MIDI_TX,$kind,$midiCh,$a")
        }
    }
}
