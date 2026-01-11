# Oboe & FluidSynth Implementation Summary

**Project:** Breathing Hand  
**Date:** January 2026  
**Platform:** Kotlin Multiplatform (Android/iOS)

---

## Overview

This document summarizes the architectural changes and optimizations implemented for integrating Oboe audio engine and FluidSynth synthesis with the Breathing Hand MPE touch controller.

---

## Architecture Components

### 1. MidiOutput Abstraction Layer

**File:** `shared/src/commonMain/kotlin/com/breathinghand/core/MidiOutput.kt`

Created a hot-path interface to support multiple routing modes:

```kotlin
interface MidiOutput {
    fun noteOn(slot: Int, note: Int, velocity: Int)
    fun noteOff(slot: Int, note: Int)
    fun pitchBend(slot: Int, bend14: Int)
    fun channelPressure(slot: Int, pressure: Int)
    fun cc(slot: Int, ccNum: Int, value: Int)
    fun allNotesOff()
    fun close()
}
```

#### Implementations:

1. **MpeMidiOutput**
   - 1-to-1 slot-to-channel mapping (Slot 0 → Channel 1, etc.)
   - Supports external MPE hardware/software
   - Per-voice expression control

2. **StandardMidiOutput**
   - All slots → Channel 0 (Human Channel 1)
   - Zero-allocation tracking via `BooleanArray(128)`
   - Reductive logic: only Slot 0 controls global pitch bend, CC, and aftertouch
   - Prevents "fighting" where multiple fingers send conflicting messages

---

## CC11 Expression Mapping for FluidSynth

### Problem
FluidSynth requires continuous CC11 (Expression) messages for dynamic volume control. Raw aftertouch values (0-127) produce inconsistent loudness response.

### Solution: Soft-Knee LUT with Stabilization

**File:** `VoiceLeader.kt`

#### Components:

1. **Soft-Knee Response Curve**
   - Built once at initialization via `buildCc11Lut()`
   - Parameters:
     - `MIN_EXPRESSION = 100` (floor to prevent inaudible synth)
     - `CC11_KNEE_X = 0.30` (breakpoint at 30% input)
     - `CC11_KNEE_LIFT = 1.4` (boost low-level loudness)
     - `CC11_EXP_LOW = 0.35` (power curve below knee)
     - `CC11_EXP_HIGH = 1.2` (power curve above knee)
   - O(1) lookup during hot path

2. **Touchscreen Stabilizer**
   - **Problem:** Glass touchscreens often report near-zero aftertouch even when notes are active
   - **Solution:** 
     - `CC11_AFTERTOUCH_DEADZONE = 4` (detect weak pressure)
     - `CC11_PLAYING_FLOOR = 110` (enforce stronger floor when playing)
   - If any note is active AND max aftertouch ≤ deadzone → enforce playing floor

3. **Frame-Based Rate Limiter**
   - **Purpose:** Reduce CC11 message spam to FluidSynth
   - **Mechanism:**
     - `cc11SendCooldown` counter (primitive int, zero allocations)
     - Decrements once per frame
     - Sends CC11 only when `cooldown <= 0 OR value_jump >= 3`
     - Sets `cooldown = 2` after send (~50 Hz at 60fps)
   - **Bypass:** Large jumps (≥3) always bypass rate limit for responsiveness

4. **Per-Slot Aggregation + Smoothing**
   - Each slot has `pendingCC11Target[slot]` mapped from aftertouch
   - Final CC11 = max across all slots
   - Exponential smoothing: `smoothed = ((prev * 7) + target) >> 3` (alpha=1/8)
   - Deduplication: skip if unchanged

5. **Feature Flag**
   - `INTERNAL_USE_CC11_EXPRESSION = true` (from `MusicalConstants`)
   - `setUseExpressionCc11(enabled: Boolean)` public API
   - Allows velocity-only loudness mode (bypass CC11 entirely)

---

## Ghost Note & Wrong-Chord Fixes

### Problems

1. **Ghost Note:** Lifting multiple fingers simultaneously caused brief extra note
2. **Wrong Chord:** Touching down multiple fingers produced momentary incorrect harmony

### Evolution of Fixes

#### Initial Fix: Frame-Based Attack/Release Freeze (VoiceLeader)

**File:** `VoiceLeader.kt`

Added state-based freezing logic to suppress note emission during transitions:

