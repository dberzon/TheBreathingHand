package com.breathinghand.core

import android.view.MotionEvent

/**
 * Android "Nerves" driver:
 * MotionEvent -> SmartInputHAL -> TouchFrame
 *
 * No allocations per ingest().
 */
class AndroidTouchDriver(
    maxSlots: Int = MusicalConstants.MAX_VOICES
) {
    private val hal = SmartInputHAL(maxSlots = maxSlots)
    val frame = TouchFrame(maxVoices = maxSlots)

    /**
     * Optional expansion signal (0..1). Will be used on subsequent ingests,
     * same as your current flow.
     */
    var expansion01: Float
        get() = hal.expansion01
        set(v) { hal.expansion01 = v }

    fun ingest(ev: MotionEvent): TouchFrame {
        hal.ingest(ev)

        frame.tMs = ev.eventTime
        frame.activeCount = hal.activeCount

        val n = frame.pointerIds.size
        for (s in 0 until n) {
            frame.pointerIds[s] = hal.slotPointerId[s]
            frame.x[s] = hal.x[s]
            frame.y[s] = hal.y[s]
            frame.force01[s] = hal.force[s]
            frame.pressure[s] = hal.rawPressure[s]
            frame.size[s] = hal.rawSize[s]
            frame.flags[s] = hal.flags[s]
        }
        return frame
    }
}
