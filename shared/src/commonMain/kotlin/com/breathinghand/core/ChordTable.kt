package com.breathinghand.core

/**
 * Chord interval lookup.
 *
 * quality: 0..2
 * density: 3..5 (triad, 7th, 9th)  <-- IMPORTANT: matches HarmonicEngine density domain
 *
 * Returns semitone offsets above chromatic root.
 * Zero allocation: returns references to static IntArray tables.
 */
object ChordTable {

    private val DATA = arrayOf(
        // quality = 0
        arrayOf(
            intArrayOf(0, 3, 6),          // density=3
            intArrayOf(0, 3, 6, 9),       // density=4
            intArrayOf(0, 3, 6, 9, 12)    // density=5
        ),
        // quality = 1
        arrayOf(
            intArrayOf(0, 3, 7),
            intArrayOf(0, 3, 7, 10),
            intArrayOf(0, 3, 7, 10, 14)
        ),
        // quality = 2
        arrayOf(
            intArrayOf(0, 4, 7),
            intArrayOf(0, 4, 7, 11),
            intArrayOf(0, 4, 7, 11, 14)
        )
    )

    fun get(q: Int, d: Int): IntArray {
        val qIndex = q.coerceIn(0, 2)
        val dIndex = (d - 3).coerceIn(0, 2) // density domain is 3..5
        return DATA[qIndex][dIndex]
    }
}