```kotlin
private var lastFrameActiveSlots = 0      // Current frame history
private var lastFrameActiveSlots2 = 0     // Previous frame history
private var attackSettleFrames = 0        // Attack delay counter
private var releaseFreezeActive = false   // Release suppression flag
```

- **Attack Settle:** 1-frame delay on finger-add to let harmony settle
- **Release Freeze:** Suppress role compaction during cascading lifts

**Limitation:** Addressed symptoms within VoiceLeader but didn't fix root cause upstream.

---

#### Revised Fix: Serial-Based Harmony Gate (VoiceLeader)

**File:** `VoiceLeader.kt`

Replaced frame-based freeze with serial tracking to ensure harmony settles before note emission:

```kotlin
private var touchSerial: Int = 0          // Latest touch change counter
private var harmonySerialApplied: Int = 0 // Serial when roles/targets were last updated
```

**Logic:**
1. `touchSerial` increments on every `update()` call (touch change)
2. `harmonySerialApplied` tracks when `fillRoleNotes()` was last called
3. If `touchSerial > harmonySerialApplied`, skip note-on emission (harmony not settled)
4. Still allow note-offs and continuous updates (CC11, pitch bend, aftertouch)

**Limitation:** Works correctly if inputs are consistent, but can't compensate for inconsistent snapshots.

---

#### Final Fix: Release Coalescing (MainActivity)

**File:** `app/src/main/java/com/breathinghand/MainActivity.kt`  
**Documentation:** `DOCS/Release_Coalescing_Implementation.md`

**Root Cause Discovery:** HarmonicState and activePointerIds were derived from **different touch snapshots** because Android delivers pointer-up events sequentially.

**Solution:** Batch near-simultaneous `ACTION_POINTER_UP` events within a 10ms coalescing window:

```kotlin
companion object {
    private const val COALESCE_WINDOW_MS = 10L
}

// Zero-allocation buffering
private val mainHandler = Handler(Looper.getMainLooper())
private var coalesceStartMs: Long = 0L
private var isCoalescing: Boolean = false
private var coalescedEvent: MotionEvent? = null
```

**How It Works:**
1. When `ACTION_POINTER_UP` arrives, buffer it for 10ms using `MotionEvent.obtain()`
2. If more pointer-ups arrive within the window, replace the buffer with the latest event
3. After 10ms, process the coalesced event via `Handler.postDelayed()`
4. Normal events (`MOVE`, `DOWN`, `UP`, `CANCEL`) bypass coalescing for instant response

**Result:**
- **Atomic snapshot boundary:** `HarmonicState` and `activePointerIds` now derive from same `TouchFrame`
- **No intermediate states:** Multi-finger lifts seen as single transition (3 fingers → 0 fingers)
- **No ghost notes:** VoiceLeader receives consistent input, serial gate prevents premature emission
- **No wrong-chord flash:** Harmony settles within coalescing window before first update

**Performance:**
- 10ms latency only on finger lifts (negligible)
- Move/down events remain instant
- Zero allocations (reuses single `MotionEvent`)

---

### Summary of Fixes

| Fix | Scope | Status | Effectiveness |
|-----|-------|--------|---------------|
| Frame-based freeze | VoiceLeader | Replaced | Addressed symptoms, not root cause |
| Serial harmony gate | VoiceLeader | Active | Works correctly with consistent inputs |
| Release coalescing | MainActivity | Active | Ensures inputs are consistent (upstream fix) |

**Key Insight:** VoiceLeader serial gate + MainActivity release coalescing = complete solution. The serial gate prevents wrong notes *if* given consistent data; coalescing ensures the data *is* consistent.

---

## Performance Guarantees

### Zero Allocations in Hot Path

- **Primitive arrays:** `IntArray`, `BooleanArray` (no iterators)
- **No string concatenation**
- **No collections:** HashSet → BooleanArray for note tracking
- **Frame-based cooldown:** `int` counter instead of `TimeSource.Monotonic`

### KMP-Safe

- **No platform-specific imports** in shared code
- **No `android.*` dependencies**
- `kotlin.math` only (abs, pow)
- Frame-based timing (no `kotlin.time.TimeSource`)

---

## Configuration Constants

