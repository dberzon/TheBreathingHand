package com.breathinghand.core

import com.breathinghand.core.midi.MidiOut

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

class VoiceLeader(private val midi: MidiOut) {

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

    // Last harmony snapshot that affects mapping (keeps hot path stable).
    private var lastRootPc = Int.MIN_VALUE
    private var lastFingerCount = Int.MIN_VALUE
    private var lastTriad = Int.MIN_VALUE
    private var lastSeventh = Int.MIN_VALUE
    private var lastUnstable = false

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
        updateRolesByFingerChanges()
        updateTargetsByRole()
        solveAndSendBySlot()
        flushContinuous()
    }

    /**
     * Flush all continuous expression (Aftertouch + Pitch Bend + CC74).
     * Safe to call every frame (deduped by lastSent arrays).
     */
    fun flushContinuous() {
        flushAftertouch()
        flushPitchBend()
        flushCC74()
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
        pendingAftertouch[slotIndex] = value.coerceIn(0, 127)

        val v = voices[slotIndex]
        if (v.pointerId == TouchFrame.INVALID_ID && pointerId != TouchFrame.INVALID_ID) {
            v.pointerId = pointerId
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
            midi.sendChannelPressure(voices[i].channel, v)
        }
    }

    private fun flushPitchBend() {
        for (i in 0 until MusicalConstants.MAX_VOICES) {
            val v = pendingPitchBend[i]
            if (v == lastSentPitchBend[i]) continue
            lastSentPitchBend[i] = v
            midi.sendPitchBend(voices[i].channel, v)
        }
    }

    private fun flushCC74() {
        for (i in 0 until MusicalConstants.MAX_VOICES) {
            val v = pendingCC74[i]
            if (v == lastSentCC74[i]) continue
            lastSentCC74[i] = v
            midi.sendCC(voices[i].channel, 74, v)
        }
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
                    midi.sendNoteOff(v.channel, v.note)
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
                    midi.sendNoteOff(v.channel, v.note)
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
                    midi.sendNoteOff(v.channel, v.note)
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
                midi.sendNoteOn(v.channel, target, vel)
                continue
            }

            if (v.note != target) {
                midi.sendNoteOff(v.channel, v.note)

                val vel = if (pid in 0 until velocityByPointerId.size)
                    velocityByPointerId[pid]
                else
                    MusicalConstants.DEFAULT_VELOCITY

                v.note = target
                midi.sendNoteOn(v.channel, target, vel)
            }
        }
    }

    fun allNotesOff() {
        for (i in 0 until MusicalConstants.MAX_VOICES) {
            val v = voices[i]
            if (v.active) {
                midi.sendNoteOff(v.channel, v.note)
            }

            // Reset channel state.
            midi.sendChannelPressure(v.channel, 0)
            midi.sendPitchBend(v.channel, MusicalConstants.CENTER_PITCH_BEND)
            midi.sendCC(v.channel, 74, MusicalConstants.CENTER_CC74)

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
        }

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
        midi.close()
    }
}