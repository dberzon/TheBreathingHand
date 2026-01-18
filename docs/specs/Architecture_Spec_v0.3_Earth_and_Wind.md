# Breathing Hand — Architecture Spec v0.3 (EARTH & WIND)

**Status:** Authoritative (Draft v0.3)
**Extends:** Implementation Blueprint v0.2
**Compliance:** Strict adherence to GOLDEN_RULES.md

---

## 1. Core Philosophy: "Earth & Wind"

To resolve **Rule 11 (No Dual Responsibility)** while enabling expressive performance, the instrument is physically split into two domains.

### 1.1 The Earth Hand (Glass / Touch)
* **Role:** The **Harmonic Sculptor**.
* **Physics:** Gravity, Friction, Inertia.
* **Responsibility:** Defines **WHAT** is played (Notes, Voicing, Root, Tension).
* **Latency:** Ultra-low (~10ms).
* **Thread:** UI/Audio Thread (Hot Path).
* **State:** Creates the *harmonic potential*.

### 1.2 The Air Hand (Camera / Vision)
* **Role:** The **Conductor**.
* **Physics:** Flow, Velocity, Agitation.
* **Responsibility:** Defines **HOW** it is played (Rhythm, Dynamics, Articulation).
* **Latency:** Variable (~50–100ms).
* **Thread:** Background Vision Thread (Async).
* **State:** Extracts *kinetic energy* to trigger the potential.

---

## 2. Air Gesture Grammar (The "Conductor" Language)

Because camera latency prevents millisecond-accurate triggering, Air Gestures map to **continuous energy parameters**, never discrete triggers.

| Physical Input | Musical Parameter | Analogy |
| :--- | :--- | :--- |
| **Motion Energy** (Speed) | **Clock Rate** (Tempo) | "Waving the baton faster" |
| **Vertical Height** (Y-Axis) | **Dynamics** (Velocity/Filter) | "Raising the hand for volume" |
| **Grip** (Pinch vs. Open) | **Gate Length** (Legato vs. Staccato) | "Pinching the sound short" |
| **Presence** (Hand Visible) | **Master Gate** (Play/Stop) | "The conductor enters/exits" |

### 2.1 The "Rainfall" Mechanic
* **Hand Moving:** Notes are triggered from the current Harmonic State (Earth Hand).
* **Hand Still:** The Arpeggiator pauses; the current chord sustains (Pad).
* **Hand Absent:** The sound fades to silence.

---

## 3. Technical Architecture: The "Async Bridge"

To preserve **Rule 13 (Zero Allocation in Hot Path)**, the Vision system is strictly isolated from the Audio system.

### 3.1 Threading Model

#### A. Vision Thread (Background / Low Priority)
* **Engine:** MediaPipe Solutions (Hand Landmarker).
* **Frequency:** ~30Hz (Camera limits).
* **Task:** Calculates normalized physics scalars (0.0–1.0).
* **Write:** Updates `AtomicReference<ConductorState>`.

#### B. Audio Thread (Hot Path / High Priority)
* **Frequency:** ~480Hz (Touch/Audio rate).
* **Read:** Reads `AtomicReference<ConductorState>` every tick.
* **Smoothing:** Applies `OneEuroFilter` to interpolate low-rate Vision data.
* **Action:** Drives the Step Sequencer and Synth parameters.

---
## 4. Data Structure: `ConductorState`

This structure acts as the bridge. It must be immutable or thread-safe.

```kotlin
/**
 * The snapshot of the "Air Hand" state.
 * Written by Vision Thread, Read by Audio Thread.
 */
data class ConductorState(
    /**
     * Normalized Motion Energy (0.0 = Still, 1.0 = Furioso).
     * Maps to Arpeggiator Clock Division / Speed.
     */
    val flowEnergy: Float = 0f,

    /**
     * Normalized Height (0.0 = Bottom, 1.0 = Top).
     * Maps to Velocity & CC74 (Brightness).
     */
    val verticalBias: Float = 0f,

    /**
     * Grip State (True = Pinched, False = Open).
     * Maps to Note Duration (Staccato vs. Legato).
     */
    val isPinched: Boolean = false,

    /**
     * Safety flag. If false, the Air Hand is lost.
     * System should fade to silence or sustain.
     */
    val isActive: Boolean = false
)

```


## 5. Implementation Constraints

### 5.1 No Blocking
The Audio Thread must never wait for the Camera. It reads the *last known* state.

### 5.2 Graceful Degrade
If Camera fails or overheats, the system must default to a "Manual/Touch-Only" mode (e.g., `isActive = true`, `flowEnergy = 0.5`).

### 5.3 Privacy
No camera images are saved or transmitted. Processing is strictly on-device.

