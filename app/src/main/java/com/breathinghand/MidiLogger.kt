package com.breathinghand

import android.os.SystemClock
import android.util.Log
import com.breathinghand.core.HarmonicState
import com.breathinghand.core.midi.MusicalConstants

object MidiLogger {
    private const val TAG = "FORENSIC_DATA"
    private const val TAG_INVARIANT = "BH_INVARIANT"

    // LINKED TO SINGLE SOURCE OF TRUTH IN MusicalConstants
    private const val ENABLED = MusicalConstants.IS_DEBUG

    fun logHarmony(state: HarmonicState) {
        if (!ENABLED) return
        val tMs = SystemClock.uptimeMillis()
        // String concatenation only happens if ENABLED is true
        Log.d(TAG, "$tMs,HARMONY,sector=${state.functionSector},pc=${state.rootPc},fc=${state.fingerCount}")
    }

    fun logAllNotesOff(reason: String) {
        if (!ENABLED) return
        val tMs = SystemClock.uptimeMillis()
        val safeReason = reason.replace(",", ";")
        Log.d(TAG, "$tMs,MIDI_ALL_OFF,$safeReason")
    }

    /**
     * Lightweight invariant check: slot-to-channel mapping.
     * Zero-alloc when disabled (guarded by ENABLED).
     */
    fun logSlotChannelMapping(slot: Int, channel: Int, isActive: Boolean) {
        if (!ENABLED) return
        val tMs = SystemClock.uptimeMillis()
        Log.d(TAG_INVARIANT, "$tMs,SLOT_CH,$slot,$channel,$isActive")
    }

    /**
     * Lightweight invariant check: note state transition.
     * Zero-alloc when disabled (guarded by ENABLED).
     */
    fun logNoteTransition(slot: Int, channel: Int, oldNote: Int, newNote: Int, reason: String) {
        if (!ENABLED) return
        val tMs = SystemClock.uptimeMillis()
        val safeReason = reason.replace(",", ";")
        Log.d(TAG_INVARIANT, "$tMs,NOTE_TRANS,$slot,$channel,$oldNote,$newNote,$safeReason")
    }

    /**
     * Lightweight invariant check: cascade state change.
     * Zero-alloc when disabled (guarded by ENABLED).
     */
    fun logCascadeState(releaseActive: Boolean, landingActive: Boolean) {
        if (!ENABLED) return
        val tMs = SystemClock.uptimeMillis()
        Log.d(TAG_INVARIANT, "$tMs,CASCADE,$releaseActive,$landingActive")
    }
}