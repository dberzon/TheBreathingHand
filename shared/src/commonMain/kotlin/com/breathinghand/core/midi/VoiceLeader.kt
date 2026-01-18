package com.breathinghand.core.midi

import com.breathinghand.core.HarmonicState
import com.breathinghand.core.MusicalConstants
import com.breathinghand.engine.GestureAnalyzer

// Debug logging: platform-specific implementation via expect/actual
// Zero-alloc when disabled (guarded by MusicalConstants.IS_DEBUG)
internal expect object DebugLogger {
    fun logSlotChannelMapping(slot: Int, channel: Int, isActive: Boolean)
    fun logNoteTransition(slot: Int, channel: Int, oldNote: Int, newNote: Int, reason: String)
    fun logCascadeState(releaseActive: Boolean, landingActive: Boolean)
}

/**
 * VOICE LEADER v0.2
 *
 * Policies:
 * - Slot Stability: Channels 2-6 fixed to Slots 0-4.
 * - Reserved Channel: Channel 1 is Global (unused here).
 * - Silence Over Guessing: NONE archetype -> Silence.
 * - Platform Agnostic: Uses SlotPresence interface.
 */
class VoiceLeader {

    companion object {
        private const val INSTABILITY_THRESHOLD = MusicalConstants.INSTABILITY_THRESHOLD
        private const val CHANNEL_OFFSET = 1
    }

    // State Tracking (Zero-Alloc)
    private val currentNotes = IntArray(MusicalConstants.MAX_VOICES) { -1 }
    private val slotVelocity = IntArray(MusicalConstants.MAX_VOICES) { 100 }
    private val slotBend = IntArray(MusicalConstants.MAX_VOICES) { 8192 }
    private val slotCC74 = IntArray(MusicalConstants.MAX_VOICES) { 64 }
    private val slotAftertouch = IntArray(MusicalConstants.MAX_VOICES) { 0 }

    // Hold last non-NONE archetype values (prevents dead-state stutter)
    private var lastTriadNonNone = GestureAnalyzer.TRIAD_FAN
    private var lastSeventhNonNone = GestureAnalyzer.SEVENTH_COMPACT

    // Sent State (for diffing)
    private val sentBend = IntArray(MusicalConstants.MAX_VOICES) { -1 }
    private val sentCC74 = IntArray(MusicalConstants.MAX_VOICES) { -1 }
    private val sentAftertouch = IntArray(MusicalConstants.MAX_VOICES) { -1 }

    private var landingCascadeActive = false
    private var releaseCascadeActive = false

    // Store midiOutput reference for allNotesOff() (set during first process() call)
    private var midiOutputRef: MidiOutput? = null

    // API called by MainActivity
    fun setSlotVelocity(slot: Int, pointerId: Int, velocity: Int) {
        if (slot in slotVelocity.indices) slotVelocity[slot] = velocity
    }
    fun setSlotPitchBend(slot: Int, pointerId: Int, bend: Int) {
        if (slot in slotBend.indices) slotBend[slot] = bend
    }
    fun setSlotCC74(slot: Int, pointerId: Int, value: Int) {
        if (slot in slotCC74.indices) slotCC74[slot] = value
    }
    fun setSlotAftertouch(slot: Int, pointerId: Int, value: Int) {
        if (slot in slotAftertouch.indices) slotAftertouch[slot] = value
    }
    fun setLandingCascadeActive(active: Boolean) {
        if (landingCascadeActive != active) {
            landingCascadeActive = active
            DebugLogger.logCascadeState(releaseCascadeActive, landingCascadeActive)
        }
    }
    fun setReleaseCascadeActive(active: Boolean) {
        if (releaseCascadeActive != active) {
            releaseCascadeActive = active
            DebugLogger.logCascadeState(releaseCascadeActive, landingCascadeActive)
        }
    }
    fun close() { allNotesOff() }

    /**
     * Sends note-off for all active notes and resets state.
     * CRITICAL FIX: Previously only cleared array without sending MIDI messages.
     */
    fun allNotesOff() {
        val output = midiOutputRef
        if (output != null) {
            for (i in 0 until MusicalConstants.MAX_VOICES) {
                val current = currentNotes[i]
                if (current != -1) {
                    val channel = i + CHANNEL_OFFSET
                    output.sendNoteOff(channel, current, 0)
                    currentNotes[i] = -1
                }
            }
            // Reset sent state to force re-send on next activation
            for (i in sentBend.indices) {
                sentBend[i] = -1
                sentCC74[i] = -1
                sentAftertouch[i] = -1
            }
        } else {
            // Fallback: clear array even if midiOutput not yet set
            for (i in currentNotes.indices) currentNotes[i] = -1
        }
    }

