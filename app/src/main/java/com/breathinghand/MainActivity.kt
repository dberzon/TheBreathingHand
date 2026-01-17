package com.breathinghand.android

import android.media.midi.MidiManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.Choreographer
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.breathinghand.audio.OboeSynthesizer
import com.breathinghand.R
import com.breathinghand.core.*
import com.breathinghand.core.midi.AndroidForensicLogger
import com.breathinghand.engine.GestureAnalyzer
import com.breathinghand.core.midi.AndroidMonotonicClock
import com.breathinghand.core.midi.AndroidMidiSink
import com.breathinghand.core.midi.FanOutMidiSink
import com.breathinghand.core.midi.MonoChannelMidiSink
import com.breathinghand.core.midi.MidiOut
import com.breathinghand.core.midi.OboeMidiSink
import com.breathinghand.core.midi.VoiceLeader
import com.breathinghand.core.midi.VoiceAllocator
import com.breathinghand.core.midi.TouchSource
import com.breathinghand.core.midi.AndroidMidiOutput
import com.breathinghand.core.midi.MusicalConstants
import java.io.IOException
import com.breathinghand.MidiLogger
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG_SEM = "BH_SEM"
        private const val COALESCE_WINDOW_MS = 10L
    }

    private val touchState = MutableTouchPolar()
    private val radiusFilter = OneEuroFilter()

    private lateinit var harmonicEngine: HarmonicEngine
    private lateinit var gestureAnalyzer: GestureAnalyzer
    private lateinit var timbreNav: TimbreNavigator
    private val transitionWindow = TransitionWindow()

    private var r1Px: Float = 0f
    private var r2Px: Float = 0f
    private val startTime = System.nanoTime()

    // Release coalescing (INPUT layer)
    private val choreographer: Choreographer = Choreographer.getInstance()
    private var releaseCoalesceActive: Boolean = false
    private var releaseCoalesceDeadlineMs: Long = 0L
    private var releaseCoalescePosted: Boolean = false
    private val releaseCoalesceCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!releaseCoalesceActive) {
                releaseCoalescePosted = false
                return
            }
            val nowMs = SystemClock.uptimeMillis()
            if (nowMs < releaseCoalesceDeadlineMs) {
                choreographer.postFrameCallback(this)
                return
            }
            releaseCoalesceActive = false
            releaseCoalescePosted = false
            processTouchSnapshot()
        }
    }

    // V0.2 ARCHITECTURE: VoiceLeader is non-nullable and persistent
    private val voiceLeader = VoiceLeader()

    // We keep a reference to the raw MIDI transport so we can swap routing without reconnecting
    private var activeMidiOut: MidiOut? = null
    private lateinit var internalSynth: OboeSynthesizer
    private lateinit var midiFanOut: FanOutMidiSink
    private var externalMidiSink: AndroidMidiSink? = null
    private var cc11BaselineSent: Boolean = false

    private lateinit var overlay: HarmonicOverlayView
    private lateinit var externalRoutingSwitch: Switch
    private lateinit var calibrationView: CalibrationView

    // SF2 file picker
    private val pickSf2Launcher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { onSf2Picked(it) }
    }

    private val activePointers = IntArray(MusicalConstants.MAX_VOICES) { -1 }
    private val touchDriver = AndroidTouchDriver(maxSlots = MusicalConstants.MAX_VOICES)
    private val touchFrame: TouchFrame
        get() = touchDriver.frame

    // V0.2 ARCHITECTURE: Physical Truth & Semantic Source
    private val voiceAllocator = VoiceAllocator()
    private lateinit var midiOutput: AndroidMidiOutput

    private val gestureContainer = TimbreNavigator.MutableGesture()
    private var lastPointerCount = 0

    // Pre-allocated arrays for gesture analyzer (zero-alloc hot path)
    private val activeX = FloatArray(MusicalConstants.MAX_VOICES)
    private val activeY = FloatArray(MusicalConstants.MAX_VOICES)

    // Release-cascade suppression (rhythmic-only)
    private var releaseCascadeUntilMs: Long = 0L

    // Landing/add-finger cascade suppression (rhythmic-only)
    private var landingCascadeUntilMs: Long = 0L

    // Last non-zero centroid (used to arm Transition Window on lift-to-zero)
    private var lastActiveCenterX = 0f
    private var lastActiveCenterY = 0f

    // Last spread value (preserved for END marker snapshots)
    private var lastSpreadSmooth: Float = 0f

    // Visual change detection
    private var lastDrawnSector = -1
    private var lastDrawnPc = -1
    private var lastDrawnFc = -1
    private var lastDrawnUnstable = false

    private val importScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        MidiOut.FORENSIC_TX_LOG = false // Disable in production

        val density = resources.displayMetrics.density
        r1Px = MusicalConstants.BASE_RADIUS_INNER * density
        r2Px = MusicalConstants.BASE_RADIUS_OUTER * density

        gestureAnalyzer = GestureAnalyzer()
        harmonicEngine = HarmonicEngine()
        timbreNav = TimbreNavigator(
            maxPointerId = MusicalConstants.MAX_POINTER_ID,
            deadzonePx = 6f * density,
            rangeXPx = 220f * density,
            rangeYPx = 220f * density
        )

        internalSynth = OboeSynthesizer()

        importScope.launch {
            val ok = internalSynth.initFluidSynthAndLoadBundledDefaultSf2(this@MainActivity)
            runOnUiThread {
                if (ok) {
                    Toast.makeText(this@MainActivity, "Loaded bundled SoundFont (default.sf2)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "Bundled SoundFont load failed (check assets/sf2/default.sf2 and FluidSynth build)",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        midiFanOut = FanOutMidiSink(MonoChannelMidiSink(OboeMidiSink(internalSynth)))
        activeMidiOut = MidiOut(midiFanOut, AndroidMonotonicClock, AndroidForensicLogger)

        // V0.2 ARCHITECTURE: Initialize MIDI Adapter
        midiOutput = AndroidMidiOutput(midiFanOut)

        if (intent?.getBooleanExtra("selfTest", false) == true) {
            runSelfTest()
        }

        setupUI()
        setupMidi()
    }

    private fun setupUI() {
        val container = FrameLayout(this)
        container.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        overlay = HarmonicOverlayView(this, harmonicEngine)
        overlay.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        container.addView(overlay)

        externalRoutingSwitch = Switch(this)
        externalRoutingSwitch.textSize = 14f
        externalRoutingSwitch.setTextColor(-1)
        externalRoutingSwitch.isChecked = false
        externalRoutingSwitch.text = if (externalRoutingSwitch.isChecked) getString(R.string.external_midi_label_mpe) else getString(R.string.external_midi_label_gm)

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.setMargins(0, 48, 48, 0)
        externalRoutingSwitch.layoutParams = params

        externalRoutingSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateMidiRouting(isChecked)
            val routingName = if (isChecked) getString(R.string.external_midi_name_mpe) else getString(R.string.external_midi_name_gm)
            externalRoutingSwitch.text = "External MIDI: $routingName"
            Toast.makeText(this, "External MIDI: $routingName", Toast.LENGTH_SHORT).show()
        }

        container.addView(externalRoutingSwitch)
        updateMidiRouting(externalRoutingSwitch.isChecked)

        val fluidsynthSwitch = Switch(this)
        fluidsynthSwitch.textSize = 14f
        fluidsynthSwitch.setTextColor(-1)
        fluidsynthSwitch.isChecked = false
        fluidsynthSwitch.text = "FluidSynth: Off"

        val fsParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        fsParams.gravity = Gravity.TOP or Gravity.END
        fsParams.setMargins(0, 112, 48, 0)
        fluidsynthSwitch.layoutParams = fsParams

        fluidsynthSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                importScope.launch { internalSynth.initFluidSynthAndLoadBundledDefaultSf2(this@MainActivity) }
            } else {
                importScope.launch {
                    val ok = internalSynth.shutdownFluidSynth()
                    runOnUiThread {
                        fluidsynthSwitch.text = if (ok) "FluidSynth: Off" else "FluidSynth: Off"
                        if (ok) Toast.makeText(this@MainActivity, "FluidSynth shutdown", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        fluidsynthSwitch.setOnLongClickListener {
            pickSf2Launcher.launch(arrayOf("*/*"))
            Toast.makeText(this, "Pick a SoundFont (.sf2)", Toast.LENGTH_SHORT).show()
            true
        }

        container.addView(fluidsynthSwitch)

        calibrationView = CalibrationView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.START
                setMargins(16, 0, 0, 16)
            }
            initialize(harmonicEngine)
        }
        container.addView(calibrationView)

        setContentView(container)
    }

    private fun updateMidiRouting(useMultiChannel: Boolean) {
        // V0.2: Routing logic is handled by Sink configuration.
        // VoiceLeader is single-strategy (Channels 2-6).
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
                voiceLeader.setSlotVelocity(s, pid, v)
            }
        }
    }

    private fun cachedSf2File(): java.io.File {
        return java.io.File(cacheDir, "loaded_soundfont.sf2")
    }

    private fun onSf2Picked(uri: android.net.Uri) {
        if (!internalSynth.isFluidSynthCompiled()) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "FluidSynth support is not included in this build.", Toast.LENGTH_LONG).show()
            }
            return
        }

        importScope.launch {
            try {
                val outFile = cachedSf2File()
                contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(outFile).use { out ->
                        input.copyTo(out)
                    }
                }

                val initOk = internalSynth.initFluidSynth()
                if (!initOk) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "Failed to initialize FluidSynth", Toast.LENGTH_LONG).show() }
                    return@launch
                }

                val loadOk = internalSynth.loadSoundFontFromPath(outFile.absolutePath)
                runOnUiThread {
                    if (loadOk) {
                        Toast.makeText(this@MainActivity, "Loaded SoundFont", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Failed to load SoundFont", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { Toast.makeText(this@MainActivity, "SF2 load failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun runSelfTest() {
        importScope.launch {
            try {
                android.util.Log.d("BreathingHand", "runSelfTest: Starting self-test")
                val initOk = internalSynth.initFluidSynth()
                if (!initOk) {
                    android.util.Log.e("BreathingHand", "runSelfTest: initFluidSynth() failed")
                    return@launch
                }
                val loadOk = internalSynth.loadSoundFontFromPath("/sdcard/Download/test.sf2")
                if (!loadOk) return@launch

                midiFanOut.send3(0xE0 or 0, 127, 127)
                kotlinx.coroutines.delay(150)
                midiFanOut.send3(0x90 or 5, 60, 100)
                kotlinx.coroutines.delay(600)
                midiFanOut.send2(0xD0 or 0, 80)
                kotlinx.coroutines.delay(300)
                midiFanOut.send3(0x80 or 5, 60, 0)
                android.util.Log.d("BreathingHand", "runSelfTest: Sequence complete")
            } catch (e: Exception) {
                android.util.Log.e("BreathingHand", "runSelfTest: exception ${'$'}e")
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val nowNs = SystemClock.elapsedRealtimeNanos()
        val actionMasked = event.actionMasked

        // Telemetry
        if (TelemetryRecorder.isRecording()) {
            val pointerCount = event.pointerCount
            val isPointerAction = actionMasked == MotionEvent.ACTION_POINTER_UP ||
                    actionMasked == MotionEvent.ACTION_POINTER_DOWN
            val actionIndex = if (isPointerAction) event.actionIndex else -1

            for (i in 0 until pointerCount) {
                val pointerId = event.getPointerId(i)
                val x = event.getX(i)
                val y = event.getY(i)
                val recordAction = if (isPointerAction && i == actionIndex) {
                    actionMasked
                } else if (isPointerAction) {
                    MotionEvent.ACTION_MOVE
                } else {
                    actionMasked
                }
                TelemetryRecorder.recordTouch(nowNs, recordAction, pointerId, x, y)
            }
        }

        // 1. PHYSICAL ALLOCATION (Authority)
        voiceAllocator.processEvent(event)

        // Full lift / cancel
        if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
            cancelReleaseCoalesce()
            touchDriver.ingest(event)
            handleAllFingersLift(actionMasked)
            return true
        }

        if (releaseCoalesceActive && actionMasked != MotionEvent.ACTION_POINTER_UP) {
            flushReleaseCoalesce(force = true)
        }

        touchDriver.ingest(event)

        // 2. SEMANTIC SOURCING (Coherence)
        TouchSource.update(event, voiceAllocator)

        if (actionMasked == MotionEvent.ACTION_POINTER_UP) {
            for (s in 0 until MusicalConstants.MAX_VOICES) {
                val pid = touchFrame.pointerIds[s]
                if (pid == TouchFrame.INVALID_ID) continue
                if ((touchFrame.flags[s] and TouchFrame.F_UP) != 0) {
                    timbreNav.onPointerUp(pid)
                }
            }

            val activeNow = fillActivePointersFromFrame()

            if (activeNow > 0) {
                val dl = touchFrame.tMs + MusicalConstants.RELEASE_CASCADE_MS
                if (dl > releaseCascadeUntilMs) releaseCascadeUntilMs = dl
            } else {
                releaseCascadeUntilMs = 0L
            }

            val inReleaseCascade = (activeNow > 0 && touchFrame.tMs < releaseCascadeUntilMs)

            // v0.2: Tell VoiceLeader about cascade state to suppress new attacks
            voiceLeader.setReleaseCascadeActive(inReleaseCascade)
            voiceLeader.setLandingCascadeActive(false)

            // EXECUTE VOICE LOGIC (Diff against current state)
            voiceLeader.process(harmonicEngine.state, voiceAllocator, midiOutput)

            releaseCoalesceActive = true
            releaseCoalesceDeadlineMs = touchFrame.tMs + COALESCE_WINDOW_MS
            scheduleReleaseCoalesce()
            return true
        }

        return processTouchSnapshot()
    }

    private fun scheduleReleaseCoalesce() {
        if (releaseCoalescePosted) return
        releaseCoalescePosted = true
        choreographer.postFrameCallback(releaseCoalesceCallback)
    }

    private fun cancelReleaseCoalesce() {
        if (releaseCoalescePosted) {
            choreographer.removeFrameCallback(releaseCoalesceCallback)
        }
        releaseCoalescePosted = false
        releaseCoalesceActive = false
        releaseCoalesceDeadlineMs = 0L
    }

    private fun flushReleaseCoalesce(force: Boolean) {
        if (!releaseCoalesceActive) return
        if (!force && SystemClock.uptimeMillis() < releaseCoalesceDeadlineMs) return
        cancelReleaseCoalesce()
        processTouchSnapshot()
    }

    private fun handleAllFingersLift(actionMasked: Int) {
        if (TelemetryRecorder.isRecording() && lastPointerCount > 0) {
            val state = harmonicEngine.state
            val tNs = SystemClock.elapsedRealtimeNanos()
            TelemetryRecorder.recordSnapshotEnd(
                tNs = tNs,
                fingerCount = 0,
                centroidX = lastActiveCenterX,
                centroidY = lastActiveCenterY,
                spread = lastSpreadSmooth,
                instability = state.harmonicInstability,
                triadArchetype = state.triad,
                seventhArchetype = state.seventh,
                rootPc = state.rootPc,
                sector = state.functionSector
            )
        }

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

        if (MusicalConstants.IS_DEBUG) {
            MidiLogger.logAllNotesOff(
                if (actionMasked == MotionEvent.ACTION_UP) "ACTION_UP" else "ACTION_CANCEL"
            )
        }

        voiceLeader.allNotesOff()
        harmonicEngine.onAllFingersLift(touchFrame.tMs)
        releaseCascadeUntilMs = 0L
        landingCascadeUntilMs = 0L
        TouchMath.reset()
        radiusFilter.reset()
        timbreNav.resetAll()
        lastPointerCount = 0

        invalidateIfVisualChanged()
    }

    private fun fillActivePointersFromFrame(): Int {
        var n = 0
        for (s in 0 until MusicalConstants.MAX_VOICES) {
            val pid = touchFrame.pointerIds[s]
            if (pid == TouchFrame.INVALID_ID) {
                activePointers[s] = -1
                continue
            }
            if ((touchFrame.flags[s] and TouchFrame.F_UP) != 0) {
                activePointers[s] = -1
                continue
            }
            activePointers[s] = pid
            n++
        }
        return n
    }

    private fun extractActiveCoordinates(): Int {
        var n = 0
        for (s in 0 until MusicalConstants.MAX_VOICES) {
            val pid = touchFrame.pointerIds[s]
            if (pid == TouchFrame.INVALID_ID) continue
            if ((touchFrame.flags[s] and TouchFrame.F_UP) != 0) continue
            activeX[n] = touchFrame.x[s]
            activeY[n] = touchFrame.y[s]
            n++
        }
        return n
    }

    private fun processTouchSnapshot(): Boolean {
        try {
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
            val removeFinger = (fCount > 0 && fCount < lastPointerCount)
            val liftToZero = (fCount == 0 && lastPointerCount > 0)

            if (landing || addFinger) {
                releaseCascadeUntilMs = 0L
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

            if (liftToZero) {
                if (TelemetryRecorder.isRecording() && lastPointerCount > 0) {
                    val state = harmonicEngine.state
                    val tNs = SystemClock.elapsedRealtimeNanos()
                    TelemetryRecorder.recordSnapshotEnd(
                        tNs = tNs,
                        fingerCount = 0,
                        centroidX = lastActiveCenterX,
                        centroidY = lastActiveCenterY,
                        spread = lastSpreadSmooth,
                        instability = state.harmonicInstability,
                        triadArchetype = state.triad,
                        seventhArchetype = state.seventh,
                        rootPc = state.rootPc,
                        sector = state.functionSector
                    )
                }

                transitionWindow.arm(
                    touchFrame.tMs,
                    lastActiveCenterX,
                    lastActiveCenterY,
                    harmonicEngine.state,
                    lastPointerCount
                )
                radiusFilter.reset()
            }

            if (removeFinger) {
                val dl = touchFrame.tMs + MusicalConstants.RELEASE_CASCADE_MS
                if (dl > releaseCascadeUntilMs) releaseCascadeUntilMs = dl
            }
            if (liftToZero || fCount == 0) releaseCascadeUntilMs = 0L

            if (landing || addFinger) {
                val dl = touchFrame.tMs + MusicalConstants.LANDING_CASCADE_MS
                if (dl > landingCascadeUntilMs) landingCascadeUntilMs = dl
            }
            if (removeFinger) landingCascadeUntilMs = 0L
            if (liftToZero || fCount == 0) landingCascadeUntilMs = 0L

            if (touchState.isActive) {
                val tSec = (System.nanoTime() - startTime) / 1_000_000_000f
                val spreadSmooth = radiusFilter.filter(touchState.radius, tSec)
                lastSpreadSmooth = spreadSmooth

                val expansion01 = ((spreadSmooth - r1Px) / (r2Px - r1Px)).coerceIn(0f, 1f)
                touchDriver.expansion01 = expansion01

                fillActivePointersFromFrame()

                for (s in 0 until MusicalConstants.MAX_VOICES) {
                    val pid = touchFrame.pointerIds[s]
                    if (pid == TouchFrame.INVALID_ID) continue
                    if ((touchFrame.flags[s] and TouchFrame.F_UP) != 0) continue

                    val force = touchFrame.force01[s]
                    val at = (force * 127f).toInt()
                    voiceLeader.setSlotAftertouch(s, pid, at)
                    val x = touchFrame.x[s]
                    val y = touchFrame.y[s]
                    if (timbreNav.compute(pid, x, y, gestureContainer)) {
                        val bend14 = (MusicalConstants.CENTER_PITCH_BEND +
                                (gestureContainer.dxNorm * 8191f)).toInt().coerceIn(0, 16383)
                        voiceLeader.setSlotPitchBend(s, pid, bend14)
                        val cc74 = (MusicalConstants.CENTER_CC74 +
                                (-gestureContainer.dyNorm * 63f)).toInt().coerceIn(0, 127)
                        voiceLeader.setSlotCC74(s, pid, cc74)
                    }
                }

                // 1. Analyze Geometry -> Semantics
                if (!transitionHit) {
                    val activeCount = extractActiveCoordinates()
                    // v0.2 Analyzer Signature
                    gestureAnalyzer.analyze(
                        pointCount = activeCount,
                        packedX = activeX,
                        packedY = activeY,
                        slotGeometry = TouchSource.slotGeometry,
                        activeSlotMask = voiceAllocator.activeSlotMask,
                        outState = harmonicEngine.state
                    )
                }

                if (landing && MusicalConstants.IS_DEBUG) {
                    Log.d(
                        TAG_SEM,
                        "${touchFrame.tMs},LAND,f=$fCount,triad=${harmonicEngine.state.triad},sev=${harmonicEngine.state.seventh}"
                    )
                }

                val centerYNorm =
                    if (overlay.height > 0) (touchState.centerY / overlay.height.toFloat()) else 0.5f

                val changed = if (!transitionHit) {
                    harmonicEngine.update(
                        nowMs = touchFrame.tMs,
                        angleRad = touchState.angle,
                        spreadPx = spreadSmooth,
                        centerYNorm = centerYNorm,
                        activeSlotMask = voiceAllocator.activeSlotMask
                    )
                } else {
                    false
                }

                if (changed && MusicalConstants.IS_DEBUG) {
                    MidiLogger.logHarmony(harmonicEngine.state)
                }

                if (TelemetryRecorder.isRecording() && touchState.isActive) {
                    val state = harmonicEngine.state
                    val snapTNs = SystemClock.elapsedRealtimeNanos()
                    TelemetryRecorder.recordSnapshot(
                        tNs = snapTNs,
                        fingerCount = fCount,
                        centroidX = touchState.centerX,
                        centroidY = touchState.centerY,
                        spread = spreadSmooth,
                        instability = state.harmonicInstability,
                        triadArchetype = state.triad,
                        seventhArchetype = state.seventh,
                        rootPc = state.rootPc,
                        sector = state.functionSector
                    )
                }

                // Rule 6: Temporal Logic Must Never Define Harmony
                // Tell VoiceLeader whether we are in rhythmic-only cascade windows.
                val inReleaseCascade = (fCount > 0 && touchFrame.tMs < releaseCascadeUntilMs)
                val inLandingCascade = (fCount > 0 && touchFrame.tMs < landingCascadeUntilMs)

                voiceLeader.setReleaseCascadeActive(inReleaseCascade)
                voiceLeader.setLandingCascadeActive(inLandingCascade)

                // Rule 3: Modification Over Replacement
                voiceLeader.process(harmonicEngine.state, voiceAllocator, midiOutput)

                lastPointerCount = fCount
            } else {
                lastPointerCount = 0
            }

            invalidateIfVisualChanged()
            calibrationView.updateFeedback()
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

                            try {
                                activeMidiOut?.sendNoteOn(0, 60, 100)
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    activeMidiOut?.sendNoteOff(0, 60)
                                }, 300)
                            } catch (_: Exception) { }

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
        voiceLeader.allNotesOff()
        harmonicEngine.onAllFingersLift(SystemClock.uptimeMillis())
        TouchMath.reset()
        radiusFilter.reset()
        timbreNav.resetAll()
        lastPointerCount = 0
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelReleaseCoalesce()
        voiceLeader.close()
    }

    override fun onResume() {
        super.onResume()
        internalSynth.start()
        if (!cc11BaselineSent) {
            try {
                internalSynth.controlChange(0, 11, 127)
                cc11BaselineSent = true
            } catch (_: Exception) { }
        }
    }
}