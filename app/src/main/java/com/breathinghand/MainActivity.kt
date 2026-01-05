package com.breathinghand

import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Bundle
import android.view.Choreographer
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.breathinghand.core.*
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private val touchState = MutableTouchPolar()
    private val radiusFilter = OneEuroFilter()
    private val harmonicEngine = HarmonicEngine()
    private val startTime = System.nanoTime()

    private var voiceLeader: VoiceLeader? = null
    private lateinit var overlay: HarmonicOverlayView

    // --- FRAME LATCH ---
    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }

    private val pendingState = HarmonicState()
    private var pendingDirty = false
    private var pendingRelease = false
    private var pendingReleaseReason: String = ""
    private var frameArmed = false

    // We store the active pointer IDs to pass to VoiceLeader
    private val activePointers = IntArray(5) { -1 }

    private val inputHAL = SmartInputHAL(maxSlots = 5)

    // Task 3: TimbreNavigator (translationally invariant Δx/Δy gesture space)
    private val timbreNav = TimbreNavigator(
        maxPointerId = 64,
        deadzonePx = 6f,
        rangeXPx = 220f,
        rangeYPx = 220f
    )
    // Stash Y + distance for next step (CC74 / macro), indexed by slot 0..4
    private val lastDyNormBySlot = FloatArray(5) { 0f }
    private val lastDistNormBySlot = FloatArray(5) { 0f }

    private val frameCallback = Choreographer.FrameCallback {
        frameArmed = false

        // 1. Commit New State
        if (pendingDirty) {
            MidiLogger.logCommit(pendingState)

            // PASS THE POINTERS! This enables sticky allocation.
            voiceLeader?.update(pendingState, activePointers)

            pendingDirty = false
        }

        // 2. Flush Continuous Expression
        //    (Aftertouch + Pitch Bend are routed via sticky voice binding)
        voiceLeader?.flushAftertouch()

        // 3. Release
        if (pendingRelease) {
            MidiLogger.logAllNotesOff("FRAME_LATCH_RELEASE:$pendingReleaseReason")
            voiceLeader?.allNotesOff()
            pendingRelease = false
            pendingReleaseReason = ""
            TouchMath.reset()
            radiusFilter.reset()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        overlay = HarmonicOverlayView(this, harmonicEngine)
        setContentView(overlay)
        setupMidi()
    }


    private fun latchAttackVelocitiesFromHAL() {
        // Latch note-on velocity instantly on pointer-down (F_DOWN), using
        // the attack-bypassed force sample from SmartInputHAL.
        for (s in 0 until 5) {
            val flags = inputHAL.flags[s]
            if ((flags and SmartInputHAL.F_DOWN) == 0) continue

            val pid = inputHAL.slotPointerId[s]
            if (pid != SmartInputHAL.INVALID_ID) {
                var v = (1 + (inputHAL.force[s] * 126f)).toInt().coerceIn(1, 127)
                if ((flags and SmartInputHAL.F_WACK) != 0) {
                    v = (v + 20).coerceIn(1, 127)
                }
                voiceLeader?.setSlotVelocity(s, pid, v)
            }

            // Consume DOWN so we don't re-latch every MOVE/frame.
            inputHAL.flags[s] = inputHAL.flags[s] and SmartInputHAL.F_DOWN.inv()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        try {
            TouchLogger.log(event, overlay.width, overlay.height)
            inputHAL.ingest(event)
            latchAttackVelocitiesFromHAL()

            // Capture gesture origin on pointer down; clear on pointer up/cancel
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val idx = event.actionIndex
                    val pid = event.getPointerId(idx)
                    timbreNav.onPointerDown(pid, event.getX(idx), event.getY(idx))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    val idx = event.actionIndex
                    val pid = event.getPointerId(idx)
                    timbreNav.onPointerUp(pid)
                }
            }

            // Cancel release if new activity
            if (pendingRelease && isTouchActivity(event)) {
                pendingRelease = false
                pendingReleaseReason = ""
                if (!pendingDirty) cancelFrameCallbackIfIdle()
            }

            // Handle All-Up
            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pendingDirty = false
                    pendingRelease = true
                    pendingReleaseReason = if (event.actionMasked == MotionEvent.ACTION_UP) "ACTION_UP" else "ACTION_CANCEL"
                    armFrameCallback()
                    overlay.invalidate()
                    return true
                }
            }

            // Physics Update
            val cx = overlay.width / 2f
            val cy = overlay.height / 2f
            TouchMath.update(event, cx, cy, touchState)

            if (touchState.isActive) {
                val tSec = (System.nanoTime() - startTime) / 1_000_000_000f
                val rSmooth = radiusFilter.filter(touchState.radius, tSec)

                // Expansion compensation
                val expansion01 = ((rSmooth - 150f) / (350f - 150f)).coerceIn(0f, 1f)
                inputHAL.expansion01 = expansion01

                // 1. Collect Active Pointer IDs for VoiceLeader
                //    We basically copy what SmartInputHAL knows.
                var activeCount = 0
                for (s in 0 until 5) {
                    val pid = inputHAL.slotPointerId[s]
                    activePointers[s] = pid
                    if (pid != SmartInputHAL.INVALID_ID) activeCount++
                    // Reset stored gesture values when slot is inactive
                    if (pid == SmartInputHAL.INVALID_ID) {
                        lastDyNormBySlot[s] = 0f; lastDistNormBySlot[s] = 0f
                    }
                }

                // 2. Continuous Expression (Aftertouch)
                //    We update this EVERY event, but flush once per frame.
                for (s in 0 until 5) {
                    val pid = inputHAL.slotPointerId[s]
                    if (pid == SmartInputHAL.INVALID_ID) continue

                    val force = inputHAL.force[s]
                    val at = (force * 127f).toInt()

                    // NEW: Route to specific Slot/Pointer
                    voiceLeader?.setSlotAftertouch(s, pid, at)

                    // Task 3: TimbreNavigator gesture (Δx/Δy from per-pointer origin)
                    val pIndex = event.findPointerIndex(pid)
                    if (pIndex >= 0) {
                        val x = event.getX(pIndex)
                        val y = event.getY(pIndex)
                        val g = timbreNav.compute(pid, x, y)

                        // Keep Y + distance for next step (CC74 / macro)
                        lastDyNormBySlot[s] = g.dyNorm
                        lastDistNormBySlot[s] = g.distNorm

                        // Pitch Bend from X gesture (dxNorm)
                        val bend14 = (8192 + (g.dxNorm * 8191f)).toInt().coerceIn(0, 16383)
                        voiceLeader?.setSlotPitchBend(s, pid, bend14)

                        // CC74 from Y gesture (dyNorm). Invert so "drag up" increases timbre.
                        val cc74 = (64 + (-g.dyNorm * 63f)).toInt().coerceIn(0, 127)
                        voiceLeader?.setSlotCC74(s, pid, cc74)

                    }
                }

                // 3. Harmonic Logic
                val changed = harmonicEngine.update(
                    touchState.angle,
                    rSmooth,
                    touchState.pointerCount
                )

                if (changed) {
                    pendingState.setFrom(harmonicEngine.state)
                    pendingDirty = true

                    // Note-on velocity is latched instantly on F_DOWN (see latchAttackVelocitiesFromHAL()).

                    armFrameCallback()
                } else {
                    // Even if harmony didn't change, we might want to flush AT
                    armFrameCallback()
                }
            }
            overlay.invalidate()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return true
    }

    // ... [Rest of Lifecycle / Setup Code remains the same] ...

    private fun armFrameCallback() {
        if (!frameArmed) {
            frameArmed = true
            choreographer.postFrameCallback(frameCallback)
        }
    }

    private fun cancelFrameCallbackIfIdle() {
        if (!pendingDirty && !pendingRelease && frameArmed) {
            choreographer.removeFrameCallback(frameCallback)
            frameArmed = false
        }
    }

    private fun cancelFrameCallbackHard() {
        choreographer.removeFrameCallback(frameCallback)
        frameArmed = false
        pendingDirty = false
        pendingRelease = false
        pendingReleaseReason = ""
    }

    private fun isTouchActivity(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> true
            else -> false
        }
    }

    private fun HarmonicState.setFrom(src: HarmonicState) {
        root = src.root
        quality = src.quality
        density = src.density
    }

    private fun setupMidi() {
        try {
            val midiManager = getSystemService(MIDI_SERVICE) as? MidiManager ?: return
            @Suppress("DEPRECATION")
            val devices = midiManager.devices // Simplified for brevity
            // ... (Use existing device finding logic) ...

            // For the Refactor output, assume standard setup code fits here.
            // Just ensuring voiceLeader is initialized with MidiOut.
            val usbDevice = devices.firstOrNull { it.inputPortCount > 0 } // Quick fallback
            if (usbDevice != null) {
                midiManager.openDevice(usbDevice, { device ->
                    if (device != null) {
                        val port = device.openInputPort(0)
                        if (port != null) {
                            voiceLeader = VoiceLeader(MidiOut(port))
                            runOnUiThread { Toast.makeText(this, "Connected", Toast.LENGTH_SHORT).show() }
                        }
                    }
                }, null)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onPause() {
        super.onPause()
        cancelFrameCallbackHard()
        MidiLogger.logAllNotesOff("onPause")
        voiceLeader?.allNotesOff()
        TouchMath.reset()
        radiusFilter.reset()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelFrameCallbackHard()
        voiceLeader?.close()
    }
}