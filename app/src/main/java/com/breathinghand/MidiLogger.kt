package com.breathinghand

import android.os.SystemClock
import android.util.Log
import com.breathinghand.core.HarmonicState

object MidiLogger {
    private const val TAG = "FORENSIC_DATA"
    // Fix: Local constant to bypass unresolved BuildConfig
    private const val IS_DEBUG = true

    private val ENABLED = IS_DEBUG

    fun logCommit(state: HarmonicState) {
        if (!ENABLED) return
        val tMs = SystemClock.uptimeMillis()
        Log.d(TAG, "$tMs,MIDI_COMMIT,${state.root},${state.quality},${state.density}")
    }

    fun logAllNotesOff(reason: String) {
        if (!ENABLED) return
        val tMs = SystemClock.uptimeMillis()
        val safeReason = reason.replace(",", ";")
        Log.d(TAG, "$tMs,MIDI_ALL_OFF,$safeReason")
    }
}