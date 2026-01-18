# Gesture Grammar Spec v0.2 — Continuous Harmonic Field

## Status
Authoritative  
Aligned with:
- GOLDEN_RULES.md
- Implementation Blueprint v0.2

This document defines the **semantic gesture grammar** of the Breathing Hand instrument.
It defines **how gestures acquire meaning**.

---

## 1. Core Principle

> **Gestures are semantic.  
They define what harmony is, never when harmony is allowed.**

Gesture interpretation must be stable, deterministic, and free of timing logic.

---

## 2. Gesture Center (Arch Center)

The gesture center is defined as the **centroid of all active touch points**:
`Cx = mean(x_i)`
`Cy = mean(y_i)`

All spatial mappings (rotation, bias, inertia) are derived from this point.

---

## 3. Finger Count Semantics (Invariant)

Finger count defines **harmonic role**, not note density.

| Finger Count | Semantic Role |
|------------|---------------|
| 1 | Reference / continuation |
| 2 | Harmonic shell (Root + Fifth) |
| 3 | Triad-defining color |
| 4 | Qualifier (Seventh layer) |

**Rules:**
- Finger addition **adds** harmonic layers.
- Finger removal **removes only those layers**.
- No gesture replaces the entire harmonic state.

---

## 4. Grip Archetypes (Shape-Based Semantics)

Grip archetypes are determined **only when finger count increases**.
They are latched until finger count changes again.

### 4.1 Three-Finger Archetypes

| Archetype | Shape Description | Semantic Meaning |
|---------|------------------|-----------------|
| FAN | One point clearly separated | Stable / major tendency |
| STRETCH | Wide triangle | Minor / expressive tension |
| CLUSTER | Compact triangle | Suspended / ambiguous |

### 4.2 Four-Finger Archetypes

| Archetype | Shape Description | Semantic Meaning |
|----------|------------------|-----------------|
| COMPACT | Tight convex hull | Resolved extension |
| WIDE | Wide convex hull | Unstable / dominant extension |

---

## 5. Spread & Harmonic Instability

### 5.1 Spread (Physical Quantity)
Spread is defined as the **mean distance** of fingers from the centroid.
- Spread is continuous.
- Spread is lightly smoothed.
- Spread is never discretized into semantic bands.

### 5.2 Harmonic Instability
Spread is mapped to a continuous instability scalar:
`harmonicInstability ∈ [0.0, 1.0]`

- Smaller spread → higher instability.
- Larger spread → greater stability.

This value may change continuously and does not latch.

---

## 6. Closed / Collapsed Grip (Semantic Meaning)

A closed or collapsed grip represents **harmonic instability**.

It implies:
- diminished symmetry
- leading-tone tension
- harmonic unrest

It must never be interpreted as:
- a lock
- a clutch
- a confirmation signal
- a timing mechanism

---

## 7. Screen as Harmonic Field

The screen is **not** a note layout.
- Positions bias harmonic gravity.
- Positions do not select absolute pitch.

### 7.1 Vertical Bias (Starting Gravity)

| Region | Bias |
|------|------|
| Center | C / A-minor axis |
| Lower | D / G axis |
| Upper | E / B axis |

Bias affects initial gravity only.

---

## 8. Rotation & Functional Space

The angular position of the gesture centroid relative to the screen center defines **functional harmonic position**.

Angle is quantized into 12 sectors mapped to the circle of fifths.
Root position may change **continuously**, subject only to harmonic inertia.

---

## 9. Harmonic Inertia (Normative)

Harmony always follows the hand, but with resistance.

Stability is achieved through:
- angular hysteresis
- dwell-based debouncing
- physical inertia

There are **no commit actions, no preview states, no permission gates**.

---

## 10. Transition Window (Rhythmic Only)

A short temporal buffer exists **only** for rhythmic re-articulation.

- It preserves the previous harmonic state across rapid lift → re-touch.
- It never affects harmony selection.
- It never depends on gesture shape.

---

## 11. Canonical Summary

> **Gesture defines harmony.  
Movement morphs harmony.  
Time preserves rhythm.**