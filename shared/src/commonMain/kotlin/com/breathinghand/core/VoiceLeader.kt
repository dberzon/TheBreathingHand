package com.breathinghand.core

import kotlin.math.abs
import kotlin.math.pow

/**
 * Slot-stable MPE voice (slot index == voice index).
 * PointerId binding is absolute: slot i is always MIDI channel (i+1).
 *
 * Note assignment (layer roles):
 * - Notes are assigned by ROLE (layer) and mapped to active slots.
 * - Roles are stable on finger-add, and compact on finger-remove, so:
 * - 1 finger always becomes reference/root role (role 0)
 * - 2 fingers => roles 0..1 (root + fifth)
 * - 3 fingers => roles 0..2 (+ triad layer)
 * - 4 fingers => roles 0..3 (+ seventh layer)
 *
 * KMP-safe: no android.* imports.
 * Zero allocations in update hot path.
 */
private data class VoiceSlot(
    val channel: Int,
    var note: Int = 0,
    var active: Boolean = false,
    var pointerId: Int = TouchFrame.INVALID_ID
)

class VoiceLeader(private val output: MidiOutput) { // CHANGED: midi -> output

    // Slot index == voice index. Channel is fixed per slot for "sticky" MPE behavior.
    private val voices = Array(MusicalConstants.MAX_VOICES) { i -> VoiceSlot(channel = i + 1) }

    // Target note per slot (0 means "silent / no target").
    private val targetNotes = IntArray(MusicalConstants.MAX_VOICES)

    // ROLE -> note (v0.1 uses roles 0..3)
    private val roleNotes = IntArray(4)

    // Slot -> role (0..3), or -1 if unassigned/extra finger.
    private val roleBySlot = IntArray(MusicalConstants.MAX_VOICES) { -1 }

    // Scratch (no allocations)
    private val activeSlotsScratch = IntArray(MusicalConstants.MAX_VOICES)

    private var lastActiveCount = 0
    private var lastFrameActiveSlots = 0
    private var retargetFreezeFrames = 0

    // PointerId -> velocity lookup (O(1), no allocations).
    private val velocityByPointerId =
        IntArray(MusicalConstants.MAX_POINTER_ID + 1) { MusicalConstants.DEFAULT_VELOCITY }

    // Continuous per-slot messages (no allocations).
    private val pendingAftertouch = IntArray(MusicalConstants.MAX_VOICES)
    private val lastSentAftertouch = IntArray(MusicalConstants.MAX_VOICES) { -1 }

    private val pendingPitchBend =
        IntArray(MusicalConstants.MAX_VOICES) { MusicalConstants.CENTER_PITCH_BEND }
    private val lastSentPitchBend = IntArray(MusicalConstants.MAX_VOICES) { -1 }

    private val pendingCC74 =
        IntArray(MusicalConstants.MAX_VOICES) { MusicalConstants.CENTER_CC74 }
    private val lastSentCC74 = IntArray(MusicalConstants.MAX_VOICES) { -1 }

    // CC11 (Expression) mapping — mono internal path (no allocations).
    // Tweakables (adjust these for desired responsiveness):
    // - MIN_EXPRESSION: the minimum CC11 value (floor) to avoid very quiet synth.
    // - Soft-knee LUT parameters: tune the response shape without hot-path math.
    //   Increase KNEE_LIFT to raise low-level loudness; EXP_LOW < 1 boosts small touches.
    //   Keep EXP_HIGH >= 1 to preserve headroom.
    private val MIN_EXPRESSION = 100 // Tweakable: minimum CC11 (0..127)
    private val CC11_KNEE_X = 0.30f
    private val CC11_KNEE_LIFT = 1.4f
    private val CC11_EXP_LOW = 0.35f
    private val CC11_EXP_HIGH = 1.2f

    // Per-slot targets (set from aftertouch); final CC11 sent is the max across slots.
    private val pendingCC11Target = IntArray(MusicalConstants.MAX_VOICES) { 0 }

    // CC11 LUT: aftertouch (0..127) -> CC11 (0..127). Built once, no hot-path math.
    private val cc11Lut = IntArray(128)

    // Feature flag: enable or disable Expression -> CC11 mapping for internal synth.
    // Default from MusicalConstants. Can be exposed in UI later.
    private var useExpressionCc11: Boolean = MusicalConstants.INTERNAL_USE_CC11_EXPRESSION

