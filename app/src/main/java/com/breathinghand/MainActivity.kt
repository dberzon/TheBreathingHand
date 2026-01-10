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
import com.breathinghand.core.midi.AndroidForensicLogger
import com.breathinghand.core.midi.AndroidMonotonicClock
import com.breathinghand.core.midi.AndroidMidiSink
import com.breathinghand.core.midi.MidiOut
import java.io.IOException

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG_SEM = "BH_SEM"
        private const val TAG_COMMIT = "BH_COMMIT"
        // Fix: Local debug flag to bypass unresolved BuildConfig
        private const val IS_DEBUG = true
    }

    private val touchState = MutableTouchPolar()
    private val radiusFilter = OneEuroFilter()

    private lateinit var harmonicEngine: HarmonicEngine
    private lateinit var gestureAnalyzer: GestureAnalyzerV01
    private lateinit var timbreNav: TimbreNavigator

    private var r1Px: Float = 0f
    private var r2Px: Float = 0f
    private val startTime = System.nanoTime()

    private var voiceLeader: VoiceLeader? = null
    private lateinit var overlay: HarmonicOverlayView

    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }
    private val pendingState = HarmonicState()
    private var pendingDirty = false
    private var pendingRelease = false
    private var pendingReleaseReason: String = ""
    private var frameArmed = false

    private val activePointers = IntArray(MusicalConstants.MAX_VOICES) { -1 }
    private val touchDriver = AndroidTouchDriver(maxSlots = MusicalConstants.MAX_VOICES)
    private val touchFrame: TouchFrame
        get() = touchDriver.frame

    private val gestureContainer = TimbreNavigator.MutableGesture()
    private var lastPointerCount = 0

    // Fix: Zero-allocation initialization (removed redundant lambda)
    private val lastDyNormBySlot = FloatArray(MusicalConstants.MAX_VOICES)
    private val lastDistNormBySlot = FloatArray(MusicalConstants.MAX_VOICES)

    private var lastDrawnRoot = -1
    private var lastDrawnPreviewRoot = -1
    private var lastDrawnQuality = -1
    private var lastDrawnDensity = -1

    private val frameCallback = Choreographer.FrameCallback {
        frameArmed = false

        // Capture state BEFORE clearing pendingDirty
        val stateToUse = if (pendingDirty) pendingState else harmonicEngine.state

        if (pendingDirty) {
            MidiLogger.logCommit(pendingState)
            pendingDirty = false
        }

        // Update VoiceLeader with correct state (flushContinuous is called inside update)
        voiceLeader?.update(stateToUse, activePointers)

        if (pendingRelease) {
            MidiLogger.logAllNotesOff("FRAME_LATCH_RELEASE:$pendingReleaseReason")
            voiceLeader?.allNotesOff()
            pendingRelease = false
            pendingReleaseReason = ""
            TouchMath.reset()
            radiusFilter.reset()
            lastPointerCount = 0
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Fix: Use local constant
        MidiOut.FORENSIC_TX_LOG = IS_DEBUG

        val density = resources.displayMetrics.density
        r1Px = MusicalConstants.BASE_RADIUS_INNER * density
        r2Px = MusicalConstants.BASE_RADIUS_OUTER * density

        gestureAnalyzer = GestureAnalyzerV01(r1Px, r2Px)

        // Note: Ensure HarmonicEngine.kt is updated to accept 'hysteresis'
        harmonicEngine = HarmonicEngine(
            sectorCount = MusicalConstants.SECTOR_COUNT,
            r1 = r1Px,
            r2 = r2Px,
            hysteresis = InputTuning.RADIUS_HYSTERESIS_PX * density
        )

        timbreNav = TimbreNavigator(
            maxPointerId = MusicalConstants.MAX_POINTER_ID,
            deadzonePx = 6f * density,
            rangeXPx = 220f * density,
            rangeYPx = 220f * density
        )

        overlay = HarmonicOverlayView(this, harmonicEngine)
        setContentView(overlay)
        setupMidi()
    }

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
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        try {
            TouchLogger.log(event, overlay.width, overlay.height)
            touchDriver.ingest(event)

            val cx = overlay.width / 2f
            val cy = overlay.height / 2f
            TouchMath.update(touchFrame, cx, cy, touchState)

            val fCount = touchState.pointerCount.coerceIn(0, 4)

            // Semantic Event Detection
            val semanticEvent = when {
                lastPointerCount == 0 && fCount > 0 -> GestureAnalyzerV01.EVENT_LANDING
                fCount > lastPointerCount -> GestureAnalyzerV01.EVENT_ADD_FINGER
                else -> GestureAnalyzerV01.EVENT_NONE
            }

            latchAttackVelocitiesFromFrame()

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

            if (pendingRelease && isTouchActivity(event)) {
                pendingRelease = false
                pendingReleaseReason = ""
                if (!pendingDirty) cancelFrameCallbackIfIdle()
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // Clear active pointers to prevent stale state in frame callback
                    for (s in 0 until MusicalConstants.MAX_VOICES) {
                        activePointers[s] = -1
                        lastDyNormBySlot[s] = 0f
                        lastDistNormBySlot[s] = 0f
                    }
                    pendingDirty = false
                    pendingRelease = true
                    pendingReleaseReason =
                        if (event.actionMasked == MotionEvent.ACTION_UP) "ACTION_UP" else "ACTION_CANCEL"
                    armFrameCallback()

                    invalidateIfVisualChanged()
                    return true
                }
            }

            if (touchState.isActive) {
                val tSec = (System.nanoTime() - startTime) / 1_000_000_000f
                val spreadSmooth = radiusFilter.filter(touchState.radius, tSec)

                val expansion01 = ((spreadSmooth - r1Px) / (r2Px - r1Px)).coerceIn(0f, 1f)
                touchDriver.expansion01 = expansion01

                for (s in 0 until MusicalConstants.MAX_VOICES) {
                    val pid = touchFrame.pointerIds[s]
                    activePointers[s] = pid
                    if (pid == TouchFrame.INVALID_ID) {
                        lastDyNormBySlot[s] = 0f
                        lastDistNormBySlot[s] = 0f
                    }
                }

                for (s in 0 until MusicalConstants.MAX_VOICES) {
                    val pid = touchFrame.pointerIds[s]
                    if (pid == TouchFrame.INVALID_ID) continue

                    val force = touchFrame.force01[s]
                    val at = (force * 127f).toInt()
                    voiceLeader?.setSlotAftertouch(s, pid, at)

                    val pIndex = event.findPointerIndex(pid)
                    if (pIndex >= 0) {
                        val x = event.getX(pIndex)
                        val y = event.getY(pIndex)

                        if (timbreNav.compute(pid, x, y, gestureContainer)) {
                            lastDyNormBySlot[s] = gestureContainer.dyNorm
                            lastDistNormBySlot[s] = gestureContainer.distNorm

                            val bend14 = (MusicalConstants.CENTER_PITCH_BEND +
                                    (gestureContainer.dxNorm * 8191f)).toInt().coerceIn(0, 16383)
                            voiceLeader?.setSlotPitchBend(s, pid, bend14)

                            val cc74 = (MusicalConstants.CENTER_CC74 +
                                    (-gestureContainer.dyNorm * 63f)).toInt().coerceIn(0, 127)
                            voiceLeader?.setSlotCC74(s, pid, cc74)
                        }
                    }
                }

                gestureAnalyzer.onSemanticEvent(touchFrame, fCount, spreadSmooth, semanticEvent)

                if (semanticEvent != GestureAnalyzerV01.EVENT_NONE) {
                    val evtName = if (semanticEvent == GestureAnalyzerV01.EVENT_LANDING) "LAND" else "ADD"
                    if (IS_DEBUG) {
                        Log.d(
                            TAG_SEM,
                            "${touchFrame.tMs},$evtName,f=$fCount,triad=${gestureAnalyzer.latchedTriad},sev=${gestureAnalyzer.latchedSeventh}"
                        )
                    }
                }

                val changed = harmonicEngine.update(
                    touchState.angle,
                    spreadSmooth,
                    fCount,
                    gestureAnalyzer.latchedTriad,
                    gestureAnalyzer.latchedSeventh,
                    touchFrame.tMs
                )

                if (harmonicEngine.didCommitRootThisUpdate && IS_DEBUG) {
                    Log.d(
                        TAG_COMMIT,
                        "${touchFrame.tMs},COMMIT,root=${harmonicEngine.state.root},preview=${harmonicEngine.state.previewRoot}"
                    )
                }

                if (changed) {
                    pendingState.setFrom(harmonicEngine.state)
                    pendingDirty = true
                    armFrameCallback()
                } else {
                    armFrameCallback()
                }

                lastPointerCount = fCount
            } else {
                lastPointerCount = 0
            }

            invalidateIfVisualChanged()

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return true
    }

    private fun HarmonicState.setFrom(src: HarmonicState) {
        root = src.root
        previewRoot = src.previewRoot
        quality = src.quality
        density = src.density
        triad = src.triad
        seventh = src.seventh
    }

    private fun invalidateIfVisualChanged() {
        val s = harmonicEngine.state
        val changed =
            (s.root != lastDrawnRoot) ||
                    (s.previewRoot != lastDrawnPreviewRoot) ||
                    (s.quality != lastDrawnQuality) ||
                    (s.density != lastDrawnDensity)

        if (changed) {
            overlay.invalidate()
            lastDrawnRoot = s.root
            lastDrawnPreviewRoot = s.previewRoot
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
                            val sink = AndroidMidiSink(port)
                            val midi = MidiOut(sink, AndroidMonotonicClock, AndroidForensicLogger)
                            voiceLeader = VoiceLeader(midi)
                            runOnUiThread { Toast.makeText(this, "Connected", Toast.LENGTH_SHORT).show() }
                        } else {
                            Log.w("MIDI", "Failed to open input port")
                            try {
                                device.close()
                            } catch (e: IOException) {
                                e.printStackTrace()
                            }
                        }
                    }
                }, null)
            }
        } catch (e: Exception) {
            Log.e("MIDI", "Setup failed", e)
        }
    }

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
        timbreNav.resetAll()
        lastPointerCount = 0
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelFrameCallbackHard()
        voiceLeader?.close()
    }
}