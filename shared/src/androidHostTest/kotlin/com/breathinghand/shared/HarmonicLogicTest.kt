package com.breathinghand.shared

import com.breathinghand.core.*
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
        state.triad = GestureAnalyzerV01.TRIAD_FAN
        state.seventh = GestureAnalyzerV01.SEVENTH_COMPACT
        state.rootPc = 0 // C

        // Case A: Stable (Open hand) -> Should be Major 7 (C, G, E, B)
        state.harmonicInstability = 0.0f
        HarmonicFieldMapV01.fillRoleNotes(state, roles)

        // C4=60. Expect: 60 (Root), 67 (P5), 64 (M3), 71 (M7)
        assertEquals(60, roles[0], "Stable Root should be C")
        assertEquals(67, roles[1], "Stable 5th should be Perfect")
        assertEquals(64, roles[2], "Stable 3rd should be Major (FAN)")
        assertEquals(71, roles[3], "Stable 7th should be Major (COMPACT)")

        // Case B: Unstable (Closed hand) -> Should be Diminished (C, Gb, Eb, Bbb/A)
        state.harmonicInstability = 1.0f // Maximum instability
        HarmonicFieldMapV01.fillRoleNotes(state, roles)

        // Expect: 60 (Root), 66 (d5), 63 (m3), 69 (d7/M6)
        assertEquals(60, roles[0], "Root stays C")
        assertEquals(66, roles[1], "Unstable 5th should be Diminished (Gb)")
        assertEquals(63, roles[2], "Unstable 3rd should be Minor (Eb)")
        assertEquals(69, roles[3], "Unstable 7th should be Diminished (A)")
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
        var count = HarmonicFieldMapV01.fillRoleNotes(state, roles)
        assertEquals(1, count)
        assertEquals(72, roles[0]) // C5 (Reference)
        assertEquals(0, roles[1])  // No 5th

        // 2 Fingers: Add 5th
        state.fingerCount = 2
        count = HarmonicFieldMapV01.fillRoleNotes(state, roles)
        assertEquals(2, count)
        assertEquals(60, roles[0]) // Drops to C4 base
        assertEquals(67, roles[1]) // Adds G

        // 3 Fingers: Add Color
        state.fingerCount = 3
        state.triad = GestureAnalyzerV01.TRIAD_FAN // Major
        count = HarmonicFieldMapV01.fillRoleNotes(state, roles)
        assertEquals(3, count)
        assertEquals(64, roles[2]) // Adds E (M3)
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