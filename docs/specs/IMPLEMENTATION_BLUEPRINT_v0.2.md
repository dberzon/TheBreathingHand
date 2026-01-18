# Breathing Hand — IMPLEMENTATION BLUEPRINT v0.2

**Continuous Harmonic Instrument Architecture**

**Status:**
✅ Design-locked
✅ Philosophy-locked
✅ Safe to implement
🚫 Drift-resistant

---

## 0. Purpose of This Document

This document is the **authoritative implementation blueprint** for the Breathing Hand instrument.

It defines:

* how gesture semantics are translated into harmony,
* how harmony morphs continuously in response to movement,
* how stability is achieved without modes, locks, or permissions,
* how rhythmic re-articulation is supported without semantic ambiguity.

This blueprint must be followed verbatim when rewriting or refactoring code.
If code behavior contradicts this document, the code is wrong.

---

## 1. Instrument Identity (Non-Negotiable)

> **Breathing Hand is a continuous harmonic instrument, not a controller.**

* It does not select notes.
* It does not switch modes.
* It does not wait for confirmation.
* It does not gate harmonic change.

Harmony is **alive** as soon as fingers touch the surface.

---

## 2. Separation of Responsibilities (Foundational)

| Layer               | Responsibility                                 |
| ------------------- | ---------------------------------------------- |
| Gesture Grammar     | Defines **what harmony is**                    |
| Movement            | Defines **how harmony flows**                  |
| Harmonic Inertia    | Defines **how resistant harmony is to change** |
| Transition Window   | Preserves **rhythmic coherence only**          |
| MIDI / Voice Engine | Renders harmony with stable voice-leading      |

No layer may assume responsibility belonging to another.

---

## 3. Gesture Grammar (Semantic Layer)

Gestures are **semantic symbols**, comparable to sign language.

They define harmonic meaning and **must never be used as control switches**.

---

### 3.1 Finger Count Semantics (Invariant)

| Finger Count | Harmonic Role                 |
| ------------ | ----------------------------- |
| 1            | Reference / continuation tone |
| 2            | Harmonic shell (Root + Fifth) |
| 3            | Triad-defining color          |
| 4            | Qualifier (Seventh layer)     |

Adding fingers **adds harmonic layers**.
Removing fingers **removes only those layers**.

No full harmony replacement is allowed.

---

### 3.2 Grip Archetypes (Latched Semantics)

Grip archetypes are **shape-based classifications** detected only when finger count increases.

They are latched until finger count changes again.

#### Three-Finger Archetypes

| Archetype | Meaning                    |
| --------- | -------------------------- |
| FAN       | Stable / major tendency    |
| STRETCH   | Minor / expressive tension |
| CLUSTER   | Suspended / ambiguous       |

#### Four-Finger Archetypes

| Archetype | Meaning                       |
| --------- | ----------------------------- |
| COMPACT   | Resolved extension            |
| WIDE      | Unstable / dominant extension |

Grip archetypes must **never** be re-evaluated continuously.

---

## 4. Gesture Center & Physical Metrics

### 4.1 Gesture Center (Arch Center)

The gesture center is defined as the **centroid of all active touch points**:

```
centroidX = mean(x_i)
centroidY = mean(y_i)
```

All spatial mappings derive from this point.

---

### 4.2 Spread (Physical, Continuous)

Spread is defined as the **mean distance** of fingers from the centroid.

* Spread is continuous.
* Spread is smoothed lightly.
* Spread is never discretized into semantic "bands".

---

## 5. Closed / Collapsed Grip → Harmonic Instability

A closed or collapsed grip is a **semantic harmonic gesture**, not a control signal.

---

### 5.1 Harmonic Instability Metric

Finger spread is mapped to a continuous scalar:

```
harmonicInstability ∈ [0.0, 1.0]
```

* Smaller spread → higher instability
* Larger spread → greater stability

This value may change continuously.

---

### 5.2 v0.2 Harmonic Mapping (Normative)

In Harmonic Map v0.2:

* When `harmonicInstability > INSTABILITY_THRESHOLD`:

  * Default harmonic mapping is overridden.
  * Harmony is forced into a **diminished interval set**: Root, minor third, diminished fifth, diminished seventh

