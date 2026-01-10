package com.breathinghand

import android.media.midi.MidiManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.breathinghand.audio.OboeSynthesizer
import com.breathinghand.core.*
import com.breathinghand.core.midi.AndroidForensicLogger
import com.breathinghand.core.midi.AndroidMonotonicClock
import com.breathinghand.core.midi.AndroidMidiSink
import com.breathinghand.core.midi.FanOutMidiSink
import com.breathinghand.core.midi.MidiOut
import com.breathinghand.core.midi.OboeMidiSink
import java.io.IOException

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG_SEM = "BH_SEM"
    }

    private val touchState = MutableTouchPolar()
    private val radiusFilter = OneEuroFilter()

    private lateinit var harmonicEngine: HarmonicEngine
    private lateinit var gestureAnalyzer: GestureAnalyzerV01
    private lateinit var timbreNav: TimbreNavigator
    private val transitionWindow = TransitionWindow()

    private var r1Px: Float = 0f
    private var r2Px: Float = 0f
    private val startTime = System.nanoTime()

    @Volatile
    private var voiceLeader: VoiceLeader? = null

    // We keep a reference to the raw MIDI transport so we can swap modes without reconnecting
    private var activeMidiOut: MidiOut? = null
    private lateinit var internalSynth: OboeSynthesizer
    private lateinit var midiFanOut: FanOutMidiSink
    private var externalMidiSink: AndroidMidiSink? = null

    private lateinit var overlay: HarmonicOverlayView
    private lateinit var mpeSwitch: Switch // The new Tick Box

    private val activePointers = IntArray(MusicalConstants.MAX_VOICES) { -1 }
    private val touchDriver = AndroidTouchDriver(maxSlots = MusicalConstants.MAX_VOICES)
    private val touchFrame: TouchFrame
        get() = touchDriver.frame

    private val gestureContainer = TimbreNavigator.MutableGesture()
    private var lastPointerCount = 0

    // Last non-zero centroid (used to arm Transition Window on lift-to-zero)
    private var lastActiveCenterX = 0f
    private var lastActiveCenterY = 0f

    // Visual change detection
    private var lastDrawnSector = -1
    private var lastDrawnPc = -1
    private var lastDrawnFc = -1
    private var lastDrawnUnstable = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        MidiOut.FORENSIC_TX_LOG = MusicalConstants.IS_DEBUG

        val density = resources.displayMetrics.density
        r1Px = MusicalConstants.BASE_RADIUS_INNER * density
        r2Px = MusicalConstants.BASE_RADIUS_OUTER * density

        gestureAnalyzer = GestureAnalyzerV01(r1Px, r2Px)
        harmonicEngine = HarmonicEngine()
        timbreNav = TimbreNavigator(
            maxPointerId = MusicalConstants.MAX_POINTER_ID,
            deadzonePx = 6f * density,
            rangeXPx = 220f * density,
            rangeYPx = 220f * density
        )

        // --- NEW UI SETUP ---
        setupUI()

        internalSynth = OboeSynthesizer()
        midiFanOut = FanOutMidiSink(OboeMidiSink(internalSynth))
        activeMidiOut = MidiOut(midiFanOut, AndroidMonotonicClock, AndroidForensicLogger)
        updateMidiMode(mpeSwitch.isChecked)

        setupMidi()
    }

    /**
     * Programmatically creates a FrameLayout with the Instrument on bottom
     * and the Configuration Switch on top.
     */
    private fun setupUI() {
        // 1. The Container
        val container = FrameLayout(this)
        container.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        // 2. The Instrument View (Bottom Layer)
        overlay = HarmonicOverlayView(this, harmonicEngine)
        overlay.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        container.addView(overlay)

        // 3. The MPE Switch (Top Layer)
        mpeSwitch = Switch(this)
        mpeSwitch.text = "MPE Mode   "
        mpeSwitch.textSize = 14f
        mpeSwitch.setTextColor(-1) // White text
        mpeSwitch.isChecked = true // Default to MPE (enable internal synth polyphony)

        // Position: Top Right
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.setMargins(0, 48, 48, 0) // Margin for status bar
        mpeSwitch.layoutParams = params

        // 4. Handle Mode Switching
        mpeSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateMidiMode(isChecked)
            val modeName = if (isChecked) "MPE (Multi-Ch)" else "Standard (Ch 1)"
            Toast.makeText(this, "Mode: $modeName", Toast.LENGTH_SHORT).show()
        }

        container.addView(mpeSwitch)

        // Set the container as the activity content
        setContentView(container)
    }

    /**
     * Swaps the VoiceLeader strategy without breaking the USB connection.
     */
    private fun updateMidiMode(useMpe: Boolean) {
        val midi = activeMidiOut ?: return // Can't update if not connected

        // 1. Silence current notes
        voiceLeader?.allNotesOff()

        // 2. Choose Strategy
        val output: MidiOutput = if (useMpe) {
            MpeMidiOutput(midi)
        } else {
            StandardMidiOutput(midi)
        }

        // 3. Replace VoiceLeader
        voiceLeader = VoiceLeader(output)
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
        // Pass touch events to the Overlay manually if the Switch doesn't consume them?
        // Actually, returning false here allows standard dispatch, but since we override onTouchEvent
        // at the Activity level, we intercept everything not caught by views.
        // The Switch will handle its own touches. We process the rest for music.

        try {
            TouchLogger.log(event, overlay.width, overlay.height)
            touchDriver.ingest(event)

            val cx = overlay.width / 2f
            val cy = overlay.height / 2f
            TouchMath.update(touchFrame, cx, cy, touchState)

            val fCount = touchState.pointerCount.coerceIn(0, 4)

            if (fCount > 0) {
                lastActiveCenterX = touchState.centerX
                lastActiveCenterY = touchState.centerY
            }

            val landing = (lastPointerCount == 0 && fCount > 0)
            val addFinger = (fCount > lastPointerCount)
            val liftToZero = (fCount == 0 && lastPointerCount > 0)

            var semanticEvent = when {
                landing -> GestureAnalyzerV01.EVENT_LANDING
                addFinger -> GestureAnalyzerV01.EVENT_ADD_FINGER
                else -> GestureAnalyzerV01.EVENT_NONE
            }

            var transitionHit = false
            if (landing) {
                transitionHit = transitionWindow.consumeIfHit(
                    touchFrame.tMs,
                    touchState.centerX,
                    touchState.centerY,
                    fCount
                )
                if (transitionHit) {
                    harmonicEngine.beginFromRestoredState(
                        touchFrame.tMs,
                        transitionWindow.storedState,
                        touchState.angle
                    )
                    gestureAnalyzer.seedFromState(transitionWindow.storedState)
                    semanticEvent = GestureAnalyzerV01.EVENT_NONE
                } else {
                    transitionWindow.disarm()
                }
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

            when (event.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (lastPointerCount > 0) {
                        transitionWindow.arm(
                            touchFrame.tMs,
                            lastActiveCenterX,
                            lastActiveCenterY,
                            harmonicEngine.state,
                            lastPointerCount
                        )
                    }

                    for (s in 0 until MusicalConstants.MAX_VOICES) {
                        activePointers[s] = -1
                    }

                    MidiLogger.logAllNotesOff(
                        if (event.actionMasked == MotionEvent.ACTION_UP) "ACTION_UP" else "ACTION_CANCEL"
                    )
                    voiceLeader?.allNotesOff()

                    harmonicEngine.onAllFingersLift(touchFrame.tMs)
                    TouchMath.reset()
                    radiusFilter.reset()
                    lastPointerCount = 0

                    invalidateIfVisualChanged()
                    return true
                }
            }

            if (liftToZero) {
                transitionWindow.arm(
                    touchFrame.tMs,
                    lastActiveCenterX,
                    lastActiveCenterY,
                    harmonicEngine.state,
                    lastPointerCount
                )
                radiusFilter.reset()
            }

            if (touchState.isActive) {
                val tSec = (System.nanoTime() - startTime) / 1_000_000_000f
                val spreadSmooth = radiusFilter.filter(touchState.radius, tSec)

                val expansion01 = ((spreadSmooth - r1Px) / (r2Px - r1Px)).coerceIn(0f, 1f)
                touchDriver.expansion01 = expansion01

                for (s in 0 until MusicalConstants.MAX_VOICES) {
                    val pid = touchFrame.pointerIds[s]
                    activePointers[s] = pid
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
                            val bend14 = (MusicalConstants.CENTER_PITCH_BEND +
                                    (gestureContainer.dxNorm * 8191f)).toInt().coerceIn(0, 16383)
                            voiceLeader?.setSlotPitchBend(s, pid, bend14)

                            val cc74 = (MusicalConstants.CENTER_CC74 +
                                    (-gestureContainer.dyNorm * 63f)).toInt().coerceIn(0, 127)
                            voiceLeader?.setSlotCC74(s, pid, cc74)
                        }
                    }
                }

                if (!transitionHit) {
                    gestureAnalyzer.onSemanticEvent(touchFrame, fCount, spreadSmooth, semanticEvent)
                }

                if (semanticEvent != GestureAnalyzerV01.EVENT_NONE && MusicalConstants.IS_DEBUG) {
                    val evtName = if (semanticEvent == GestureAnalyzerV01.EVENT_LANDING) "LAND" else "ADD"
                    Log.d(
                        TAG_SEM,
                        "${touchFrame.tMs},$evtName,f=$fCount,triad=${gestureAnalyzer.latchedTriad},sev=${gestureAnalyzer.latchedSeventh}"
                    )
                }

                val centerYNorm =
                    if (overlay.height > 0) (touchState.centerY / overlay.height.toFloat()) else 0.5f

                val changed = if (!transitionHit) {
                    harmonicEngine.update(
                        touchFrame.tMs,
                        touchState.angle,
                        spreadSmooth,
                        centerYNorm,
                        fCount,
                        gestureAnalyzer.latchedTriad,
                        gestureAnalyzer.latchedSeventh
                    )
                } else {
                    false
                }

                if (changed && MusicalConstants.IS_DEBUG) {
                    MidiLogger.logHarmony(harmonicEngine.state)
                }

                voiceLeader?.update(harmonicEngine.state, activePointers)
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

    private fun invalidateIfVisualChanged() {
        val s = harmonicEngine.state
        val unstable = s.harmonicInstability > MusicalConstants.INSTABILITY_THRESHOLD

        val changed =
            (s.functionSector != lastDrawnSector) ||
                    (s.rootPc != lastDrawnPc) ||
                    (s.fingerCount != lastDrawnFc) ||
                    (unstable != lastDrawnUnstable)

        if (changed) {
            overlay.invalidate()
            lastDrawnSector = s.functionSector
            lastDrawnPc = s.rootPc
            lastDrawnFc = s.fingerCount
            lastDrawnUnstable = unstable
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
                            externalMidiSink?.close()
                            externalMidiSink = AndroidMidiSink(port)
                            midiFanOut.setSecondary(externalMidiSink)

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

    override fun onPause() {
        super.onPause()
        internalSynth.stop()
        MidiLogger.logAllNotesOff("onPause")
        voiceLeader?.allNotesOff()
        harmonicEngine.onAllFingersLift(SystemClock.uptimeMillis())
        TouchMath.reset()
        radiusFilter.reset()
        timbreNav.resetAll()
        lastPointerCount = 0
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceLeader?.close()
    }

    override fun onResume() {
        super.onResume()
        internalSynth.start()
    }
}