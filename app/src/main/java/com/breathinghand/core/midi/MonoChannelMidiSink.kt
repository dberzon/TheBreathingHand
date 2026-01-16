package com.breathinghand.core.midi

/**
 * Rule 3: Modification Over Replacement — Maps multi-channel MIDI to mono channel 0, preserving active voices.
 * Wraps a MidiSink and forwards all NOTE ON/OFF messages to channel 0 (mono internal channel).
 * For continuous controllers (Pitch Bend, Channel Pressure, CC) only forwards messages that
 * originate on channel 0 (standard) or channel 1 (slot 0 in MPE) to avoid "fighting" across
 * multiple fingers. This enforces mono-expression for the internal synth while allowing
 * external sinks to receive the original (possibly multi-channel) messages.
 */
class MonoChannelMidiSink(private val inner: MidiSink) : MidiSink {

    override fun send3(status: Int, data1: Int, data2: Int) {
        val cmd = status and 0xF0
        val ch = status and 0x0F

        when (cmd) {
            0x90, 0x80 -> {
                // Rule 3: Modification Over Replacement — Maps to channel 0, updates active voices only.
                // Rule 13: Performance Is a Musical Requirement — Zero allocation hot path (no string allocation).
                // Note On / Note Off -> always map to channel 0 for internal synth
                // REMOVED: Log.d call to prevent string allocation in hot path
                val s = cmd or 0
                inner.send3(s, data1, data2)
            }
            0xB0 -> {
                // Rule 13: Performance Is a Musical Requirement — Zero allocation hot path (no string allocation).
                // CC -> only accept from primary (channel 0) or MPE slot 0 (channel 1)
                if (ch == 0 || ch == 1) {
                    // REMOVED: Log.d call to prevent string allocation in hot path
                    val s = 0xB0 or 0
                    inner.send3(s, data1, data2)
                } else {
                    // REMOVED: Log.d call to prevent string allocation in hot path
                }
            }
            0xE0 -> {
                // Rule 13: Performance Is a Musical Requirement — Zero allocation hot path (no string allocation).
                // Pitch Bend -> only accept from primary
                if (ch == 0 || ch == 1) {
                    // REMOVED: Log.d call to prevent string allocation in hot path
                    val s = 0xE0 or 0
                    inner.send3(s, data1, data2)
                } else {
                    // REMOVED: Log.d call to prevent string allocation in hot path
                }
            }
            else -> {
                // Fallback: map channel to 0 and forward
                // REMOVED: Log.d call to prevent string allocation in hot path
                val s = (cmd) or 0
                inner.send3(s, data1, data2)
            }
        }
    }

    override fun send2(status: Int, data1: Int) {
        val cmd = status and 0xF0
        val ch = status and 0x0F
        if (cmd == 0xD0) {
            // Rule 13: Performance Is a Musical Requirement — Zero allocation hot path (no string allocation).
            // Channel pressure -> only accept from primary
            if (ch == 0 || ch == 1) {
                // REMOVED: Log.d call to prevent string allocation in hot path
                inner.send2(0xD0 or 0, data1)
            } else {
                // REMOVED: Log.d call to prevent string allocation in hot path
            }
            return
        }
        // Rule 13: Performance Is a Musical Requirement — Zero allocation hot path (no string allocation).
        // Otherwise forward, mapped to channel 0
        // REMOVED: Log.d call to prevent string allocation in hot path
        inner.send2(status and 0xF0 or 0, data1)
    }

    override fun close() {
        inner.close()
    }
}
