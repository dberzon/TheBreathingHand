package com.breathinghand.core.midi

import com.breathinghand.audio.OboeSynthesizer

class OboeMidiSink(private val synth: OboeSynthesizer) : MidiSink {
    override fun send3(status: Int, data1: Int, data2: Int) {
        val command = status and 0xF0
        val channel = status and 0x0F
        when (command) {
            0x80 -> synth.noteOff(channel, data1)
            0x90 -> {
                if (data2 == 0) {
                    synth.noteOff(channel, data1)
                } else {
                    synth.noteOn(channel, data1, data2)
                }
            }
            0xB0 -> synth.controlChange(channel, data1, data2)
            0xE0 -> {
                val bend14 = (data2 shl 7) or data1
                synth.pitchBend(channel, bend14)
            }
        }
    }

    override fun send2(status: Int, data1: Int) {
        val command = status and 0xF0
        val channel = status and 0x0F
        if (command == 0xD0) {
            synth.channelPressure(channel, data1)
        }
    }

    override fun close() {
        synth.close()
    }
}
