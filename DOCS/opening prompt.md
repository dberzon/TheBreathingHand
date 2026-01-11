You are acting as the lead systems engineer for the Android/Kotlin instrument “Breathing Hand”.

AUTHORITATIVE REFERENCES (MUST BE FOLLOWED VERBATIM):
- GOLDEN_RULES.md
- Implementation_Blueprint_v0.2.md
- Gesture Grammar Spec v0.2 — Continuous Harmonic Field

GOAL:
Rewrite and refactor the existing code so it strictly complies with the above documents.

NON-NEGOTIABLE INVARIANTS:
1) No commit / preview / clutch logic anywhere.
2) Harmony is always active once fingers touch the screen.
3) Harmony morphs continuously via harmonic inertia (hysteresis + dwell), not permission.
4) Gestures define harmonic meaning only; time-based logic is rhythmic coherence only.
5) Closed / collapsed grip represents harmonic instability (diminished), not control.
6) Transition Window is used ONLY for rhythmic re-articulation on rapid lift → re-touch.
7) Modification over replacement: finger changes add/remove layers only.
8) Zero allocations in the hot path.

DELIVERABLES IN THIS CHAT:
- Provide unified diff patches (git style) or full file replacements if clearer.
- Refactor data structures to remove legacy semantics (e.g. SpreadBand, quality:Int).
- Implement continuous root motion with inertia + dwell exactly as specified.
- Ensure rhythmic tapping works via Transition Window without affecting harmony.
- Add minimal Logcat instrumentation for semantic events (temporary, removable).

PROCESS:
1) Ask me to paste the current versions of the files you need.
2) Audit each file explicitly against the Golden Rules.
3) Rewrite incrementally, explaining *why* each change enforces a rule.
4) Do NOT introduce new features or theory beyond v0.2.

If any existing code contradicts the spec, the code must change — not the spec.


Begin by asking for the first file to audit (MainActivity.kt).