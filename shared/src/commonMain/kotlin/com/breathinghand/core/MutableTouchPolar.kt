package com.breathinghand.core

/**
 * A mutable data holder for touch coordinates.
 * We use this instead of an immutable Data Class to avoid creating
 * new objects every frame (Garbage Collection optimization).
 */
data class MutableTouchPolar(
    var radius: Float = 0f,
    var angle: Float = 0f,
    var isActive: Boolean = false,
    var pointerCount: Int = 0
)