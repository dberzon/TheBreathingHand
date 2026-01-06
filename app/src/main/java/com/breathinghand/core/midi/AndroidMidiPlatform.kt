package com.breathinghand.core.midi

import android.os.SystemClock
import android.util.Log

object AndroidForensicLogger : ForensicLogger {
    override fun log(tag: String, message: String) {
        Log.d(tag, message)
    }
}

object AndroidMonotonicClock : MonotonicClock {
    override fun nowMs(): Long = SystemClock.uptimeMillis()
}
