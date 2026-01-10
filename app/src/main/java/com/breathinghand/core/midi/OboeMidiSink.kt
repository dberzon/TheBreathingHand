package com.breathinghand.core.midi

import com.breathinghand.audio.OboeSynthesizer

class OboeMidiSink(private val synth: OboeSynthesizer) : MidiSink {
    private val MAX_SYNTH_VOICES = 8

    // Allocation maps used when receiving on Standard MIDI channel (0):
    // note -> voice index, voice -> note
    private val noteToVoice = IntArray(128) { -1 }
    private val voiceToNote = IntArray(MAX_SYNTH_VOICES) { -1 }
    private val voiceActive = BooleanArray(MAX_SYNTH_VOICES) { false }
    private var nextAlloc = 0

    private fun mapChannelToSynth(channel: Int): Int {
        // MIDI uses 0-based internal channel numbers, but humans refer to channels 1..16.
        // Our synth engine expects channels 0..7 for 8-voice polyphony; map human 1..8 -> 0..7.
        return when (channel) {
            in 1..MAX_SYNTH_VOICES -> channel - 1
            else -> channel
        }
    }

    override fun send3(status: Int, data1: Int, data2: Int) {
        val command = status and 0xF0
        val channel = status and 0x0F

        // If Standard MIDI (channel 0), perform internal allocation for polyphony.
        if (channel == 0) {
            when (command) {
                0x80 -> { // Note Off
                    val note = data1
                    val v = noteToVoice.getOrNull(note) ?: -1
                    if (v != -1) {
                        synth.noteOff(v, note)
                        noteToVoice[note] = -1
                        voiceToNote[v] = -1
                        voiceActive[v] = false
                    }
                }
                0x90 -> { // Note On
                    val note = data1
                    val vel = data2
                    if (vel == 0) {
                        // NoteOn with zero velocity -> NoteOff
                        val v = noteToVoice.getOrNull(note) ?: -1
                        if (v != -1) {
                            synth.noteOff(v, note)
                            noteToVoice[note] = -1
                            voiceToNote[v] = -1
                            voiceActive[v] = false
                        }
                        return
                    }

                    // If this note already has a voice, retrigger it
                    var v = noteToVoice.getOrNull(note) ?: -1
                    if (v == -1) {
                        // Find free voice
                        var found = -1
                        for (i in 0 until MAX_SYNTH_VOICES) {
                            val idx = (nextAlloc + i) % MAX_SYNTH_VOICES
                            if (!voiceActive[idx]) {
                                found = idx
                                break
                            }
                        }
                        if (found == -1) {
                            // Steal nextAlloc
                            found = nextAlloc
                            // If it had a note, turn it off and clear mapping
                            val oldNote = voiceToNote[found]
                            if (oldNote != -1) {
                                synth.noteOff(found, oldNote)
                                noteToVoice[oldNote] = -1
                            }
                        }
                        v = found
                        voiceActive[v] = true
                        voiceToNote[v] = note
                        noteToVoice[note] = v
                        nextAlloc = (v + 1) % MAX_SYNTH_VOICES
                    }
                    synth.noteOn(v, note, vel)
                }
                0xB0 -> { // CC
                    val cc = data1
                    val value = data2
                    if (cc == 71) {
                        // CC71 -> filter cutoff for all voices
                        val vNorm = value / 127.0
                        val cutoff = (20.0 * Math.pow(12000.0 / 20.0, vNorm)).toFloat()
                        for (i in 0 until MAX_SYNTH_VOICES) {
                            synth.setFilterCutoff(i, cutoff)
                        }
                    } else {
                        // Forward other CCs to all voices
                        for (i in 0 until MAX_SYNTH_VOICES) {
                            synth.controlChange(i, cc, value)
                        }
                    }
                }
                0xE0 -> { // Pitch Bend
                    val bend14 = (data2 shl 7) or data1
                    for (i in 0 until MAX_SYNTH_VOICES) {
                        synth.pitchBend(i, bend14)
                    }
                }
            }
            return
        }

        // MPE / explicit channel mapping
        val synthChannel = mapChannelToSynth(channel)
        when (command) {
            0x80 -> synth.noteOff(synthChannel, data1)
            0x90 -> {
                if (data2 == 0) {
                    synth.noteOff(synthChannel, data1)
                } else {
                    synth.noteOn(synthChannel, data1, data2)
                }
            }
            0xB0 -> {
                // CC mappings: CC71 -> Filter Cutoff (Hz), CC74 -> brightness already forwarded
                val cc = data1
                val value = data2
                if (cc == 71) {
                    // Map 0..127 -> 20Hz..12000Hz exponentially
                    val vNorm = value / 127.0
                    val cutoff = (20.0 * Math.pow(12000.0 / 20.0, vNorm)).toFloat()
                    synth.setFilterCutoff(synthChannel, cutoff)
                } else {
                    synth.controlChange(synthChannel, data1, data2)
                }
            }
            0xE0 -> {
                val bend14 = (data2 shl 7) or data1
                synth.pitchBend(synthChannel, bend14)
            }
        }
    }

    override fun send2(status: Int, data1: Int) {
        val command = status and 0xF0
        val channel = status and 0x0F

        if (channel == 0) {
            if (command == 0xD0) {
                // Channel pressure -> apply to all voices
                for (i in 0 until MAX_SYNTH_VOICES) {
                    synth.channelPressure(i, data1)
                }
            }
            return
        }

        val synthChannel = mapChannelToSynth(channel)
        if (command == 0xD0) {
            synth.channelPressure(synthChannel, data1)
        }
    }

    override fun close() {
        // Clear alloc maps
        for (n in 0 until noteToVoice.size) noteToVoice[n] = -1
        for (i in 0 until MAX_SYNTH_VOICES) {
            voiceToNote[i] = -1
            voiceActive[i] = false
        }
        synth.close()
    }
}