* Below the threshold:

  * Standard harmonic mapping applies (major / minor / suspended via archetypes).

This override is:

* purely harmonic,
* continuous,
* deterministic.

Closed grip must **never** interact with timing, dwell, or transitions.

---

## 6. Screen-Anchored Harmonic Field

### 6.1 Screen Is a Harmonic Field

The screen is **not** a note map.

* Positions bias harmonic gravity.
* They do not select absolute pitch.

---

### 6.2 Anchor Zones (Bias Only)

Vertical screen position biases initial harmonic gravity:

| Zone   | Bias             |
| ------ | ---------------- |
| Center | C / A-minor axis |
| Lower  | D / G axis       |
| Upper  | E / B axis       |

Bias never locks key or root.

---

### 6.3 Rotation → Functional Space

The gesture centroid angle relative to the **screen center** defines functional position.

Angle is quantized into 12 sectors mapped to the circle of fifths:

```
[C, G, D, A, E, B, F#, C#, Ab, Eb, Bb, F]
[0, 7, 2, 9, 4, 11, 6, 1, 8, 3, 10, 5]
```

---

## 7. Harmonic Inertia & Dwell (Authoritative)

Harmony always follows the hand — **with weight**.

---

### 7.1 Harmonic Inertia

Harmonic inertia is the **only** stability mechanism.

There are:

* no permission gates,
* no commit actions,
* no preview modes,
* no gesture-based locking.

---

### 7.2 Dwell (Debounce, Not State)

**Dwell is a debouncing timer, not a harmonic state.**

* Dwell timer resets to `0` whenever the centroid crosses a sector boundary.
* Harmonic change occurs when **either**:

  1. `dwellTime > DWELL_THRESHOLD_MS`, or
  2. `angularVelocity > ANGULAR_SNAP_THRESHOLD` (optional fast throw).

Dwell:

* does not enable change,
* does not block change,
* does not require permission.

Any interpretation of dwell as "commit" violates the instrument contract.

---

## 8. Transition Window — Rhythmic Re-Articulation Only

The Transition Window exists **only** for rhythmic coherence.

---

### 8.1 Purpose

* Preserve harmony across rapid lift → re-touch.
* Enable fast tapping and percussive repetition.

---

### 8.2 Activation (Normative)

A Transition Window is active if:

1. `fingerCount` drops to `0`, and
2. Re-contact occurs within `TRANSITION_WINDOW_MS` (80–150 ms), and
3. Re-contact centroid is within spatial tolerance, and
4. Finger count is compatible with previous gesture.

---

### 8.3 Behavior While Active

* Previous `HarmonicState` is reused verbatim.
* Notes are **re-articulated**.
* Spatial smoothing may be bypassed for immediate attack.

---

### 8.4 Explicit Prohibitions

Transition Window must **never**:

* enable harmonic change,
* block harmonic change,
* influence root selection,
* depend on gesture shape.

---

## 9. Harmonic Map v0.2 (Stable Gravity Map)

### 9.1 One Finger

* Reference tone only
* No bass

### 9.2 Two Fingers

* Root + Fifth
* Voicing derived from spread

### 9.3 Three Fingers

| Archetype | Interval         |
| --------- | ---------------- |
| FAN       | Major third      |
| STRETCH   | Minor third      |
| CLUSTER   | Suspended fourth |

### 9.4 Four Fingers

| Archetype | Interval      |
| --------- | ------------- |
| COMPACT   | Major seventh |
| WIDE      | Minor seventh |

Layers stack incrementally.

---

## 10. State Behavior Summary

* Landing produces sound immediately.
* Movement morphs harmony continuously.
* Gesture defines harmony.
* Time preserves rhythm.
* Physics replaces permission.

---

## 11. Acceptance Criteria

An implementation is correct if:

1. No dead states exist.
2. Harmony morphs continuously.
3. Fast tapping repeats harmony cleanly.
4. No gesture doubles as timing control.
5. No commit / preview logic exists.
6. Hot path is allocation-free.

---

## 12. Canonical Definition (Authoritative)

> **Breathing Hand is a continuous harmonic instrument: gestures define what harmony is, movement defines how it flows, and temporal buffering exists only to preserve rhythmic coherence.**

---

### ✅ END OF BLUEPRINT v0.3