    // Mono smoothed/dedup state sent to internal synth (channel 0).
    private var pendingCC11SmoothedMono = 0
    private var lastSentCC11Mono = -1
    private var cc11SendCooldown = 0  // Frame-based cooldown: decremented each frame, blocks send if > 0 (unless jump >= 3)

    // Last harmony snapshot that affects mapping (keeps hot path stable).
    private var lastRootPc = Int.MIN_VALUE
    private var lastFingerCount = Int.MIN_VALUE
    private var lastTriad = Int.MIN_VALUE
    private var lastSeventh = Int.MIN_VALUE
    private var lastUnstable = false
    // Touchscreen stabilizer (prevents faint sound when aftertouch is near-zero on glass).
    // If any note is active and max aftertouch is <= deadzone, we enforce a stronger CC11 floor.
    private val CC11_AFTERTOUCH_DEADZONE = 4          // 0..127 (try 4..8)
    private val CC11_PLAYING_FLOOR = (MIN_EXPRESSION + 10).coerceAtMost(127)             // must be >= MIN_EXPRESSION (try 95..110)


    init {
        buildCc11Lut()
    }

    /**
     * Primary update (safe to call every frame; no allocations).
     *
     * @param input Harmonic state (layered).
     * @param activePointerIds slot-aligned pointerIds; TouchFrame.INVALID_ID for empty slots.
     */
    fun update(input: HarmonicState, activePointerIds: IntArray) {
        val harmonicChanged =
            input.rootPc != lastRootPc ||
                    input.fingerCount != lastFingerCount ||
                    input.triad != lastTriad ||
                    input.seventh != lastSeventh ||
                    (input.harmonicInstability > MusicalConstants.INSTABILITY_THRESHOLD) != lastUnstable

        if (harmonicChanged) {
            lastRootPc = input.rootPc
            lastFingerCount = input.fingerCount
            lastTriad = input.triad
            lastSeventh = input.seventh
            lastUnstable = input.harmonicInstability >= MusicalConstants.INSTABILITY_THRESHOLD

            HarmonicFieldMapV01.fillRoleNotes(input, roleNotes)
        }

        updateAllocationBySlot(activePointerIds)

        val activeNow = countActiveSlots()
        if (activeNow < lastFrameActiveSlots) {
            retargetFreezeFrames = 2
        }
        lastFrameActiveSlots = activeNow

        if (retargetFreezeFrames > 0) {
            retargetFreezeFrames--
            flushContinuous()
            return
        }

        updateRolesByFingerChanges()
        updateTargetsByRole()
        solveAndSendBySlot()
        flushContinuous()
    }

    private fun countActiveSlots(): Int {
        var count = 0
        for (slot in 0 until MusicalConstants.MAX_VOICES) {
            if (voices[slot].pointerId != TouchFrame.INVALID_ID) count++
        }
        return count
    }

    /**
     * Flush all continuous expression (Aftertouch + Pitch Bend + CC74).
     * Safe to call every frame (deduped by lastSent arrays).
     */
    fun flushContinuous() {
        flushAftertouch()
        flushPitchBend()
        flushCC74()
        flushCC11()
    }

    /**
     * Velocity latch. Also "primes" pointer->slot mapping if the slot has not been bound yet.
     */
    fun setSlotVelocity(slotIndex: Int, pointerId: Int, velocity: Int) {
        if (slotIndex !in 0 until MusicalConstants.MAX_VOICES) return
        val pidOk = pointerId in 0 until velocityByPointerId.size
        if (pidOk) velocityByPointerId[pointerId] = velocity.coerceIn(1, 127)

        val v = voices[slotIndex]
        if (v.pointerId == TouchFrame.INVALID_ID && pointerId != TouchFrame.INVALID_ID) {
            v.pointerId = pointerId
        }
    }

    fun setSlotAftertouch(slotIndex: Int, pointerId: Int, value: Int) {
        if (slotIndex !in 0 until MusicalConstants.MAX_VOICES) return
        val val8 = value.coerceIn(0, 127)
        pendingAftertouch[slotIndex] = val8
        // Map aftertouch to CC11 (Expression) target for smoothing/dedup (per-slot target)
        pendingCC11Target[slotIndex] = mapAftertouchToCC11(val8)

        val v = voices[slotIndex]
        if (v.pointerId == TouchFrame.INVALID_ID && pointerId != TouchFrame.INVALID_ID) {
            v.pointerId = pointerId
        }
    }

    // Map 0..127 aftertouch to CC11 via LUT (O(1), zero allocations in hot path).
    private fun mapAftertouchToCC11(after: Int): Int {
        val idx = if (after < 0) 0 else if (after > 127) 127 else after
        return cc11Lut[idx]
    }

