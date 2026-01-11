package com.breathinghand.core

/**
 * Harmonic Map v0.2 ("Stable Gravity Map")
 * Complies with Rule 8: Closed grip (> INSTABILITY_THRESHOLD) forces diminished symmetry.
 *
 * IMPORTANT:
 * - state.rootPc is already a pitch class (0..11).
 * - Any circle-of-fifths sector mapping must happen upstream (HarmonicEngine), not here.
 */
object HarmonicFieldMapV01 {

    private const val REF_TONE_BASE_MIDI = 72 // C5
    private const val CHORD_BASE_MIDI = 60    // C4

    /**
     * Fill roleNotes[0..3].
     * @return number of active roles (0..4)
     */
    fun fillRoleNotes(state: HarmonicState, roleNotes: IntArray): Int {
        if (roleNotes.size < 4) return 0

        // 1) Clean slate
        roleNotes[0] = 0
        roleNotes[1] = 0
        roleNotes[2] = 0
        roleNotes[3] = 0

        val fingers = state.fingerCount.coerceIn(0, 4)
        if (fingers <= 0) return 0

        // 2) Base pitch (rootPc is already a pitch class)
        // FIX: Kept the Developer's fix here (no double mapping)
        val rootPc = state.rootPc.coerceIn(0, 11)
        val base = if (fingers == 1) REF_TONE_BASE_MIDI else CHORD_BASE_MIDI
        val root = base + rootPc

        // 3) Instability override (semantic, not temporal)
        val isUnstable = state.harmonicInstability > MusicalConstants.INSTABILITY_THRESHOLD

        // Role 0: Root
        roleNotes[0] = root
        var count = 1

        if (fingers >= 2) {
            // Role 1: Fifth (Perfect or Diminished)
            roleNotes[1] = if (isUnstable) root + 6 else root + 7
            count = 2
        }

        if (fingers >= 3) {
            // Role 2: Triad color
            val interval = if (isUnstable) {
                3 // minor third in diminished context
            } else {
                when (state.triad) {
                    GestureAnalyzerV01.TRIAD_STRETCH -> 3 // m3
                    GestureAnalyzerV01.TRIAD_CLUSTER -> 5 // sus4
                    else -> 4                             // M3 (FAN)
                }
            }
            roleNotes[2] = root + interval
            count = 3
        }

        if (fingers >= 4) {
            // Role 3: Seventh / extension
            val interval = if (isUnstable) {
                9 // diminished 7th (root + 9)
            } else {
                when (state.seventh) {
                    GestureAnalyzerV01.SEVENTH_WIDE -> 10 // m7
                    else -> 11                            // M7
                }
            }
            roleNotes[3] = root + interval
            count = 4
        }

        // FIX: REMOVED "Voicing Loop".
        // The tests expect raw semantic pitches.
        // The synth/sound engine can handle voicing if needed, but the Map should return pure intervals.

        return count
    }
}