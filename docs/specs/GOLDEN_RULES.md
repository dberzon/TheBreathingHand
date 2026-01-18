# BREATHING HAND — GOLDEN RULES
**(Instrument Constitution — Design Locked)**

## Status
**Authoritative**
Any feature, refactor, or optimization MUST comply with these rules.
If a change violates a rule, the change is rejected or the rule must be explicitly amended.

---

### RULE 0 — The Instrument Identity (Prime Axiom)
**Breathing Hand is an instrument, not a controller.**

If a design decision makes the system behave like:
* a mode switcher,
* a preset selector,
* a gated interface,
* or a note grid,
…it violates the identity of the instrument.

### RULE 1 — Gesture Has Semantic Meaning
**Every gesture must mean something musically.**

* Finger count, relationships, hand shape, spread, and grip archetype are semantic.
* **Forbidden:** Using a gesture to “allow”, “unlock”, or “confirm” something.
* Gestures define **what** harmony is, never **when** it is allowed.

### RULE 2 — Harmony Is Always Alive
**Harmony must always be active and audible once fingers touch the surface.**

* **Forbidden:** Dead states, silent waiting for confirmation, “preview-only” harmony.
* Landing on the surface is already a commitment to sound.

### RULE 3 — Modification Over Replacement
**Harmony evolves by adding and removing layers, never by replacement.**

* Adding fingers → adds harmonic layers.
* Removing fingers → removes only those layers.
* Existing voices persist unless explicitly removed.
* **Forbidden:** Full chord retriggers on minor gesture changes.

### RULE 4 — Continuous Morphing Is Fundamental
**Harmony must be allowed to morph continuously during play.**

* Root, Function, and Harmonic gravity may evolve at all times.
* **Forbidden:** Permission gates, commit buttons, “Only change when X is true”.

### RULE 5 — Stability Comes From Physics, Not Permission
**Stability is achieved through inertia, not gating.**

* Mechanisms: hysteresis, dwell time, resistance, momentum.
* **Forbidden:** Commit/preview dichotomies, boolean flags that enable change.
* Harmony moves with weight, not with authorization.

### RULE 6 — Temporal Logic Must Never Define Harmony
**Time-based mechanisms may preserve coherence, but never define musical meaning.**

* **Allowed:** Temporal buffering for rhythmic re-articulation (Transition Window).
* **Forbidden:** Time windows that enable/block harmonic change.
* Timing is **how** something happens, never **what** it means.

### RULE 7 — The Transition Window Is Rhythmic Only
**A Transition Window exists only to support rhythmic repetition.**

* It may preserve harmonic state across rapid lift → re-touch.
* It must **never** gate or define harmonic change.

### RULE 8 — Closed / Collapsed Grip Means Instability
**A closed or collapsed hand shape represents harmonic instability.**

* It implies diminished, symmetric tension, or leading-tone gravity.
* **Forbidden:** Using closed grip as a “lock”, “clutch”, or “confirm”.

### RULE 9 — The Screen Is Not a Note Map
**The screen never represents fixed notes or absolute pitch positions.**

* The screen is a harmonic field with gravity.
* **Forbidden:** “This Y position is D”. Position biases harmony; it never selects notes directly.

### RULE 10 — Root, Function, and Color Are Distinct Layers
**Root, function, and harmonic color must not be collapsed into a single variable.**

* **Forbidden:** Overloading one variable to mean all three. Harmony is layered, not atomic.

### RULE 11 — No Gesture Has Dual Responsibility
**No single gesture may both define harmony and control timing or permission.**

* If a gesture defines harmony → it must not control timing.
* If a gesture controls timing → it must not define harmony.

### RULE 12 — Implementation Must Preserve Physicality
**The code must reflect physical intuition, not UI conventions.**

* **Allowed:** Physics metaphors (mass, inertia).
* **Forbidden:** UI-style state machines, mode flags, button metaphors.

### RULE 13 — Performance Is a Musical Requirement
**Zero allocation in the hot path is a musical requirement, not an optimization.**

* Latency, jitter, and GC pauses are musical failures.
* Any implementation that introduces allocation churn violates the instrument contract.

### RULE 14 — When in Doubt, Favor Playability
**If a decision improves theoretical purity but harms playability, it is rejected.**

* Breathing Hand is validated by hands, time, repetition, and muscle memory.

---

### Canonical One-Sentence Definition (Authoritative)
**Breathing Hand is a continuous harmonic instrument: gestures define what harmony is, movement defines how it flows, and temporal buffering exists only to preserve rhythmic coherence.**

## AMENDMENT v0.3 — The Earth & Wind Split

**Clarification of Rule 11 (No Dual Responsibility)**
To strictly enforce Rule 11, responsibilities are now physically segregated:
* **Touch (Earth)** defines *Harmonic Potential*.
* **Vision (Air)** defines *Kinetic Activation*.

**Clarification of Rule 13 (Performance)**
* Vision processing (MediaPipe) is **exempt** from the "Zero Allocation" rule ONLY because it runs on a background thread.
* The **Bridge** (reading the state) must remain Zero Allocation on the Audio Thread side.
