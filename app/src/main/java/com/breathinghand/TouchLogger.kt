package com.breathinghand

import android.util.Log
import android.view.MotionEvent
import java.util.Locale
import kotlin.math.max

object TouchLogger {
    private const val TAG = "FORENSIC_DATA"
    private const val ENABLED = true // <-- flip to false to disable logging
    private var lastMoveLogMs: Long = 0L

    fun log(
        event: MotionEvent,
        viewWidth: Int,
        viewHeight: Int,
        moveMinIntervalMs: Long = 16L,
        includeHistory: Boolean = false
    ) {
        if (!ENABLED) return

        val actionMasked = event.actionMasked
        val actionStr = actionToString(actionMasked)

        val tMsNow = event.eventTime // ms since boot (uptime)
        if (actionMasked == MotionEvent.ACTION_MOVE) {
            if (tMsNow - lastMoveLogMs < moveMinIntervalMs) return
            lastMoveLogMs = tMsNow
        }

        val w = max(viewWidth, 1)
        val h = max(viewHeight, 1)

        if (includeHistory && actionMasked == MotionEvent.ACTION_MOVE) {
            val hs = event.historySize
            for (histIdx in 0 until hs) {
                val tHist = event.getHistoricalEventTime(histIdx)
                logPointers(tHist, "MOVE_HIST", event, w, h, histIdx)
            }
        }

        logPointers(tMsNow, actionStr, event, w, h, -1)
    }

    private fun logPointers(
        tMs: Long,
        actionStr: String,
        event: MotionEvent,
        w: Int,
        h: Int,
        historyIdx: Int
    ) {
        val count = event.pointerCount

        for (i in 0 until count) {
            val pid = event.getPointerId(i)

            val x = if (historyIdx >= 0) event.getHistoricalX(i, historyIdx) else event.getX(i)
            val y = if (historyIdx >= 0) event.getHistoricalY(i, historyIdx) else event.getY(i)

            val pressure = event.getPressure(i)
            val size = event.getSize(i)

            val xNorm = x / w.toFloat()
            val yNorm = y / h.toFloat()

            val line = String.format(
                Locale.US,
                "%d,TOUCH_RAW,%s,%d,%d,%.2f,%.2f,%.5f,%.5f,%.4f,%.4f,%d",
                tMs, actionStr, pid, count,
                x, y, xNorm, yNorm,
                pressure, size, historyIdx
            )
            Log.d(TAG, line)
        }
    }

    private fun actionToString(actionMasked: Int): String {
        return when (actionMasked) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_POINTER_DOWN -> "PTR_DOWN"
            MotionEvent.ACTION_POINTER_UP -> "PTR_UP"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            else -> "OTHER"
        }
    }
}
