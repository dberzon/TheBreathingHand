package com.breathinghand

import android.media.midi.MidiManager
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.breathinghand.core.*
import com.breathinghand.core.midi.MidiOut as MidiOutTransport
import java.io.IOException

class MainActivity : AppCompatActivity() {

    // --- State Holders ---
    private val touchState = MutableTouchPolar()
    private val radiusFilter = OneEuroFilter()

    // Initialized in onCreate to allow density-aware scaling
    private lateinit var harmonicEngine: HarmonicEngine

    // Cached, density-scaled radii (px)
    private var r1Px: Float = 0f
    private var r2Px: Float = 0f
    private val startTime = System.nanoTime()

    private var voiceLeader: VoiceLeader? = null
    private lateinit var overlay: HarmonicOverlayView

    // --- Frame Latch (Choreographer) ---
    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }
    private val pendingState = HarmonicState()
    private var pendingDirty = false
    private var pendingRelease = false
    private var pendingReleaseReason: String = ""
    private var frameArmed = false

    // --- Input & Hardware Abstraction ---
    // We store the active pointer IDs to pass to VoiceLeader
    private val activePointers = IntArray(MusicalConstants.MAX_VOICES) { -1 }
    private val touchDriver = AndroidTouchDriver(maxSlots = MusicalConstants.MAX_VOICES)
    private val touchFrame: TouchFrame
        get() = touchDriver.frame

    // --- Gesture Intelligence ---
    // P1 Fix: Reusable container to avoid allocation in the hot path
    private val gestureContainer = TimbreNavigator.MutableGesture()

    // Translationally invariant Δx/Δy gesture space
    private val timbreNav = TimbreNavigator(
        maxPointerId = MusicalConstants.MAX_POINTER_ID,
        deadzonePx = 6f,
        rangeXPx = 220f,
        rangeYPx = 220f
    )

    // Stash Y + distance for next step (CC74 / macro), indexed by slot 0..4
    // Using MAX_VOICES to ensure array bounds match
    private val lastDyNormBySlot = FloatArray(MusicalConstants.MAX_VOICES) { 0f }
    private val lastDistNormBySlot = FloatArray(MusicalConstants.MAX_VOICES) { 0f }

    // --- Rendering Optimization ---
    // P1 Fix: State tracking to throttle invalidate() calls
    private var lastDrawnRoot = -1
    private var lastDrawnQuality = -1
    private var lastDrawnDensity = -1

    // --- Choreographer Callback ---
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

        // P1 Fix: Normalize Radii by Display Density
        // This ensures the instrument feels the same on a phone vs. tablet
        val density = resources.displayMetrics.density
        r1Px = MusicalConstants.BASE_RADIUS_INNER * density
        r2Px = MusicalConstants.BASE_RADIUS_OUTER * density

        harmonicEngine = HarmonicEngine(
            sectorCount = MusicalConstants.SECTOR_COUNT,
            r1 = r1Px,
            r2 = r2Px
        )

        overlay = HarmonicOverlayView(this, harmonicEngine)
        setContentView(overlay)
        setupMidi()
    }

    /**
     * Latch note-on velocity instantly on pointer-down (F_DOWN), using
     * the attack-bypassed force sample from SmartInputHAL.
     */
    private fun latchAttackVelocitiesFromFrame() {
        for (s in 0 until MusicalConstants.MAX_VOICES) {
            val flags = touchFrame.flags[s]
            if ((flags and TouchFrame.F_DOWN) == 0) continue

            val pid = touchFrame.pointerIds[s]
            if (pid != TouchFrame.INVALID_ID) {
                var v = (1 + (touchFrame.force01[s] * 126f)).toInt().coerceIn(1, 127)
                if ((flags and TouchFrame.F_WACK) != 0) {
                    v = (v + 20).coerceIn(1, 127)
                }
                voiceLeader?.setSlotVelocity(s, pid, v)
            }

            // Consume DOWN so we don't re-latch every MOVE/frame if flags persist.
            touchFrame.flags[s] = flags and TouchFrame.F_DOWN.inv()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        try {
            TouchLogger.log(event, overlay.width, overlay.height)
            touchDriver.ingest(event)
            latchAttackVelocitiesFromFrame()

            // Gesture origin capture purely from frame flags (KMP-friendly)
            for (s in 0 until MusicalConstants.MAX_VOICES) {
                val pid = touchFrame.pointerIds[s]
                if (pid == TouchFrame.INVALID_ID) continue
                val f = touchFrame.flags[s]
                if ((f and TouchFrame.F_DOWN) != 0) {
                    timbreNav.onPointerDown(pid, touchFrame.x[s], touchFrame.y[s])
                }
                if ((f and TouchFrame.F_UP) != 0) {
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

                    invalidateIfVisualChanged()
                    return true
                }
            }

            // Physics Update
            val cx = overlay.width / 2f
            val cy = overlay.height / 2f
            TouchMath.update(touchFrame, cx, cy, touchState)

            if (touchState.isActive) {
                val tSec = (System.nanoTime() - startTime) / 1_000_000_000f
                val rSmooth = radiusFilter.filter(touchState.radius, tSec)

                // Expansion compensation with Density Scaling
                val expansion01 = ((rSmooth - r1Px) / (r2Px - r1Px)).coerceIn(0f, 1f)
                touchDriver.expansion01 = expansion01

                // 1. Collect Active Pointer IDs for VoiceLeader
                var activeCount = 0
                for (s in 0 until MusicalConstants.MAX_VOICES) {
                    val pid = touchFrame.pointerIds[s]
                    activePointers[s] = pid
                    if (pid != TouchFrame.INVALID_ID) activeCount++
                    // Reset stored gesture values when slot is inactive
                    if (pid == TouchFrame.INVALID_ID) {
                        lastDyNormBySlot[s] = 0f
                        lastDistNormBySlot[s] = 0f
                    }
                }

                // 2. Continuous Expression (Aftertouch + Gestures)
                for (s in 0 until MusicalConstants.MAX_VOICES) {
                    val pid = touchFrame.pointerIds[s]
                    if (pid == TouchFrame.INVALID_ID) continue

                    val force = touchFrame.force01[s]
                    val at = (force * 127f).toInt()

                    // Route to specific Slot/Pointer
                    voiceLeader?.setSlotAftertouch(s, pid, at)

                    // TimbreNavigator gesture (Δx/Δy from per-pointer origin)
                    val pIndex = event.findPointerIndex(pid)
                    if (pIndex >= 0) {
                        val x = event.getX(pIndex)
                        val y = event.getY(pIndex)

                        // P1 Fix: Zero-allocation compute
                        if (timbreNav.compute(pid, x, y, gestureContainer)) {
                            // Keep Y + distance for next step (if needed)
                            lastDyNormBySlot[s] = gestureContainer.dyNorm
                            lastDistNormBySlot[s] = gestureContainer.distNorm

                            // Pitch Bend from X gesture (dxNorm)
                            val bend14 = (MusicalConstants.CENTER_PITCH_BEND +
                                    (gestureContainer.dxNorm * 8191f)).toInt().coerceIn(0, 16383)
                            voiceLeader?.setSlotPitchBend(s, pid, bend14)

                            // CC74 from Y gesture (dyNorm). Invert so "drag up" increases timbre.
                            val cc74 = (MusicalConstants.CENTER_CC74 +
                                    (-gestureContainer.dyNorm * 63f)).toInt().coerceIn(0, 127)
                            voiceLeader?.setSlotCC74(s, pid, cc74)
                        }
                    }
                }

                // 3. Harmonic Logic
                val changed = harmonicEngine.update(
                    touchState.angle,
                    rSmooth,
                    touchState.pointerCount,
                    touchFrame.tMs
                )

                if (changed) {
                    pendingState.setFrom(harmonicEngine.state)
                    pendingDirty = true
                    armFrameCallback()
                } else {
                    // Even if harmony didn't change, we might want to flush AT
                    armFrameCallback()
                }
            }

            // P1 Fix: Throttled Invalidation
            // Only invalidate if logic state changed OR strictly necessary for animation
            invalidateIfVisualChanged()

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return true
    }

    private fun HarmonicState.setFrom(src: HarmonicState) {
        root = src.root
        quality = src.quality
        density = src.density
    }

    private fun invalidateIfVisualChanged() {
        val s = harmonicEngine.state
        val changed =
            (s.root != lastDrawnRoot) ||
            (s.quality != lastDrawnQuality) ||
            (s.density != lastDrawnDensity)

        if (changed) {
            overlay.invalidate()
            lastDrawnRoot = s.root
            lastDrawnQuality = s.quality
            lastDrawnDensity = s.density
        }
    }

    private fun setupMidi() {
        try {
            val midiManager = getSystemService(MIDI_SERVICE) as? MidiManager ?: return
            @Suppress("DEPRECATION")
            val devices = midiManager.devices

            val usbDevice = devices.firstOrNull { it.inputPortCount > 0 }
            if (usbDevice != null) {
                midiManager.openDevice(usbDevice, { device ->
                    if (device != null) {
                        val port = device.openInputPort(0)
                        if (port != null) {
                            voiceLeader = VoiceLeader(MidiOutTransport(port))
                            runOnUiThread { Toast.makeText(this, "Connected", Toast.LENGTH_SHORT).show() }
                        } else {
                            // P0 Fix: Close device if port fails to avoid resource leak
                            Log.w("MIDI", "Failed to open input port")
                            try { device.close() } catch (e: IOException) { e.printStackTrace() }
                        }
                    }
                }, null)
            }
        } catch (e: Exception) {
            // P0 Fix: Log explicit errors
            Log.e("MIDI", "Setup failed", e)
        }
    }

    // -------------------------------------------------------------------
    // LIFECYCLE & HELPERS
    // -------------------------------------------------------------------

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