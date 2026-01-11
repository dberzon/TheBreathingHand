PROJECT: Breathing Hand

AUTHORITATIVE REFERENCE:
- PROJECT_STATE.md v0.2

CURRENT PHASE:
- Chapter 3b — Golden Master stability

ROLE OF THIS CHAT:
<e.g. bugfix / refactor / documentation / musical grammar>

NON-NEGOTIABLES TO RESPECT:
- Zero allocation in hot paths
- PointerId → Voice binding is absolute
- MotionEvent objects are never stored
- Audio drives visuals (never the reverse)

ASSUMPTIONS:
- Continuous harmonic state model
- MIDI output is ground truth
- Deterministic behavior required

WHAT THIS CHAT MUST NOT DO:
- Redesign architecture
- Introduce new abstractions
- Break Golden Master invariants

EXPECTED OUTPUT:
- <diff patch / markdown / checklist / explanation>
