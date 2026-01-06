package com.breathinghand.core

import com.breathinghand.core.midi.MidiOut

/**
 * Slot-stable MPE voice (slot index == voice index).
 * PointerId binding is absolute: slot i is always MIDI channel (i+1).
 *
 * KMP-safe: no android.* imports, no MotionEvent usage.
 * Zero allocations in update hot path.
 */
private data class VoiceSlot(
    val channel: Int,
    var note: Int = 0,
    var active: Boolean = false,
    var pointerId: Int = TouchFrame.INVALID_ID
)

class VoiceLeader(private val midi: MidiOut) {

    // Slot index == voice index. Channel is fixed per slot for "sticky" MPE behavior.
    // channel=1 means "MIDI channel 2" in human terms if you reserve ch1 for global.
    private val voices = Array(MusicalConstants.MAX_VOICES) { i -> VoiceSlot(channel = i + 1) }

    // Target note per slot (0 means "silent / no target").
    private val targetNotes = IntArray(MusicalConstants.MAX_VOICES)

    // PointerId -> velocity lookup (O(1), no allocations).
    private val velocityByPointerId =
        IntArray(MusicalConstants.MAX_POINTER_ID + 1) { MusicalConstants.DEFAULT_VELOCITY }

    // Continuous per-slot messages (no allocations).
    private val pendingAftertouch = IntArray(MusicalConstants.MAX_VOICES)
    private val lastSentAftertouch = IntArray(MusicalConstants.MAX_VOICES) { -1 }

    private val pendingPitchBend =
        IntArray(MusicalConstants.MAX_VOICES) { MusicalConstants.CENTER_PITCH_BEND }
    private val lastSentPitchBend = IntArray(MusicalConstants.MAX_VOICES) { -1 }

    private val pendingCC74 =
        IntArray(MusicalConstants.MAX_VOICES) { MusicalConstants.CENTER_CC74 }
    private val lastSentCC74 = IntArray(MusicalConstants.MAX_VOICES) { -1 }

    // Last committed harmony (stored as ints to avoid depending on HarmonicState constructor details).
    private var lastRoot = Int.MIN_VALUE
    private var lastQuality = Int.MIN_VALUE
    private var lastDensity = Int.MIN_VALUE

    /**
     * Primary update (called on each committed gesture tick).
     *
     * @param input Harmonic state (root/quality/density).
     * @param activePointerIds slot-aligned pointerIds; TouchFrame.INVALID_ID for empty slots.
     */
    fun update(input: HarmonicState, activePointerIds: IntArray) {
        val harmonicChanged =
            input.root != lastRoot || input.quality != lastQuality || input.density != lastDensity

        if (harmonicChanged) {
            lastRoot = input.root
            lastQuality = input.quality
            lastDensity = input.density
            updateHarmonicTargets(input)
        }

        updateAllocationBySlot(activePointerIds)

        // Always call: starts notes for newly active slots, updates notes if targets changed, releases silent slots.
        solveAndSendBySlot()

        // Flush continuous messages (allocation-free).
        flushAftertouch()
        flushPitchBend()
        flushCC74()
    }

    /**
     * Velocity latch. Also "primes" pointer->slot mapping if the slot has not been bound yet.
     */
    fun setSlotVelocity(slotIndex: Int, pointerId: Int, velocity: Int) {
        if (slotIndex !in 0 until MusicalConstants.MAX_VOICES) return
        val pidOk = pointerId in 0 until velocityByPointerId.size
        if (pidOk) velocityByPointerId[pointerId] = velocity.coerceIn(1, 127)

        // Prime mapping only if empty; never override an existing binding here.
        val v = voices[slotIndex]
        if (v.pointerId == TouchFrame.INVALID_ID && pointerId != TouchFrame.INVALID_ID) {
            v.pointerId = pointerId
        }
    }

    fun setSlotAftertouch(slotIndex: Int, pointerId: Int, value: Int) {
        if (slotIndex !in 0 until MusicalConstants.MAX_VOICES) return
        pendingAftertouch[slotIndex] = value.coerceIn(0, 127)

        val v = voices[slotIndex]
        if (v.pointerId == TouchFrame.INVALID_ID && pointerId != TouchFrame.INVALID_ID) {
            v.pointerId = pointerId
        }
    }

    fun setSlotPitchBend(slotIndex: Int, pointerId: Int, bend14: Int) {
        if (slotIndex !in 0 until MusicalConstants.MAX_VOICES) return
        pendingPitchBend[slotIndex] = bend14.coerceIn(0, 16383)

        val v = voices[slotIndex]
        if (v.pointerId == TouchFrame.INVALID_ID && pointerId != TouchFrame.INVALID_ID) {
            v.pointerId = pointerId
        }
    }

    fun setSlotCC74(slotIndex: Int, pointerId: Int, value: Int) {
        if (slotIndex !in 0 until MusicalConstants.MAX_VOICES) return
        pendingCC74[slotIndex] = value.coerceIn(0, 127)

        val v = voices[slotIndex]
        if (v.pointerId == TouchFrame.INVALID_ID && pointerId != TouchFrame.INVALID_ID) {
            v.pointerId = pointerId
        }
    }

    fun flushAftertouch() {
        for (i in 0 until MusicalConstants.MAX_VOICES) {
            val v = pendingAftertouch[i]
            if (v == lastSentAftertouch[i]) continue
            lastSentAftertouch[i] = v
            midi.sendChannelPressure(voices[i].channel, v)
        }
    }

