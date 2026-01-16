package com.breathinghand

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
import com.breathinghand.core.*
import com.breathinghand.core.midi.AndroidForensicLogger
import com.breathinghand.core.midi.AndroidMonotonicClock
import com.breathinghand.core.midi.AndroidMidiSink
import com.breathinghand.core.midi.FanOutMidiSink
import com.breathinghand.core.midi.MonoChannelMidiSink
import com.breathinghand.core.midi.MidiOut
import com.breathinghand.core.midi.OboeMidiSink
import java.io.IOException
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG_SEM = "BH_SEM"
        private const val COALESCE_WINDOW_MS = 10L
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

    // Release coalescing (INPUT layer):
    // Snapshot boundary lives in onTouchEvent(): ingest once -> derive BOTH harmony + activePointerIds from touchFrame.
    // Coalescing batches near-simultaneous ACTION_POINTER_UP bursts so harmony is recomputed ONCE (no transient states).
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

    @Volatile
    private var voiceLeader: VoiceLeader? = null

    // We keep a reference to the raw MIDI transport so we can swap routing without reconnecting
    private var activeMidiOut: MidiOut? = null
    private lateinit var internalSynth: OboeSynthesizer
    private lateinit var midiFanOut: FanOutMidiSink
    private var externalMidiSink: AndroidMidiSink? = null
    private var cc11BaselineSent: Boolean = false

    private lateinit var overlay: HarmonicOverlayView
    private lateinit var externalRoutingSwitch: Switch // External MIDI selector (MPE multi-channel / General MIDI single-channel)
    private var calibrationView: CalibrationView? = null

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

    private val gestureContainer = TimbreNavigator.MutableGesture()
    private var lastPointerCount = 0

    // Release-cascade suppression (rhythmic-only): prevent re-voicing/note-ons during multi-finger lift collapse.
    private var releaseCascadeUntilMs: Long = 0L

    // Landing/add-finger cascade suppression (rhythmic-only): prevent arpeggiated partial-chord attacks
    // when multiple fingers land across adjacent frames (0→1→2→3→4). First contact still sounds.
    private var landingCascadeUntilMs: Long = 0L

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

        internalSynth = OboeSynthesizer()

        // --- FluidSynth default SF2 auto-load (off the audio thread) ---
        // Asset: app/src/main/assets/sf2/default.sf2
        // Copy:  filesDir/sf2/default.sf2
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

        // Debug: auto-run self-test if requested via intent extra 'selfTest' (debug only)
        if (intent?.getBooleanExtra("selfTest", false) == true) {
            runSelfTest()
        }

        // --- NEW UI SETUP ---
        setupUI()
        // External routing will be initialized after the switch is created below.

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

        // 3. External MIDI Mode switch (Top Right)
        externalRoutingSwitch = Switch(this)
        externalRoutingSwitch.textSize = 14f
        externalRoutingSwitch.setTextColor(-1) // White text
        externalRoutingSwitch.isChecked = false // Default to General MIDI (single-channel)
        externalRoutingSwitch.text = if (externalRoutingSwitch.isChecked) getString(R.string.external_midi_label_mpe) else getString(R.string.external_midi_label_gm)

        // Position: Top Right
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.setMargins(0, 48, 48, 0) // Margin for status bar
        externalRoutingSwitch.layoutParams = params

        // 4. Handle External MIDI Mode Switching (affects external output only)
        externalRoutingSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateMidiRouting(isChecked)
            val routingName = if (isChecked) getString(R.string.external_midi_name_mpe) else getString(R.string.external_midi_name_gm)
            // Update switch label to reflect current external routing
            externalRoutingSwitch.text = "External MIDI: $routingName"
            Toast.makeText(this, "External MIDI: $routingName", Toast.LENGTH_SHORT).show()
        }

        container.addView(externalRoutingSwitch)
        // Initialize external routing state (affects external output only; internal synth remains on channel 0)
        updateMidiRouting(externalRoutingSwitch.isChecked)

        // 5. FluidSynth switch (below routing switch)
        val fluidsynthSwitch = Switch(this)
        fluidsynthSwitch.textSize = 14f
        fluidsynthSwitch.setTextColor(-1)
        fluidsynthSwitch.isChecked = false
        fluidsynthSwitch.text = "FluidSynth: Off"

        val fsParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        // place under routing switch (same top/right alignment with extra offset)
        fsParams.gravity = Gravity.TOP or Gravity.END
        fsParams.setMargins(0, 112, 48, 0)
        fluidsynthSwitch.layoutParams = fsParams

        fluidsynthSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Turn on: load bundled default SF2 (user can still pick another SF2 via picker if desired)
                importScope.launch { internalSynth.initFluidSynthAndLoadBundledDefaultSf2(this@MainActivity) }
            } else {
                // Turn off: shutdown synth
                importScope.launch {
                    val ok = internalSynth.shutdownFluidSynth()
                    runOnUiThread {
                        fluidsynthSwitch.text = if (ok) "FluidSynth: Off" else "FluidSynth: Off"
                        if (ok) Toast.makeText(this@MainActivity, "FluidSynth shutdown", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Optional: long-press to pick a custom SF2 (keeps the old workflow available)
        fluidsynthSwitch.setOnLongClickListener {
            pickSf2Launcher.launch(arrayOf("*/*"))
            Toast.makeText(this, "Pick a SoundFont (.sf2)", Toast.LENGTH_SHORT).show()
            true
        }

        container.addView(fluidsynthSwitch)

        // 6. Calibration View (Debug Overlay - Bottom Left)
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

        // Set the container as the activity content
        setContentView(container)
    }

    /**
     * Swaps the VoiceLeader routing strategy without breaking the USB connection.
     */
    private fun updateMidiRouting(useMultiChannel: Boolean) {
        val midi = activeMidiOut ?: return // Can't update if not connected

        // 1. Silence current notes
        voiceLeader?.allNotesOff()

        // 2. Choose Strategy
        val output: MidiOutput = if (useMultiChannel) {
            MpeMidiOutput(midi)
        } else {
            StandardMidiOutput(midi)
        }

        // 3. Replace VoiceLeader
        voiceLeader = VoiceLeader(output)
        // Apply internal CC11 expression toggle from constants
        voiceLeader?.setUseExpressionCc11(MusicalConstants.INTERNAL_USE_CC11_EXPRESSION)
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

    private val importScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    // Temporary cache path for loaded soundfonts
    private fun cachedSf2File(): java.io.File {
        return java.io.File(cacheDir, "loaded_soundfont.sf2")
    }

    private fun onSf2Picked(uri: android.net.Uri) {
        // Quick runtime check whether native FluidSynth support is compiled in
        if (!internalSynth.isFluidSynthCompiled()) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "FluidSynth support is not included in this build. Add third_party/fluidsynth (headers + per-ABI libs) and rebuild.", Toast.LENGTH_LONG).show()
            }
            return
        }

        // Copy SF2 to app cache then init FluidSynth and load it (IO coroutine)
        importScope.launch {
            try {
                val outFile = cachedSf2File()
                contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(outFile).use { out ->
                        input.copyTo(out)
                    }
                }

                // Initialize synth first (non-audio thread)
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

    // Debug helper: auto-load a test SF2 from /sdcard/Download/test.sf2 and emit a small MIDI sequence
    private fun runSelfTest() {
        importScope.launch {
            try {
                android.util.Log.d("BreathingHand", "runSelfTest: Starting self-test (loading /sdcard/Download/test.sf2)")
                val initOk = internalSynth.initFluidSynth()
                if (!initOk) {
                    android.util.Log.e("BreathingHand", "runSelfTest: initFluidSynth() failed")
                    return@launch
                }
                val loadOk = internalSynth.loadSoundFontFromPath("/sdcard/Download/test.sf2")
                android.util.Log.d("BreathingHand", "runSelfTest: loadSoundFontFromPath -> $loadOk")
                if (!loadOk) return@launch

                // Emit test MIDI: pitch bend on primary (ch 0), then NOTE ON on ch 5 (should map to ch 0 internally)
                // pitch bend: send3(status=0xE0|0, lsb=127, msb=127) -> upward bend
                midiFanOut.send3(0xE0 or 0, 127, 127)
                kotlinx.coroutines.delay(150)

                // Note on channel 5 (should be remapped to channel 0 for internal synth)
                midiFanOut.send3(0x90 or 5, 60, 100)
                kotlinx.coroutines.delay(600)

                // Channel pressure on primary (ch 0) - should affect internal synth
                midiFanOut.send2(0xD0 or 0, 80)
                kotlinx.coroutines.delay(300)

                // Note off
                midiFanOut.send3(0x80 or 5, 60, 0)
                android.util.Log.d("BreathingHand", "runSelfTest: Sequence complete")
            } catch (e: Exception) {
                android.util.Log.e("BreathingHand", "runSelfTest: exception ${'$'}e")
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Pass touch events to the Overlay manually if the Switch doesn't consume them?
        // Actually, returning false here allows standard dispatch, but since we override onTouchEvent
        // at the Activity level, we intercept everything not caught by views.
        // The Switch will handle its own touches. We process the rest for music.

        // TELEMETRY: Raw touch capture (BEFORE touchDriver.ingest() for zero-latency recording)
        // Capture all active pointers on each event to get complete raw signal
        val actionMasked = event.actionMasked
        if (TelemetryRecorder.isRecording()) {
            val pointerCount = event.pointerCount
            for (i in 0 until pointerCount) {
                val pointerId = event.getPointerId(i)
                val x = event.getX(i)
                val y = event.getY(i)
                // Record with the masked action (ACTION_DOWN, ACTION_MOVE, ACTION_POINTER_UP, etc.)
                TelemetryRecorder.recordTouch(actionMasked, pointerId, x, y)
            }
        }

        // SNAPSHOT BOUNDARY:
        // MotionEvent -> AndroidTouchDriver.ingest() happens exactly once,
        // then BOTH HarmonicState and activePointerIds are derived from touchFrame (same snapshot).

        // Full lift / cancel: handle immediately and cancel any pending coalesced flush.
        if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
            cancelReleaseCoalesce()
            touchDriver.ingest(event)
            handleAllFingersLift(actionMasked)
            return true
        }

        // If a new non-UP event arrives while a release burst is pending, flush first.
        // Prevents stale lift processing after a new touch-down.
        if (releaseCoalesceActive && actionMasked != MotionEvent.ACTION_POINTER_UP) {
            flushReleaseCoalesce(force = true)
        }

        touchDriver.ingest(event)

        if (actionMasked == MotionEvent.ACTION_POINTER_UP) {
            // Immediate note-offs only: run VoiceLeader allocation update with frozen harmony.
            // Release per-pointer timbre tracking immediately from THIS snapshot.
            for (s in 0 until MusicalConstants.MAX_VOICES) {
                val pid = touchFrame.pointerIds[s]
                if (pid == TouchFrame.INVALID_ID) continue
                if ((touchFrame.flags[s] and TouchFrame.F_UP) != 0) {
                    timbreNav.onPointerUp(pid)
                }
            }

            val activeNow = fillActivePointersFromFrame()

            // Enter/extend release-cascade window if we still have fingers down after this POINTER_UP.
            // This prevents "last finger becomes a new 1-finger chord attack" during collapse.
            if (activeNow > 0) {
                val dl = touchFrame.tMs + MusicalConstants.RELEASE_CASCADE_MS
                if (dl > releaseCascadeUntilMs) releaseCascadeUntilMs = dl
            } else {
                releaseCascadeUntilMs = 0L
            }

            val inReleaseCascade = (activeNow > 0 && touchFrame.tMs < releaseCascadeUntilMs)
            voiceLeader?.setReleaseCascadeActive(inReleaseCascade)
            voiceLeader?.setLandingCascadeActive(false)
            voiceLeader?.update(harmonicEngine.state, activePointers)

            // Sliding window: extend deadline on each POINTER_UP.
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
        voiceLeader?.allNotesOff()

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
            // Exclude the pointer that went UP in this snapshot.
            if ((touchFrame.flags[s] and TouchFrame.F_UP) != 0) {
                activePointers[s] = -1
                continue
            }
            activePointers[s] = pid
            n++
        }
        return n
    }

    // SNAPSHOT BOUNDARY: TouchFrame is ingested once per event; harmony + activePointers derive from the same frame.
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

            // Any new landing or addition cancels release-cascade suppression immediately.
            if (landing || addFinger) {
                releaseCascadeUntilMs = 0L
            }

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
            // ACTION_UP / ACTION_CANCEL are handled in onTouchEvent() before calling this snapshot function.

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

            // If finger count is collapsing (multi-finger lift), suppress re-voicing/note-ons briefly.
            if (removeFinger) {
                val dl = touchFrame.tMs + MusicalConstants.RELEASE_CASCADE_MS
                if (dl > releaseCascadeUntilMs) releaseCascadeUntilMs = dl
            }
            if (liftToZero || fCount == 0) releaseCascadeUntilMs = 0L

            // If finger count is increasing (multi-finger landing/add), suppress intermediate re-voicing/note-ons briefly.
            // First contact still sounds; this only prevents 0→1→2→3→4 partial-chord 'chatter'.
            if (landing || addFinger) {
                val dl = touchFrame.tMs + MusicalConstants.LANDING_CASCADE_MS
                if (dl > landingCascadeUntilMs) landingCascadeUntilMs = dl
            }
            // Any removal cancels landing cascade immediately.
            if (removeFinger) landingCascadeUntilMs = 0L
            if (liftToZero || fCount == 0) landingCascadeUntilMs = 0L

            if (touchState.isActive) {
                val tSec = (System.nanoTime() - startTime) / 1_000_000_000f
                val spreadSmooth = radiusFilter.filter(touchState.radius, tSec)

                val expansion01 = ((spreadSmooth - r1Px) / (r2Px - r1Px)).coerceIn(0f, 1f)
                touchDriver.expansion01 = expansion01

                // ACTIVE POINTER IDS derived from SAME snapshot as harmony (exclude F_UP).
                fillActivePointersFromFrame()

                for (s in 0 until MusicalConstants.MAX_VOICES) {
                    val pid = touchFrame.pointerIds[s]
                    if (pid == TouchFrame.INVALID_ID) continue
                    if ((touchFrame.flags[s] and TouchFrame.F_UP) != 0) continue

                    val force = touchFrame.force01[s]
                    val at = (force * 127f).toInt()
                    voiceLeader?.setSlotAftertouch(s, pid, at)
                    val x = touchFrame.x[s]
                    val y = touchFrame.y[s]
                    if (timbreNav.compute(pid, x, y, gestureContainer)) {
                        val bend14 = (MusicalConstants.CENTER_PITCH_BEND +
                                (gestureContainer.dxNorm * 8191f)).toInt().coerceIn(0, 16383)
                        voiceLeader?.setSlotPitchBend(s, pid, bend14)
                        val cc74 = (MusicalConstants.CENTER_CC74 +
                                (-gestureContainer.dyNorm * 63f)).toInt().coerceIn(0, 127)
                        voiceLeader?.setSlotCC74(s, pid, cc74)
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

                // TELEMETRY: Analyzer snapshot capture (after HarmonicEngine.update())
                // Captures what the instrument *believes* is happening (derived features)
                if (TelemetryRecorder.isRecording() && touchState.isActive) {
                    val state = harmonicEngine.state
                    TelemetryRecorder.recordSnapshot(
                        fingerCount = fCount,
                        centroidX = touchState.centerX,
                        centroidY = touchState.centerY,
                        spread = spreadSmooth,
                        instability = state.harmonicInstability,
                        triadArchetype = gestureAnalyzer.latchedTriad,
                        seventhArchetype = gestureAnalyzer.latchedSeventh,
                        rootPc = state.rootPc,
                        sector = state.functionSector
                    )
                }

                // Tell VoiceLeader whether we are in rhythmic-only cascade windows.
                val inReleaseCascade = (fCount > 0 && touchFrame.tMs < releaseCascadeUntilMs)
                voiceLeader?.setReleaseCascadeActive(inReleaseCascade)
                val inLandingCascade = (fCount > 0 && touchFrame.tMs < landingCascadeUntilMs)
                voiceLeader?.setLandingCascadeActive(inLandingCascade)
                voiceLeader?.update(harmonicEngine.state, activePointers)
                lastPointerCount = fCount
            } else {
                lastPointerCount = 0
            }

            invalidateIfVisualChanged()

            // Update calibration view feedback
            calibrationView?.updateFeedback()
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

                            // Quick validation: play a short test note on channel 1 (Middle C)
                            try {
                                activeMidiOut?.sendNoteOn(0, 60, 100)
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    activeMidiOut?.sendNoteOff(0, 60)
                                }, 300)
                            } catch (_: Exception) { /* ignore test failures */ }

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
        cancelReleaseCoalesce()
        voiceLeader?.close()
    }

    override fun onResume() {
        super.onResume()
        internalSynth.start()
        // If internal CC11 expression is disabled, send baseline CC11=127 once to channel 0
        if (!MusicalConstants.INTERNAL_USE_CC11_EXPRESSION && !cc11BaselineSent) {
            try {
                internalSynth.controlChange(0, 11, 127)
                cc11BaselineSent = true
            } catch (_: Exception) { /* ignore */ }
        }
    }
}