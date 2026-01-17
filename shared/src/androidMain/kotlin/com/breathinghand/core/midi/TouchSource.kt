package com.breathinghand.core.midi

import android.view.MotionEvent

/**
 * TOUCH SOURCE (Semantic Geometry)
 * Extracts raw coordinates directly from MotionEvent for semantic analysis.
 * Bypasses physics filters to guarantee coherence.
 */
object TouchSource {

    /**
     * Shared buffer for slot geometry [x0, y0, x1, y1, ...].
     * Size = MAX_VOICES * 2.
     */
    val slotGeometry = FloatArray(MusicalConstants.MAX_VOICES * 2) { Float.NaN }

    /**
     * Updates the slotGeometry buffer.
     * MUST be called immediately after VoiceAllocator.processEvent() with the SAME event.
     */
    fun update(event: MotionEvent, allocator: VoiceAllocator) {
        for (slotIndex in 0 until MusicalConstants.MAX_VOICES) {
            val pointerId = allocator.getPointerIdForSlot(slotIndex)
            val baseIdx = slotIndex * 2

            if (pointerId != VoiceAllocator.NO_POINTER) {
                val pointerIndex = event.findPointerIndex(pointerId)

                if (pointerIndex >= 0) {
                    slotGeometry[baseIdx]     = event.getX(pointerIndex)
                    slotGeometry[baseIdx + 1] = event.getY(pointerIndex)
                } else {
                    // Malformed event / Desync -> Silence fallback
                    slotGeometry[baseIdx]     = Float.NaN
                    slotGeometry[baseIdx + 1] = Float.NaN
                }
            } else {
                slotGeometry[baseIdx]     = Float.NaN
                slotGeometry[baseIdx + 1] = Float.NaN
            }
        }
    }
}