    private fun flushPitchBend() {
        for (i in 0 until MusicalConstants.MAX_VOICES) {
            val v = pendingPitchBend[i]
            if (v == lastSentPitchBend[i]) continue
            lastSentPitchBend[i] = v
            midi.sendPitchBend(voices[i].channel, v)
        }
    }

    private fun flushCC74() {
        for (i in 0 until MusicalConstants.MAX_VOICES) {
            val v = pendingCC74[i]
            if (v == lastSentCC74[i]) continue
            lastSentCC74[i] = v
            midi.sendCC(voices[i].channel, 74, v)
        }
    }

    /**
     * Builds per-slot targets from harmony.
     *
     * Assumes ChordTable.get(quality, density) supports density in the same domain your HarmonicEngine produces.
     * In your current working system this is density=3..5 (triad/7th/9th).
     */
    private fun updateHarmonicTargets(input: HarmonicState) {
        val fifthsRoot = (input.root * 7) % 12
        val chromaticRoot = 60 + fifthsRoot

        val intervals = ChordTable.get(input.quality, input.density)
        val count = intervals.size

        // Fill targets; anything above chord-tone count is silent.
        for (i in 0 until MusicalConstants.MAX_VOICES) {
            targetNotes[i] = if (i < count) chromaticRoot + intervals[i] else 0
        }

        // Ensure strictly ascending for the active chord tones.
        for (i in 1 until count) {
            var t = targetNotes[i]
            val prev = targetNotes[i - 1]
            while (t <= prev) t += 12
            targetNotes[i] = t
        }

        // Keep voicing in a sane range if it drifts too high.
        if (count > 0 && targetNotes[count - 1] > 96) {
            for (i in 0 until count) targetNotes[i] -= 12
        }
    }

    /**
     * Slot-stable binding:
     * - Each slot reads activePointerIds[slot] (or INVALID_ID).
     * - PointerId changes in a slot force an immediate note-off (deterministic).
     */
    private fun updateAllocationBySlot(activePointerIds: IntArray) {
        val n = minOf(activePointerIds.size, MusicalConstants.MAX_VOICES)

        for (slot in 0 until MusicalConstants.MAX_VOICES) {
            val wantPid = if (slot < n) activePointerIds[slot] else TouchFrame.INVALID_ID
            val v = voices[slot]
            val havePid = v.pointerId

            if (wantPid == TouchFrame.INVALID_ID) {
                // Slot empty -> release and clear binding.
                if (v.active) {
                    midi.sendNoteOff(v.channel, v.note)
                    v.active = false
                    v.note = 0
                }
                v.pointerId = TouchFrame.INVALID_ID
                continue
            }

            // Slot occupied.
            if (havePid == TouchFrame.INVALID_ID) {
                v.pointerId = wantPid
                continue
            }

            if (havePid != wantPid) {
                // Different pointer landed in this slot -> deterministic rebind.
                if (v.active) {
                    midi.sendNoteOff(v.channel, v.note)
                    v.active = false
                    v.note = 0
                }
                v.pointerId = wantPid
            }
        }
    }

    /**
     * Chord-tone assignment by slot:
     * slot i -> targetNotes[i]
     *
     * No sorting, no allocations.
     */
    private fun solveAndSendBySlot() {
        for (slot in 0 until MusicalConstants.MAX_VOICES) {
            val v = voices[slot]
            val pid = v.pointerId
            if (pid == TouchFrame.INVALID_ID) continue

            val target = targetNotes[slot]
            if (target == 0) {
                // Slot has a finger but no target tone in current density -> ensure silent.
                if (v.active) {
                    midi.sendNoteOff(v.channel, v.note)
                    v.active = false
                    v.note = 0
                }
                continue
            }

            if (!v.active) {
                val vel = if (pid in 0 until velocityByPointerId.size)
                    velocityByPointerId[pid]
                else
                    MusicalConstants.DEFAULT_VELOCITY

                v.note = target
                v.active = true
                midi.sendNoteOn(v.channel, target, vel)
                continue
            }

            if (v.note != target) {
                midi.sendNoteOff(v.channel, v.note)

                val vel = if (pid in 0 until velocityByPointerId.size)
                    velocityByPointerId[pid]
                else
                    MusicalConstants.DEFAULT_VELOCITY

                v.note = target
                midi.sendNoteOn(v.channel, target, vel)
            }
        }
    }

    fun allNotesOff() {
        for (i in 0 until MusicalConstants.MAX_VOICES) {
            val v = voices[i]
            if (v.active) {
                midi.sendNoteOff(v.channel, v.note)
            }

            // Reset channel state (deterministic cleanup).
            midi.sendChannelPressure(v.channel, 0)
            midi.sendPitchBend(v.channel, MusicalConstants.CENTER_PITCH_BEND)
            midi.sendCC(v.channel, 74, MusicalConstants.CENTER_CC74)

            v.note = 0
            v.active = false
            v.pointerId = TouchFrame.INVALID_ID

            pendingAftertouch[i] = 0
            lastSentAftertouch[i] = -1

            pendingPitchBend[i] = MusicalConstants.CENTER_PITCH_BEND
            lastSentPitchBend[i] = -1

            pendingCC74[i] = MusicalConstants.CENTER_CC74
            lastSentCC74[i] = -1
        }
    }

    fun close() {
        allNotesOff()
        midi.close()
    }
}