    // Build CC11 LUT once using a soft-knee response. Off hot-path.
    private fun buildCc11Lut() {
        val knee = CC11_KNEE_X.coerceIn(0.0f, 0.95f)
        var kneeOut = knee * CC11_KNEE_LIFT
        // Clamp kneeOut to [0, 0.95]
        if (kneeOut < 0f) kneeOut = 0f
        if (kneeOut > 0.95f) kneeOut = 0.95f

        val range = (127 - MIN_EXPRESSION)
        for (a in 0..127) {
            val x = a / 127f
            val y = if (x <= knee) {
                val xn = if (knee > 0f) (x / knee) else 0f
                val xnClamped = if (xn < 0f) 0f else if (xn > 1f) 1f else xn
                xnClamped.pow(CC11_EXP_LOW) * kneeOut
            } else {
                val denom = (1f - knee)
                val t = if (denom > 0f) ((x - knee) / denom) else 1f
                val tClamped = if (t < 0f) 0f else if (t > 1f) 1f else t
                kneeOut + tClamped.pow(CC11_EXP_HIGH) * (1f - kneeOut)
            }

            val ccF = MIN_EXPRESSION + y * range
            var cc = (ccF + 0.5f).toInt()
            if (cc < MIN_EXPRESSION) cc = MIN_EXPRESSION
            if (cc > 127) cc = 127
            cc11Lut[a] = cc
        }
    }

    fun setSlotPitchBend(slotIndex: Int, pointerId: Int, bend14: Int) {
        if (slotIndex !in 0 until MusicalConstants.MAX_VOICES) return
        pendingPitchBend[slotIndex] = bend14.coerceIn(0, 16383)

        val v = voices[slotIndex]
        if (v.pointerId == TouchFrame.INVALID_ID && pointerId != TouchFrame.INVALID_ID) {
            v.pointerId = pointerId
        }
    }

    fun setSlotCC74(slotIndex: Int, pointerId: Int, value: Int) {
        if (slotIndex !in 0 until MusicalConstants.MAX_VOICES) return
        pendingCC74[slotIndex] = value.coerceIn(0, 127)

        val v = voices[slotIndex]
        if (v.pointerId == TouchFrame.INVALID_ID && pointerId != TouchFrame.INVALID_ID) {
            v.pointerId = pointerId
        }
    }

    fun flushAftertouch() {
        for (i in 0 until MusicalConstants.MAX_VOICES) {
            val v = pendingAftertouch[i]
            if (v == lastSentAftertouch[i]) continue
            lastSentAftertouch[i] = v
            output.channelPressure(i, v) // CHANGED
        }
    }

    private fun flushPitchBend() {
        for (i in 0 until MusicalConstants.MAX_VOICES) {
            val v = pendingPitchBend[i]
            if (v == lastSentPitchBend[i]) continue
            lastSentPitchBend[i] = v
            output.pitchBend(i, v) // CHANGED
        }
    }

    private fun flushCC74() {
        for (i in 0 until MusicalConstants.MAX_VOICES) {
            val v = pendingCC74[i]
            if (v == lastSentCC74[i]) continue
            lastSentCC74[i] = v
            output.cc(i, 74, v) // CHANGED
        }
    }

    private fun flushCC11() {
        // Bypass: if disabled, do not send CC11 at all (velocity-only loudness).
        if (!useExpressionCc11) return

        // Decrement frame-based cooldown each frame.
        if (cc11SendCooldown > 0) cc11SendCooldown--

        var target = 0
        var maxAfter = 0
        var anyActive = false

        // Aggregate per-slot targets (max) + gather raw aftertouch and active state
        for (i in 0 until MusicalConstants.MAX_VOICES) {
            val t = pendingCC11Target[i]
            if (t > target) target = t

            val a = pendingAftertouch[i]
            if (a > maxAfter) maxAfter = a

            if (voices[i].active) anyActive = true
        }

        // Touchscreen stabilizer:
        // If we are actively playing notes but aftertouch isn't providing meaningful range,
        // enforce a stronger CC11 floor to keep the sound present.
        if (anyActive && maxAfter <= CC11_AFTERTOUCH_DEADZONE) {
            val floor = if (CC11_PLAYING_FLOOR < MIN_EXPRESSION) MIN_EXPRESSION else CC11_PLAYING_FLOOR
            if (target < floor) target = floor
        }
        if (!anyActive) {
            // Nobody is playing -> don't keep pushing expression around.
            // Let last value hold; or you can optionally return to avoid sending at rest.
            return
        }
        val prev = pendingCC11SmoothedMono
        val smoothed = ((prev * 7) + target) ushr 3 // alpha=1/8
        if (smoothed == pendingCC11SmoothedMono && smoothed == lastSentCC11Mono) return
        pendingCC11SmoothedMono = smoothed
        if (smoothed == lastSentCC11Mono) return

        // Frame-based rate limit: once per 2 frames unless value jumps by >= 3.
        val jump = abs(smoothed - lastSentCC11Mono)
        val canSend = cc11SendCooldown <= 0 || jump >= 3
        if (!canSend) return

        lastSentCC11Mono = smoothed
        cc11SendCooldown = 2

        // Send to slot 0 (StandardMidiOutput will emit to ch0; MPE will emit to ch1)
        output.cc(0, 11, smoothed)
    }

