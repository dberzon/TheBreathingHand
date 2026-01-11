# MIDI Implementation Chart — Breathing Hand

**Compliance Status:** Reviewed against GOLDEN_RULES.md ✓

This is the **exact current MIDI channel/CC convention** used by the app.

---

## 1) Global / Reserved Channel (Master)

- **MIDI Channel 1** (0-based channel = 0) is **reserved as the MPE Master / Global channel**.
- **Current behavior:** the app does not send global/master messages yet; reserved for future global controls and MPE zone configuration.

---

## 2) Dynamic Voice Channels (Member Channels)

The app uses **slot-stable member channels** (one MIDI channel per voice slot).

> **Golden Rules Alignment:** This supports **Rule 3 (Modification Over Replacement)**. Each voice persists on its channel. Adding a finger activates a new channel; removing a finger silences only that channel.

- `MusicalConstants.MAX_VOICES = 5`
- Uses **MIDI Channels 2–6** as dynamic voice channels.

### Slot → Channel Mapping

| Voice slot | 0-based channel | Human channel |
|---:|---:|---:|
| 0 | 1 | 2 |
| 1 | 2 | 3 |
| 2 | 3 | 4 |
| 3 | 4 | 5 |
| 4 | 5 | 6 |

Implementation: `channel = slotIndex + 1`

Channels 7–16 are currently unused.

---

## 3) Per-Voice Expressive Controls

> **Golden Rules Alignment:** All expressive controls are **continuous**, supporting **Rule 12 (Physicality)** and **Rule 4 (Continuous Morphing)**.

### CC Messages

| CC | Name | Range | Purpose |
|---:|---|---|---|
| 74 | Timbre | 0–127 | MPE standard brightness/timbre dimension |

Center value: `MusicalConstants.CENTER_CC74 = 64`

### Non-CC Expressive Messages

| Message | Range | Purpose |
|---|---|---|
| Pitch Bend | 0–16383 (center: 8192) | Per-voice micro-pitch |
| Channel Pressure | 0–127 | Per-voice aftertouch |

---

## 4) MPE Zone Configuration

### Zone Type
- **MPE Lower Zone**
  - Master: Channel 1
  - Members: Channels 2–6 (5 voices)

### Message Routing

**Per-voice (member channels 2–6):**
- Note On / Note Off
- Pitch Bend (14-bit)
- Channel Pressure
- CC74

**Global (master channel 1):**
- Reserved for future use

### Receiver Requirements
- Synth must be configured for **MPE Lower Zone** with ≥5 member channels.
- Pitch bend range must be set on synth side (app does not yet send RPN setup).

---

## 5) Future Considerations

### Potential Additional CCs (if needed for Rule 10 compliance)

If the harmonic system evolves to express distinct layers:

| Potential CC | Purpose | Golden Rules Connection |
|---|---|---|
| CC 71 | Resonance / Harmonic content | Could express **instability** (Rule 8) |
| CC 1 | Modulation | Could express **function gravity** (Rule 10) |
| CC 73 | Attack time | Could express **gesture weight** |

These are not required now but noted for future layering.

### Performance Requirements (Rule 13)

For hot-path compliance:
- Pre-allocate MIDI message buffers
- No object allocation per message
- No string formatting in real-time path
- Consider ring buffer for outgoing messages

---

## Summary

| Aspect | Status | Notes |
|---|---|---|
| Slot stability | ✓ Compliant | Supports Rule 3 |
| Continuous expression | ✓ Compliant | Supports Rules 4, 12 |
| No mode switches | ✓ Compliant | Clean transport layer |
| Layered expression | Adequate | May expand for Rule 10 |
| Performance | Assumed | Implementation must verify Rule 13 |

---
