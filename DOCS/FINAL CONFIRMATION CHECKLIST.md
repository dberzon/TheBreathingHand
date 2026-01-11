---

# 🚫 FINAL CONFIRMATION CHECKLIST

### (“If this exists in code, it’s wrong”)

If **any** of the following are found, the patch **must be rejected or fixed**.

---

## A. Forbidden Concepts & Words (Immediate Rejection)

❌ Any variable, enum, comment, or logic containing:

* `clutch`
* `commit`
* `preview`
* `confirm`
* `lock`
* `unlock`
* `mode`
* `armed`
* `safeState`
* `pendingHarmony`
* `awaitCommit`
* `applyOnRelease`

> **Rule violated:** Golden Rules 1, 4, 5, 6
> **Action:** Delete or refactor — do not rename.

---

## B. Forbidden Control Logic

❌ Any conditional like:

```kotlin
if (canChangeHarmony) { ... }
if (isCommitted) { ... }
if (spreadBand == RED) { ... }
if (inPreviewMode) { ... }
```

❌ Any boolean that enables/disables harmonic change.

> **Rule violated:** Rule 4 (Continuous Morphing), Rule 5 (Physics over Permission)

---

## C. Forbidden Gesture Overloading

❌ Any gesture that:

* both defines harmony **and**
* controls timing, permission, or gating

Examples:

```kotlin
if (isClosedGrip) allowRootChange()
if (fingerCount == 0) resetHarmony()
```

> **Rule violated:** Rule 11 (No Dual Responsibility)

---

## D. Forbidden Spread Semantics

❌ Any discrete spread bands used semantically:

```kotlin
enum SpreadBand { RED, GREEN, BLUE }
state.quality = spreadBand
```

❌ Any logic implying:

* small spread = “safe”
* small spread = “commit”

> **Rule violated:** Rule 8 (Closed Grip = Instability)

---

## E. Forbidden Time-Based Harmony Control

❌ Any time logic that affects **what harmony is selected**:

```kotlin
if (timeHeld > X) changeChord()
delayBeforeHarmonyChange()
```

> **Rule violated:** Rule 6 (Temporal Logic Must Not Define Harmony)

---

## F. Forbidden Silence / Dead States

❌ Any code path where:

* fingers are touching
* but harmony is silent
* or harmony is “waiting”

Examples:

```kotlin
if (!confirmed) return
if (fingerCount < 2) muteAll()
```

> **Rule violated:** Rule 2 (Harmony Is Always Alive)

---

## G. Forbidden Full Replacement

❌ Any logic that resets harmony wholesale:

```kotlin
harmonicState = HarmonicState()
clearAllVoices()
```

when finger count or gesture changes.

> **Rule violated:** Rule 3 (Modification over Replacement)

---

# ✅ PATCH REVIEW CHECKLIST

### (Run this after **every** patch)

All answers must be **YES** for the patch to be accepted.

---

## 1️⃣ Gesture Semantics

☐ Do all gestures map to **musical meaning only**?
☐ Are finger count and grip archetypes semantic, not control signals?
☐ Is closed/collapsed grip treated as **instability**, not safety?

---

## 2️⃣ Continuous Harmony

☐ Does harmony sound immediately on landing?
☐ Can harmony morph continuously during movement?
☐ Is root allowed to change at any time via inertia (not permission)?

---

## 3️⃣ Harmonic Inertia

☐ Is stability achieved via hysteresis + dwell (physics)?
☐ Is dwell clearly implemented as **debounce**, not commit?
☐ Does dwell reset immediately on boundary crossing?

---

## 4️⃣ Transition Window (Rhythmic Only)

☐ Is Transition Window triggered **only** by rapid lift → re-touch?
☐ Does it reuse the previous HarmonicState verbatim?
☐ Does it retrigger notes without changing harmony?
☐ Does it bypass smoothing for attack if needed?

☐ Is there **no** influence on:

* root selection
* harmonic quality
* instability

---

## 5️⃣ Layered Harmony

☐ Are harmonic layers added/removed incrementally?
☐ Are existing voices preserved unless explicitly removed?
☐ Is there no full chord reset on minor gesture changes?

---

## 6️⃣ Data Structures

☐ Is there **no** `quality:Int` / `SpreadBand` / `commitFlag`?
☐ Is harmonic instability represented as a **continuous scalar**?
☐ Are root, function, and color represented as **separate layers**?

---

## 7️⃣ Timing & Performance

☐ Are there zero allocations in the hot path?
☐ Are timers used only for:

* dwell (debounce)
* rhythmic re-articulation

☐ Are there no frame-blocking delays?

---

## 8️⃣ Language & Comments

☐ Do comments avoid UI metaphors (“mode”, “confirm”, “lock”)?
☐ Do comments reinforce:

* physics
* inertia
* continuity
* semantics

---

## 9️⃣ Canonical Test (Mental Simulation)

Ask this question:

> “If I slowly move my hand, does harmony flow?
> If I tap rhythmically, does harmony repeat?
> If I close my hand, does harmony destabilize?”

☐ If all answers are **yes**, the patch passes.

---

## 🧠 Final Rule (Override)

> **If a patch makes the system feel more like software and less like an instrument, it is wrong — even if it ‘works’.**

---

