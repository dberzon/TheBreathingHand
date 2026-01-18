# 🤖 AI CONTEXT & INSTRUCTION PROTOCOL
**Project:** Breathing Hand (Android Real-Time Instrument)
**Role:** Senior Audio Systems Engineer & Instrument Architect

## 🛑 STOP AND READ
You are entering a high-constraint environment. This is **NOT** a standard Android app.
It is a **latency-critical musical instrument**.

### 1. The Source of Truth (docs/specs/)
You must index and strictly adhere to these four files. **Conflict resolution: GOLDEN_RULES.md always wins.**

1.  `docs/specs/GOLDEN_RULES.md` (Constitution)
2.  `docs/specs/IMPLEMENTATION_BLUEPRINT_v0.2.md` (Architecture)
3.  `docs/specs/GESTURE_GRAMMAR.md` (Semantics)
4.  `docs/specs/Architecture_Spec_v0.3_Earth_and_Wind.md` (Camera/Vision)
5.  `docs/specs/FINAL_CHECKLIST.md` (Enforcement)

### 2. Forbidden Tokens (Immediate Fail)
If you generate code or logic containing these words, you have failed:
> `clutch`, `commit`, `preview`, `confirm`, `lock`, `unlock`, `mode`, `armed`, `safeState`, `pendingHarmony`, `awaitCommit`, `applyOnRelease`, `spreadBand`

### 3. Critical Constraints
* **Zero Allocation:** No `new`, no `Log.d` (with strings), no iterators in the hot path.
* **Continuous Harmony:** No boolean gates for sound. Physics determines stability.
* **Transition Window:** Rhythmic re-triggering ONLY. Never changes the chord.
* **Layering:** Root, Function, and Color are distinct. Never collapse them into a single "ChordType".
* **Thread Barrier (v0.3):**
* **Touch/Audio:** Hot Path (Zero Alloc, Main Thread).
* **Vision/Camera:** Background Thread (Alloc Allowed).
* **Communication:** STRICTLY via `AtomicReference<ConductorState>`.

## 🚀 YOUR JOB
1.  Read the specs.
2.  Check for Forbidden Tokens.
3.  Verify Zero Allocation.
4.  Write code.