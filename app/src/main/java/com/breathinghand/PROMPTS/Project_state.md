Breathing Hand — v0.2 (Authoritative)
1. Project Identity

Name: Breathing Hand
Platform: Android (Kotlin)
Primary Output: USB MIDI (Hardware-first validation)

Purpose:
Breathing Hand is a continuous, multi-touch musical controller that maps hand gestures to harmonic states and voice-leading behavior in real time. It prioritizes physical intuition, musical continuity, and ultra-low-latency performance over discrete UI interaction.

Core Mental Model:

The hand does not “trigger events”; it inhabits a harmonic state that evolves continuously over time.

2. Current Status

Phase: Chapter 3b — Stability & Refinement (Golden Master)

Build Status: Green / Stable

Primary Test Device: Physical Android phone

Validation Method: External MIDI hardware (audio) + Logcat (data)

Canonical Reference: Golden Master architecture (v3)

⚠️ NOTE: Exact commit hash / tag to be inserted once frozen.

3. Architecture Overview (Authoritative)
3.1 Input Layer

Source: Android multi-touch input (MotionEvent)

Thread: UI thread

Extraction Rule (Hard):
MotionEvent objects are never stored. All required primitive data (pointerId, x/y, actionMasked, eventTime) is extracted immediately.

Memory Safety:
Extracted data is written into pre-allocated, reusable structures. No per-event allocations are permitted.

Identity Rule:
Pointer IDs are the sole source of truth for musical voice identity.

Rationale: Android recycles MotionEvent objects. Storing them causes corrupted gesture history and undefined behavior.

3.2 Signal Conditioning / Math

Allocation Strategy: Zero allocation in all hot paths

Geometry: Polar / angular representations relative to a defined anchor

Filtering: Smoothing exists to reduce jitter

Status: Filter type and coefficients are TBD / VERIFY IN CODE

3.3 Harmonic State Engine

Nature: Continuous state engine (no discrete triggers)

State Dimensions (minimum):

Root

Quality

Density / voicing parameters

Stability:
Hysteresis is applied at boundaries to prevent harmonic flicker

3.4 Voice Leading & Output

Mapping Rule:
One active PointerId equals one musical voice

Persistence:
Voices never reassign or reorder when other fingers lift

Movement Logic:
Pitch movement is minimized using a nearest-neighbor strategy
(Algorithm: Manhattan distance)

3.5 MIDI Output

Protocol: USB MIDI

Verification:
All NoteOn / NoteOff messages are logged with timestamps

Truth Rule:
MIDI logs define software truth:

If MIDI is logged but audio fails → hardware issue

If MIDI is not logged → software issue

4. Musical / Conceptual Rules

These are observed invariants, not aspirational design goals.

Continuity: Gesture → harmony mapping is continuous
(Pitch output may be quantized)

Finger Influence: Finger count directly affects harmonic interpretation

Voice Independence:
Lifting one finger must not cause remaining voices to jump pitch

Causality:
Physical action must produce immediate, intelligible musical response

⚠️ NOTE: Detailed harmonic grammar is not yet frozen

5. Non-Negotiables
Performance

Zero Allocation: No new operations in onTouch or onDraw

No GC Pressure: Sustained play must not trigger GC

Determinism: Identical input + state must yield identical output

Identity & State

Strict Binding: PointerId → Voice is absolute

No Implicit Reindexing: Voice order never changes implicitly

Audio / Visual Hierarchy (Critical)

Visual Subservience Rule:
Audio state drives visuals. Visuals never drive audio.

Data Flow:
Input → State → MIDI/Audio → Visuals

State Access Rule:
Visuals may read an immutable snapshot of current musical state but must never compute or modify it.

Rationale: Prevents UI frame pacing or rendering load from affecting musical latency or correctness.

6. Terminology (Canonical)
Term	Meaning
PointerId	Android touch identity used as a stable voice key
Voice	One musical pitch stream tied to one PointerId
Harmonic State	Continuous harmony representation
Golden Master	Verified stable behavior reference
Hot Path	Per-event or per-frame execution zone (no allocation)
7. Open Problems (Tracked)

Formalize harmonic grammar

Document filter / hysteresis parameters

Long-session stability test (>1h)

Define visual feedback role (decorative vs informational)

8. Explicitly Out of Scope (For Now)

UI polish / animations

Presets

Internal audio synthesis

Network / OSC / Bluetooth

Multi-hand or collaborative modes

9. Authority Statement

If discussion contradicts this document, this document wins.
If code contradicts a Non-Negotiable, the code is defective.

Version History

v0.1 — Conservative baseline

v0.2 — MotionEvent memory safety + Audio-Visual hierarchy formalized