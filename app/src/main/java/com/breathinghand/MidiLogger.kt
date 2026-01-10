package com.breathinghand

import android.os.SystemClock
import android.util.Log
import com.breathinghand.core.HarmonicState
import com.breathinghand.core.MusicalConstants // Import added

object MidiLogger {
    private const val TAG = "FORENSIC_DATA"

    // REMOVED: private const val IS_DEBUG = true
    // LINKED TO SINGLE SOURCE OF TRUTH:
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
}