### CC11 Tuning (VoiceLeader.kt):
```kotlin
MIN_EXPRESSION = 100           // Minimum CC11 value
CC11_KNEE_X = 0.30f           // Soft-knee breakpoint (30%)
CC11_KNEE_LIFT = 1.4f         // Low-level boost multiplier
CC11_EXP_LOW = 0.35f          // Power below knee (boosts light touch)
CC11_EXP_HIGH = 1.2f          // Power above knee (preserves headroom)
CC11_AFTERTOUCH_DEADZONE = 4  // Glass touchscreen floor
CC11_PLAYING_FLOOR = 110      // Minimum when notes active
```

### Rate Limiting:
```kotlin
cc11SendCooldown = 2          // ~50 Hz at 60fps
jump_bypass_threshold = 3     // Allow large changes immediately
```

### Serial Harmony Gate:
```kotlin
touchSerial                   // Increments on every update() call
harmonySerialApplied          // Tracks when harmony was last computed
```

### Release Coalescing (MainActivity.kt):
```kotlin
COALESCE_WINDOW_MS = 10L      // Batch pointer-up events within 10ms
```

---

## Integration Points

### Oboe Audio Engine
- **Expected:** Native C++ bridge for low-latency audio
- **Interface:** `MidiOut.sendNoteOn()`, `sendCC()`, etc.
- **Routing:** `StandardMidiOutput` → Slot 0 → FluidSynth on Channel 0

### FluidSynth
- **Message Requirements:**
  - NoteOn/NoteOff (channel 0)
  - CC11 (Expression) for dynamic volume
  - CC74 (Brightness) [optional]
  - Pitch Bend (±2 semitones typical)
  - Channel Pressure (aftertouch)
- **Optimizations:**
  - CC11 rate limiting reduces DSP load
  - Soft-knee mapping improves loudness consistency
  - Deduplication prevents redundant processing

---

## Testing Checklist

- [ ] No ghost notes on 2/3/4-finger simultaneous lift
- [ ] No wrong-chord flash on multi-finger touch-down
- [ ] CC11 feels smooth and responsive (not sluggish)
- [ ] Glass touchscreen produces audible sound (not silent at light pressure)
- [ ] Large pressure changes respond immediately (bypass rate limiter)
- [ ] Pitch bend and aftertouch work per-finger (MPE) or global (Standard)
- [ ] AllNotesOff correctly resets all state (no stuck notes)
- [ ] No allocations in profiler during sustained playing

---

## Future Considerations

### Potential Optimizations:
1. **Adaptive rate limiting:** Adjust cooldown based on frame rate
2. **Per-slot CC11:** Send individual expression per MPE channel (currently mono)
3. **CC74 mapping:** Similar LUT for brightness control
4. **Pressure curve editor:** Expose knee/exponent parameters in UI

### Oboe-Specific:
1. **Buffer size tuning:** Balance latency vs. dropout risk
2. **Sample rate matching:** FluidSynth config must align with Oboe
3. **Thread priority:** Ensure audio callback runs at SCHED_FIFO
4. **Underrun handling:** Graceful recovery from buffer starvation

### FluidSynth-Specific:
1. **Reverb/chorus:** Consider disabling for lower CPU usage
2. **Polyphony limits:** Test with MAX_VOICES simultaneous notes
3. **SoundFont optimization:** Preload samples, minimize disk I/O
4. **MIDI event scheduling:** Ensure frame-accurate timing

---

## Files Modified

### Core Logic:
- `shared/src/commonMain/kotlin/com/breathinghand/core/VoiceLeader.kt`
  - CC11 mapping/rate limiting
  - Attack/release freeze logic
  - Frame-based cooldown

### Abstraction Layer:
- `shared/src/commonMain/kotlin/com/breathinghand/core/MidiOutput.kt`
  - `MidiOutput` interface
  - `StandardMidiOutput` (FluidSynth routing)
  - `MpeMidiOutput` (external hardware)

### Configuration:
- `shared/src/commonMain/kotlin/com/breathinghand/core/MusicalConstants.kt`
  - `INTERNAL_USE_CC11_EXPRESSION` flag
  - MPE constants (CENTER_PITCH_BEND, CENTER_CC74, etc.)

---

## References

- [Oboe Documentation](https://github.com/google/oboe)
- [FluidSynth API](https://www.fluidsynth.org/api/)
- [MPE Specification](https://www.midi.org/specifications/midi-association-features/midi-polyphonic-expression-mpe)
- [Android Audio Best Practices](https://developer.android.com/ndk/guides/audio)

---

**End of Document**