    /** Enable/disable Expression→CC11 mapping for internal synth (default: true). */
    fun setUseExpressionCc11(enabled: Boolean) {
        useExpressionCc11 = enabled
    }

    /**
     * Role assignment:
     * - New fingers get the next available role (no replacement).
     * - On finger removal, roles are compacted in ascending prior-role order, so
     * the remaining finger(s) become roles 0..N-1.
     *
     * This guarantees: 1 finger always becomes the reference/root role (role 0).
     */
    private fun updateRolesByFingerChanges() {
        // Build active slot list
        var nActive = 0
        for (slot in 0 until MusicalConstants.MAX_VOICES) {
            if (voices[slot].pointerId != TouchFrame.INVALID_ID) {
                activeSlotsScratch[nActive] = slot
                nActive++
            } else {
                roleBySlot[slot] = -1
            }
        }

        if (nActive == 0) {
            lastActiveCount = 0
            return
        }

        // Used roles bitmask among currently active slots
        var usedMask = 0
        for (i in 0 until nActive) {
            val slot = activeSlotsScratch[i]
            val r = roleBySlot[slot]
            if (r in 0..3) usedMask = usedMask or (1 shl r)
        }

        if (nActive > lastActiveCount) {
            // Addition: assign next roles to newly active slots (role==-1), deterministic by slot order.
            for (i in 0 until nActive) {
                val slot = activeSlotsScratch[i]
                if (roleBySlot[slot] != -1) continue

                var nextRole = -1
                for (r in 0..3) {
                    if ((usedMask and (1 shl r)) == 0) {
                        nextRole = r
                        break
                    }
                }
                // If roles 0..3 are all used, leave as -1 (extra finger is silent in v0.1).
                if (nextRole != -1) {
                    roleBySlot[slot] = nextRole
                    usedMask = usedMask or (1 shl nextRole)
                }
            }
        } else if (nActive < lastActiveCount) {
            // Removal: compact roles by ascending prior-role.
            // Insertion sort activeSlotsScratch by roleBySlot[slot].
            for (i in 1 until nActive) {
                val s = activeSlotsScratch[i]
                val rS = roleRank(roleBySlot[s])
                var j = i - 1
                while (j >= 0) {
                    val sj = activeSlotsScratch[j]
                    val rJ = roleRank(roleBySlot[sj])
                    if (rJ <= rS) break
                    activeSlotsScratch[j + 1] = sj
                    j--
                }
                activeSlotsScratch[j + 1] = s
            }

            // Reassign roles 0..(nActive-1) up to 3. Anything beyond is silent.
            for (i in 0 until nActive) {
                val slot = activeSlotsScratch[i]
                roleBySlot[slot] = if (i <= 3) i else -1
            }
        } else {
            // Same count: keep roles.
            // If any active slot is missing a role (rare), assign next available deterministically.
            for (i in 0 until nActive) {
                val slot = activeSlotsScratch[i]
                if (roleBySlot[slot] != -1) continue
                var nextRole = -1
                for (r in 0..3) {
                    if ((usedMask and (1 shl r)) == 0) {
                        nextRole = r
                        break
                    }
                }
                if (nextRole != -1) {
                    roleBySlot[slot] = nextRole
                    usedMask = usedMask or (1 shl nextRole)
                }
            }
        }

        lastActiveCount = nActive
    }

    private fun roleRank(role: Int): Int {
        return if (role in 0..3) role else 999
    }

