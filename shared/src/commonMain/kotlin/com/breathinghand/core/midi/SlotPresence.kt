package com.breathinghand.core.midi

/**
 * Common interface for querying physical slot activity.
 * Implemented by VoiceAllocator on Android (wrapping MotionEvent)
 * and TouchHandler on iOS (wrapping UITouch).
 */
interface SlotPresence {
    /**
     * Returns true if the given voice slot index (0..MAX_VOICES-1)
     * is currently held by a physical pointer.
     */
    fun isSlotActive(slotIndex: Int): Boolean
}
