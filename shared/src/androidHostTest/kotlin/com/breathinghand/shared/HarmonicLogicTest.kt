package com.breathinghand.shared

import com.breathinghand.core.*
import com.breathinghand.engine.GestureAnalyzer
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals


class HarmonicLogicTest {

    // --- TEST 1: Verify Rule 8 (Closed Grip = Instability) ---
    @Test
    fun testInstabilityOverride() {
        val state = HarmonicState()
        val roles = IntArray(4)

        // Setup: 4 fingers, Fan shape (normally Major 7th)
        state.fingerCount = 4
        state.triad = GestureAnalyzer.TRIAD_FAN
        state.seventh = GestureAnalyzer.SEVENTH_COMPACT
        state.rootPc = 0 // C

        // Case A: Stable (Open hand) -> Should be Major 7 (C, G, E, B)
        state.harmonicInstability = 0.0f
        HarmonicNoteMapV02.computeNotes(state, roles)

        // v0.2 base: C3=48. Expect: 48 (Root), 55 (P5), 52 (M3), 59 (M7)
        assertEquals(48, roles[0], "Stable Root should be C3")
        assertEquals(55, roles[1], "Stable 5th should be Perfect")
        assertEquals(52, roles[2], "Stable 3rd should be Major (FAN)")
        assertEquals(59, roles[3], "Stable 7th should be Major (COMPACT)")

        // Case B: Unstable (Closed hand) -> Should be Diminished (C, Eb, Gb, A)
        state.harmonicInstability = 1.0f // Maximum instability
        HarmonicNoteMapV02.computeNotes(state, roles)

        // v0.2 unstable: base=48. Expect: 48 (Root), 51 (m3), 54 (d5), 57 (d7)
        assertEquals(48, roles[0], "Root stays C3")
        assertEquals(51, roles[1], "Unstable slot 1 should be Minor 3rd (Eb)")
        assertEquals(54, roles[2], "Unstable slot 2 should be Dim 5th (Gb)")
        assertEquals(57, roles[3], "Unstable slot 3 should be Dim 7th (A)")
    }

    // --- TEST 2: Verify Rule 3 (Layered Addition) ---
    @Test
    fun testLayeredHarmony() {
        val state = HarmonicState()
        val roles = IntArray(4)
        state.rootPc = 0
        state.harmonicInstability = 0f

        // 1 Finger: Reference only
        state.fingerCount = 1
        var count = HarmonicNoteMapV02.computeNotes(state, roles)
        assertEquals(1, count)
        assertEquals(48, roles[0]) // C3 (v0.2 base)
        assertEquals(0, roles[1])  // No 5th

        // 2 Fingers: Add 5th
        state.fingerCount = 2
        count = HarmonicNoteMapV02.computeNotes(state, roles)
        assertEquals(2, count)
        assertEquals(48, roles[0]) // C3 base
        assertEquals(55, roles[1]) // Adds G (P5)

        // 3 Fingers: Add Color
        state.fingerCount = 3
        state.triad = GestureAnalyzer.TRIAD_FAN // Major
        count = HarmonicNoteMapV02.computeNotes(state, roles)
        assertEquals(3, count)
        assertEquals(52, roles[2]) // Adds E (M3, base+4)
    }

    // --- TEST 3: Verify Rule 5 (Physics/Dwell) ---
    @Test
    fun testHarmonicInertia() {
        val engine = HarmonicEngine()

        // 1. Initial Touch at 0 degrees (Sector 0 / C)
        engine.update(
            nowMs = 1000L,
            angleRad = 0f,
            spreadPx = 200f, centerYNorm = 0.5f, fingerCount = 1,
            triadArchetype = 0, seventhArchetype = 0
        )
        assertEquals(0, engine.state.functionSector, "Should start at Sector 0")

        // 2. Move slightly into Sector 1 boundary (Hysteresis check)
        // Sector width is ~0.52 rad. Move to 0.4 (close to edge but not fully committed)
        engine.update(
            nowMs = 1010L, // Only 10ms passed
            angleRad = 0.4f,
            spreadPx = 200f, centerYNorm = 0.5f, fingerCount = 1,
            triadArchetype = 0, seventhArchetype = 0
        )
        assertEquals(0, engine.state.functionSector, "Should hold Sector 0 due to dwell/hysteresis")

        // 3. Move fully into Center of Sector 1 and wait (Dwell check)
        // FIX: Use 1.5 * width (45 degrees) to hit the center, ensuring hysteresis is cleared.
        val sector1Center = (1.5 * 2 * PI / 12).toFloat()
        engine.update(
            nowMs = 1020L,
            angleRad = sector1Center,
            spreadPx = 200f, centerYNorm = 0.5f, fingerCount = 1,
            triadArchetype = 0, seventhArchetype = 0
        )
        // Still shouldn't change immediately (only 20ms since start of dwell candidate)
        assertEquals(0, engine.state.functionSector, "Should wait for dwell timer")

        // 4. Fast forward time > DWELL_THRESHOLD_MS (90ms)
        engine.update(
            nowMs = 1200L, // +180ms later
            angleRad = sector1Center,
            spreadPx = 200f, centerYNorm = 0.5f, fingerCount = 1,
            triadArchetype = 0, seventhArchetype = 0
        )
        assertEquals(1, engine.state.functionSector, "Should advance to Sector 1 after dwell")
    }
}