    private fun updateTargetsByRole() {
        for (slot in 0 until MusicalConstants.MAX_VOICES) {
            val pid = voices[slot].pointerId
            if (pid == TouchFrame.INVALID_ID) {
                targetNotes[slot] = 0
                continue
            }

            val role = roleBySlot[slot]
            targetNotes[slot] = if (role in 0..3) roleNotes[role] else 0
        }
    }

    /**
     * Slot-stable binding:
     * - Each slot reads activePointerIds[slot] (or INVALID_ID).
     * - PointerId changes in a slot force an immediate note-off (deterministic).
     */
    private fun updateAllocationBySlot(activePointerIds: IntArray) {
        val n = minOf(activePointerIds.size, MusicalConstants.MAX_VOICES)

        for (slot in 0 until MusicalConstants.MAX_VOICES) {
            val wantPid = if (slot < n) activePointerIds[slot] else TouchFrame.INVALID_ID
            val v = voices[slot]
            val havePid = v.pointerId

            if (wantPid == TouchFrame.INVALID_ID) {
                // Slot empty -> release and clear binding.
                if (v.active) {
                    output.noteOff(slot, v.note) // CHANGED
                    v.active = false
                    v.note = 0
                }
                v.pointerId = TouchFrame.INVALID_ID
                continue
            }

            // Slot occupied.
            if (havePid == TouchFrame.INVALID_ID) {
                v.pointerId = wantPid
                continue
            }

            if (havePid != wantPid) {
                // Different pointer landed in this slot -> deterministic rebind.
                if (v.active) {
                    output.noteOff(slot, v.note) // CHANGED
                    v.active = false
                    v.note = 0
                }
                v.pointerId = wantPid
            }
        }
    }

    /**
     * Sends notes based on slot targets.
     * No sorting, no allocations.
     */
    private fun solveAndSendBySlot() {
        for (slot in 0 until MusicalConstants.MAX_VOICES) {
            val v = voices[slot]
            val pid = v.pointerId
            if (pid == TouchFrame.INVALID_ID) continue

            val target = targetNotes[slot]
            if (target == 0) {
                if (v.active) {
                    output.noteOff(slot, v.note) // CHANGED
                    v.active = false
                    v.note = 0
                }
                continue
            }

            if (!v.active) {
                val vel = if (pid in 0 until velocityByPointerId.size)
                    velocityByPointerId[pid]
                else
                    MusicalConstants.DEFAULT_VELOCITY

                v.note = target
                v.active = true
                output.noteOn(slot, target, vel) // CHANGED
                continue
            }

            if (v.note != target) {
                output.noteOff(slot, v.note) // CHANGED

                val vel = if (pid in 0 until velocityByPointerId.size)
                    velocityByPointerId[pid]
                else
                    MusicalConstants.DEFAULT_VELOCITY

                v.note = target
                output.noteOn(slot, target, vel) // CHANGED
            }
        }
    }

 fun allNotesOff() {
    // Delegate to output implementation for proper cleanup
    output.allNotesOff()

    for (i in 0 until MusicalConstants.MAX_VOICES) {
        val v = voices[i]

        v.note = 0
        v.active = false
        v.pointerId = TouchFrame.INVALID_ID

        targetNotes[i] = 0
        roleBySlot[i] = -1

        pendingAftertouch[i] = 0
        lastSentAftertouch[i] = -1

        pendingPitchBend[i] = MusicalConstants.CENTER_PITCH_BEND
        lastSentPitchBend[i] = -1

        pendingCC74[i] = MusicalConstants.CENTER_CC74
        lastSentCC74[i] = -1

        pendingCC11Target[i] = 0
    }

    // Reset mono CC11 smoothing/dedup (once)
    pendingCC11SmoothedMono = 0
    lastSentCC11Mono = -1
    cc11SendCooldown = 0
    lastFrameActiveSlots = 0
    retargetFreezeFrames = 0

    // Reset velocity cache to default values
    for (i in 0 until velocityByPointerId.size) {
        velocityByPointerId[i] = MusicalConstants.DEFAULT_VELOCITY
    }

    // Clear roles + counts
    lastActiveCount = 0
    roleNotes[0] = 0
    roleNotes[1] = 0
    roleNotes[2] = 0
    roleNotes[3] = 0

    lastRootPc = Int.MIN_VALUE
    lastFingerCount = Int.MIN_VALUE
    lastTriad = Int.MIN_VALUE
    lastSeventh = Int.MIN_VALUE
    lastUnstable = false
}


    fun close() {
        allNotesOff()
        output.close() // CHANGED
    }
}