    /**
     * Main Process Loop.
     * @param state The current harmonic state.
     * @param slots The physical truth (platform-agnostic interface).
     * @param midiOutput Interface to send MIDI events.
     */
    fun process(state: HarmonicState, slots: SlotPresence, midiOutput: MidiOutput) {
        // Store reference for allNotesOff() (zero-alloc: just pointer assignment)
        if (midiOutputRef == null) midiOutputRef = midiOutput
        
        val allowAttack = !landingCascadeActive && !releaseCascadeActive

        for (i in 0 until MusicalConstants.MAX_VOICES) {
            val channel = i + CHANNEL_OFFSET
            val isActive = slots.isSlotActive(i)

            // Debug: Log slot-channel mapping (zero-alloc when disabled)
            DebugLogger.logSlotChannelMapping(i, channel, isActive)

            // 1. Expression Diffing (Always Active)
            if (isActive) {
                if (slotBend[i] != sentBend[i]) {
                    midiOutput.sendPitchBend(channel, slotBend[i])
                    sentBend[i] = slotBend[i]
                }
                if (slotCC74[i] != sentCC74[i]) {
                    midiOutput.sendControlChange(channel, 74, slotCC74[i])
                    sentCC74[i] = slotCC74[i]
                }
                if (slotAftertouch[i] != sentAftertouch[i]) {
                    midiOutput.sendChannelPressure(channel, slotAftertouch[i])
                    sentAftertouch[i] = slotAftertouch[i]
                }
            } else {
                sentBend[i] = -1; sentCC74[i] = -1; sentAftertouch[i] = -1
            }

            // 2. Determine Target Note
            val targetNote = if (isActive) {
                calculateTargetNote(i, state)
            } else {
                -1
            }

            // 3. Diff & Send
            val current = currentNotes[i]

            if (targetNote != current) {
                val reason = when {
                    current != -1 && targetNote == -1 -> "LIFT"
                    current == -1 && targetNote != -1 -> "ATTACK"
                    current != -1 && targetNote != -1 -> "CHANGE"
                    else -> "UNKNOWN"
                }
                DebugLogger.logNoteTransition(i, channel, current, targetNote, reason)
                
                if (current != -1) {
                    midiOutput.sendNoteOff(channel, current, 0)
                }
                if (targetNote != -1 && allowAttack) {
                    midiOutput.sendNoteOn(channel, targetNote, slotVelocity[i])
                }
                currentNotes[i] = if (allowAttack) targetNote else -1
            }
        }
    }

    private fun calculateTargetNote(slotIndex: Int, state: HarmonicState): Int {
        val baseMidi = 48 + state.rootPc 
        val isUnstable = state.harmonicInstability >= INSTABILITY_THRESHOLD

        if (isUnstable) {
            return when (slotIndex) {
                0 -> baseMidi + 0              // Root
                1 -> baseMidi + 3              // Minor 3rd
                2 -> baseMidi + 6              // Dim 5th
                3 -> baseMidi + 9              // Dim 7th
                else -> -1
            }
        }

        return when (slotIndex) {
            0 -> baseMidi + 0              // Root
            1 -> baseMidi + 7              // Perfect 5th
            2 -> { // Triad Layer (hold last non-NONE)
                val effectiveTriad = if (state.triad != GestureAnalyzer.TRIAD_NONE) {
                    lastTriadNonNone = state.triad
                    state.triad
                } else {
                    lastTriadNonNone
                }
                when (effectiveTriad) {
                    GestureAnalyzer.TRIAD_FAN     -> baseMidi + 4  // Major
                    GestureAnalyzer.TRIAD_STRETCH -> baseMidi + 3  // Minor
                    GestureAnalyzer.TRIAD_CLUSTER -> baseMidi + 5  // Sus4
                    else -> -1 // Shouldn't reach here
                }
            }
            3 -> { // Seventh Layer (hold last non-NONE)
                val effectiveSeventh = if (state.seventh != GestureAnalyzer.SEVENTH_NONE) {
                    lastSeventhNonNone = state.seventh
                    state.seventh
                } else {
                    lastSeventhNonNone
                }
                when (effectiveSeventh) {
                    GestureAnalyzer.SEVENTH_COMPACT -> baseMidi + 11 // Major 7
                    GestureAnalyzer.SEVENTH_WIDE    -> baseMidi + 10 // Minor 7
                    else -> -1 // Shouldn't reach here
                }
            }
            else -> -1
        }
    }
}
