package com.breathinghand.core.midi

import android.view.MotionEvent
import com.breathinghand.core.MusicalConstants

/**
 * VOICE ALLOCATOR (Physical Truth)
 * Android-specific implementation using MotionEvent pointer IDs.
 *
 * Implements SlotPresence for common code consumption.
 */
class VoiceAllocator : SlotPresence {

    companion object {
        const val NO_POINTER = -1
    }

    /**
     * Maps Slot Index (0..MAX_VOICES-1) -> Pointer ID.
     */
    private val slotToPointerId = IntArray(MusicalConstants.MAX_VOICES) { NO_POINTER }

    /**
     * Bitmask representing physically active slots.
     */
    var activeSlotMask: Int = 0
        private set

    /**
     * Total count of active physical pointers.
     */
    var activePointerCount: Int = 0
        private set

    override fun isSlotActive(slotIndex: Int): Boolean {
        if (slotIndex in 0 until MusicalConstants.MAX_VOICES) {
            return slotToPointerId[slotIndex] != NO_POINTER
        }
        return false
    }

    /**
     * Process raw MotionEvent to update slot topology.
     */
    fun processEvent(event: MotionEvent) {
        val action = event.actionMasked
        val actionIndex = event.actionIndex

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val pointerId = event.getPointerId(actionIndex)
                assignSlot(pointerId)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val pointerId = event.getPointerId(actionIndex)
                releaseSlot(pointerId)
            }

            MotionEvent.ACTION_CANCEL -> {
                resetAll()
            }
        }
    }

    fun getPointerIdForSlot(slotIndex: Int): Int {
        if (slotIndex in 0 until MusicalConstants.MAX_VOICES) {
            return slotToPointerId[slotIndex]
        }
        return NO_POINTER
    }

    private fun assignSlot(pointerId: Int) {
        for (i in 0 until MusicalConstants.MAX_VOICES) {
            if (slotToPointerId[i] == NO_POINTER) {
                slotToPointerId[i] = pointerId
                activePointerCount++
                updateMask()
                return
            }
        }
    }

    private fun releaseSlot(pointerId: Int) {
        for (i in 0 until MusicalConstants.MAX_VOICES) {
            if (slotToPointerId[i] == pointerId) {
                slotToPointerId[i] = NO_POINTER
                activePointerCount--
                updateMask()
                return
            }
        }
    }

    private fun resetAll() {
        for (i in 0 until MusicalConstants.MAX_VOICES) {
            slotToPointerId[i] = NO_POINTER
        }
        activePointerCount = 0
        activeSlotMask = 0
    }

    private fun updateMask() {
        var mask = 0
        for (i in 0 until MusicalConstants.MAX_VOICES) {
             if (slotToPointerId[i] != NO_POINTER) {
                 mask = mask or (1 shl i)
             }
        }
        activeSlotMask = mask
    }
}
