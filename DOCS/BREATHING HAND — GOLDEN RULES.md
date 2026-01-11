BREATHING HAND — GOLDEN RULES
(Instrument Constitution — Design Locked)

Status
Authoritative
Any feature, refactor, or optimization MUST comply with these rules.
If a change violates a rule, the change is rejected or the rule must be explicitly amended.

RULE 0 — The Instrument Identity (Prime Axiom)
Breathing Hand is an instrument, not a controller.

If a design decision makes the system behave like:

a mode switcher,
a preset selector,
a gated interface,
or a note grid,
…it violates the identity of the instrument.

RULE 1 — Gesture Has Semantic Meaning
Every gesture must mean something musically.

Finger count
Finger relationships
Hand shape
Spread
Grip archetype
All of these are semantic.

Forbidden
Using a gesture to “allow”, “unlock”, or “confirm” something
Using a gesture as a boolean switch
Gestures define what harmony is, never when it is allowed.

RULE 2 — Harmony Is Always Alive
Harmony must always be active and audible once fingers touch the surface.

Forbidden
Dead states
Silent waiting for confirmation
“Preview-only” harmony
Landing on the surface is already a commitment to sound.

RULE 3 — Modification Over Replacement
Harmony evolves by adding and removing layers, never by replacement.

Adding fingers → adds harmonic layers
Removing fingers → removes only those layers
Existing voices persist unless explicitly removed
Forbidden
Full chord retriggers on minor gesture changes
Resetting harmony when finger count changes
RULE 4 — Continuous Morphing Is Fundamental
Harmony must be allowed to morph continuously during play.

Root
Function
Harmonic gravity
These may evolve at all times.

Forbidden
Permission gates
Commit buttons
“Only change when X is true”
If harmony cannot flow, the instrument is broken.

RULE 5 — Stability Comes From Physics, Not Permission
Stability is achieved through inertia, not gating.

Stability mechanisms are:

hysteresis
dwell time
resistance
momentum
Forbidden
Commit / preview dichotomies
Mode switches for safety
Boolean flags that enable change
Harmony moves with weight, not with authorization.

RULE 6 — Temporal Logic Must Never Define Harmony
Time-based mechanisms may preserve coherence, but never define musical meaning.

Allowed
Temporal buffering for rhythmic re-articulation
Short-lived coherence windows
Retrigger protection
Forbidden
Time windows that enable harmonic change
Time windows that block harmonic change
Time windows that reinterpret gestures
Timing is how something happens, never what it means.

RULE 7 — The Transition Window Is Rhythmic Only
A Transition Window exists only to support rhythmic repetition.

It may:

preserve harmonic state across rapid lift → re-touch
enable precise rhythmic articulation
It must never:

gate harmonic change
enable harmonic change
define harmony
depend on a gesture shape
If Transition Window logic affects harmony selection, it violates this rule.

RULE 8 — Closed / Collapsed Grip Means Instability
A closed or collapsed hand shape represents harmonic instability.

Diminished
Symmetric tension
Leading-tone gravity
Forbidden
Using closed grip as a “lock”, “clutch”, or “confirm”
Using closed grip as a timing mechanism
Closed grip is musical tension, not control logic.

RULE 9 — The Screen Is Not a Note Map
The screen never represents fixed notes or absolute pitch positions.

The screen is:

a harmonic field
a landscape with gravity
a contextual space
Forbidden
“This Y position is D”
“This X position is G”
Guitar-neck or piano metaphors
Position biases harmony; it never selects notes directly.

RULE 10 — Root, Function, and Color Are Distinct Layers
Root, function, and harmonic color must not be collapsed into a single variable.

Root follows functional motion
Function follows harmonic field logic
Color follows gesture grammar
Forbidden
Overloading one variable to mean all three
Encoding harmony as a single “chord type” enum
Harmony is layered, not atomic.

RULE 11 — No Gesture Has Dual Responsibility
No single gesture may both define harmony and control timing or permission.

If a gesture:

defines harmony → it must not control timing
controls timing → it must not define harmony
This prevents semantic ambiguity and preserves muscle memory.

RULE 12 — Implementation Must Preserve Physicality
The code must reflect physical intuition, not UI conventions.

Allowed
Physics metaphors (mass, inertia, resistance)
Continuous values over booleans
Gradual transitions
Forbidden
UI-style state machines
Mode flags
Button metaphors disguised as gestures
If it feels like software instead of an instrument, it violates this rule.

RULE 13 — Performance Is a Musical Requirement
Zero allocation in the hot path is a musical requirement, not an optimization.

Latency, jitter, and GC pauses are musical failures.

Any implementation that introduces:

allocation churn
unpredictable timing
audio jitter
…violates the instrument contract.

RULE 14 — When in Doubt, Favor Playability
If a decision improves theoretical purity but harms playability, it is rejected.

Breathing Hand is validated by:

hands
time
repetition
muscle memory
Not by diagrams alone.

Canonical One-Sentence Definition (Authoritative)
Breathing Hand is a continuous harmonic instrument: gestures define what harmony is, movement defines how it flows, and temporal buffering exists only to preserve rhythmic coherence.

This sentence overrides all informal explanations.

How to Use This Document
Reference rule numbers in discussions (“This violates Rule 6.”)
Review new features against all rules
Do not reinterpret rules ad hoc
Amend rules only by explicit consensus
Final Note
If development ever “feels weird”, “overcomplicated”, or “UI-ish”:

Stop.
Re-read this document.
Find which rule is being violated.
This is how drift is prevented.

