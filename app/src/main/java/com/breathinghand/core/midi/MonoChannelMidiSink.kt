package com.breathinghand.core.midi

import android.util.Log

/**
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
                // Note On / Note Off -> always map to channel 0 for internal synth
                Log.d("BreathingHand", "MonoChannelMidiSink: NOTE ${if (cmd==0x90) "ON" else "OFF"} from ch $ch -> ch 0 note=$data1 vel=$data2")
                val s = cmd or 0
                inner.send3(s, data1, data2)
            }
            0xB0 -> {
                // CC -> only accept from primary (channel 0) or MPE slot 0 (channel 1)
                if (ch == 0 || ch == 1) {
                    Log.d("BreathingHand", "MonoChannelMidiSink: CC from ch $ch -> ch 0 cc=${data1} val=${data2}")
                    val s = 0xB0 or 0
                    inner.send3(s, data1, data2)
                } else {
                    Log.d("BreathingHand", "MonoChannelMidiSink: CC ignored from ch $ch")
                }
            }
            0xE0 -> {
                // Pitch Bend -> only accept from primary
                if (ch == 0 || ch == 1) {
                    Log.d("BreathingHand", "MonoChannelMidiSink: PitchBend from ch $ch -> ch 0 lsb=${data1} msb=${data2}")
                    val s = 0xE0 or 0
                    inner.send3(s, data1, data2)
                } else {
                    Log.d("BreathingHand", "MonoChannelMidiSink: PitchBend ignored from ch $ch")
                }
            }
            else -> {
                // Fallback: map channel to 0 and forward
                Log.d("BreathingHand", "MonoChannelMidiSink: Fallback cmd=0x${cmd.toString(16)} from ch $ch")
                val s = (cmd) or 0
                inner.send3(s, data1, data2)
            }
        }
    }

    override fun send2(status: Int, data1: Int) {
        val cmd = status and 0xF0
        val ch = status and 0x0F
        if (cmd == 0xD0) {
            // Channel pressure -> only accept from primary
            if (ch == 0 || ch == 1) {
                Log.d("BreathingHand", "MonoChannelMidiSink: ChannelPressure from ch $ch -> ch 0 val=$data1")
                inner.send2(0xD0 or 0, data1)
            } else {
                Log.d("BreathingHand", "MonoChannelMidiSink: ChannelPressure ignored from ch $ch")
            }
            return
        }
        // Otherwise forward, mapped to channel 0
        Log.d("BreathingHand", "MonoChannelMidiSink: send2 fallback cmd=0x${cmd.toString(16)}")
        inner.send2(status and 0xF0 or 0, data1)
    }

    override fun close() {
        inner.close()
    }
}
