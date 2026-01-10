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
import androidx.activity.result.contract.ActivityResultContracts
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

    // Activity result launcher for picking a single audio file (WAV) — simple custom wavetable
    private val pickWavLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { loadWavetableUri(it) }
    }

    // Activity result launcher for mapped samples (asks for mapping metadata)
    private val pickMappedSampleLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { showMappedSampleDialog(it) }
    }

    // SFZ file picker
    private val pickSfzLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { onSfzPicked(it) }
    }

    // Pick a folder that contains sample files (OpenDocumentTree)
    private val pickSfzFolderLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { onSfzFolderPicked(it) }
    }

    // Pick individual missing sample file
    private val pickSingleSampleLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { onSingleSamplePicked(it) }
    }

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

        internalSynth = OboeSynthesizer()
        midiFanOut = FanOutMidiSink(OboeMidiSink(internalSynth))
        activeMidiOut = MidiOut(midiFanOut, AndroidMonotonicClock, AndroidForensicLogger)

        // --- NEW UI SETUP ---
        setupUI()
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

        // Long-press the switch to pick a custom wavetable (.wav). Uses a DirectByteBuffer and hands it to native code.
        mpeSwitch.setOnLongClickListener {
            pickWavLauncher.launch(arrayOf("audio/wav", "audio/*"))
            Toast.makeText(this, "Pick a .wav to load as wavetable", Toast.LENGTH_SHORT).show()
            true
        }

        container.addView(mpeSwitch)

        // Controls overlay (floating)
        val controlsOverlay = ControlsOverlayView(this, internalSynth)
        val controlsParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        controlsParams.gravity = Gravity.BOTTOM or Gravity.END
        controlsParams.setMargins(48, 48, 48, 48)
        controlsOverlay.layoutParams = controlsParams
        container.addView(controlsOverlay)

        // Mapped sample picker (from controls overlay)
        controlsOverlay.onLoadMappedSample = {
            pickMappedSampleLauncher.launch(arrayOf("audio/wav", "audio/*"))
        }

        // SFZ importer (from controls overlay)
        controlsOverlay.onImportSfz = {
            pickSfzLauncher.launch(arrayOf("*/*"))
        }

        // Manage samples (from controls overlay)
        controlsOverlay.onManageSamples = {
            showManageSamplesDialog()
        }

        // Set the container as the activity content
        setContentView(container)
    }

    private fun showManageSamplesDialog() {
        val names = internalSynth.getLoadedSampleNames()
        if (names.isEmpty()) {
            Toast.makeText(this, "No samples loaded", Toast.LENGTH_SHORT).show()
            return
        }
        var selectedIndex = 0
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Loaded Samples")
        builder.setSingleChoiceItems(names, selectedIndex) { _, which ->
            selectedIndex = which
        }
        builder.setPositiveButton("Unload") { _, _ ->
            val name = names.getOrNull(selectedIndex) ?: return@setPositiveButton
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Unload Sample")
                .setMessage("Unload '$name'? This will remove its mappings.")
                .setPositiveButton("Yes") { _, _ ->
                    internalSynth.unloadSample(selectedIndex)
                    Toast.makeText(this, "Unloaded $name", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        builder.setNegativeButton("Close", null)
        builder.show()
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

    // Read content from the URI into a DirectByteBuffer and pass it to the native engine
    private fun loadWavetableUri(uri: android.net.Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                val bb = java.nio.ByteBuffer.allocateDirect(bytes.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                bb.put(bytes)
                bb.rewind()

                // Hand the DirectByteBuffer to the native side via the OboeSynthesizer wrapper
                internalSynth.loadWavetableFromByteBuffer(bb)
                Toast.makeText(this, "Loaded wavetable (${bytes.size} bytes)", Toast.LENGTH_SHORT).show()
            }
        } catch (e: java.io.IOException) {
            Toast.makeText(this, "Failed to load wavetable: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showMappedSampleDialog(uri: android.net.Uri) {
        // Dialog to ask for root, lo, hi (simple numeric inputs)
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
        builder.setTitle("Sample Mapping")
        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        val rootInput = android.widget.EditText(this)
        rootInput.hint = "Root MIDI note (0..127)"
        rootInput.setText("60")
        val loInput = android.widget.EditText(this)
        loInput.hint = "Low key (0..127)"
        loInput.setText("0")
        val hiInput = android.widget.EditText(this)
        hiInput.hint = "High key (0..127)"
        hiInput.setText("127")
        layout.setPadding(24, 12, 24, 12)
        layout.addView(rootInput)
        layout.addView(loInput)
        layout.addView(hiInput)
        builder.setView(layout)
        builder.setPositiveButton("Register") { _, _ ->
            val root = rootInput.text.toString().toIntOrNull()?.coerceIn(0, 127) ?: 60
            val lo = loInput.text.toString().toIntOrNull()?.coerceIn(0, 127) ?: 0
            val hi = hiInput.text.toString().toIntOrNull()?.coerceIn(0, 127) ?: 127
            // Read file and register
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    val bytes = input.readBytes()
                    val bb = java.nio.ByteBuffer.allocateDirect(bytes.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    bb.put(bytes)
                    bb.rewind()
                    val before = internalSynth.getLoadedSampleNames().toSet()
                    internalSynth.registerSampleFromByteBuffer(bb, root, lo, hi)
                    val after = internalSynth.getLoadedSampleNames().toSet()
                    val added = after - before
                    if (added.isNotEmpty()) {
                        Toast.makeText(this, "Registered sample (root=$root lo=$lo hi=$hi)", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Registration attempted but no sample was added (check logs)", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: java.io.IOException) {
                Toast.makeText(this, "Failed to register sample: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    // --- SFZ Importer helpers ---
    data class ParsedRegion(val sampleName: String, val root: Int, val lo: Int, val hi: Int)

    private var pendingSfzRegions: List<ParsedRegion>? = null
    private var pendingSfzMissing: MutableList<String> = mutableListOf()
    private var pendingSfzMissingIndex = 0

    private fun onSfzPicked(uri: android.net.Uri) {
        // Parse SFZ file for regions; then ask user to pick a folder containing samples
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
            val regions = mutableListOf<ParsedRegion>()
            text.lines().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEach
                if (!line.contains("sample=")) return@forEach
                // Tokenize by whitespace
                val tokens = line.split(Regex("\\s+"))
                var sampleName: String? = null
                var lokey: Int? = null
                var hikey: Int? = null
                var root: Int? = null
                for (t in tokens) {
                    if (t.startsWith("sample=")) sampleName = t.substringAfter("=")
                    if (t.startsWith("lokey=")) lokey = parseSfzKey(t.substringAfter("="))
                    if (t.startsWith("hikey=")) hikey = parseSfzKey(t.substringAfter("="))
                    if (t.startsWith("pitch_keycenter=") || t.startsWith("key=") || t.startsWith("pitch_keycenter")) {
                        val v = t.substringAfter("=")
                        root = parseSfzKey(v)
                    }
                }
                if (sampleName != null) {
                    val lo = lokey ?: 0
                    val hi = hikey ?: 127
                    val r = root ?: ((lo + hi) / 2)
                    regions.add(ParsedRegion(sampleName, r, lo, hi))
                }
            }
            if (regions.isEmpty()) {
                Toast.makeText(this, "No regions found in SFZ", Toast.LENGTH_LONG).show()
                return
            }
            pendingSfzRegions = regions
            // Ask user to pick the folder containing samples
            Toast.makeText(this, "Select the folder that contains SFZ sample files", Toast.LENGTH_SHORT).show()
            pickSfzFolderLauncher.launch(null)
        } catch (e: java.io.IOException) {
            Toast.makeText(this, "Failed to read SFZ: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun parseSfzKey(s: String): Int? {
        // Accept integers or note names like c4 or c-1
        val asInt = s.toIntOrNull()
        if (asInt != null) return asInt
        // Parse note name
        val m = Regex("^([A-Ga-g])([#b]?)(-?\\d+)").find(s)
        if (m != null) {
            val note = m.groupValues[1].uppercase()
            val acc = m.groupValues[2]
            val oct = m.groupValues[3].toIntOrNull() ?: return null
            val base = when (note) {
                "C" -> 0
                "D" -> 2
                "E" -> 4
                "F" -> 5
                "G" -> 7
                "A" -> 9
                "B" -> 11
                else -> 0
            }
            var sem = base
            if (acc == "#") sem += 1
            if (acc == "b") sem -= 1
            val midi = (oct + 1) * 12 + sem
            return midi.coerceIn(0, 127)
        }
        return null
    }

    private fun onSfzFolderPicked(treeUri: android.net.Uri) {
        fun getDisplayNameFromDoc(doc: androidx.documentfile.provider.DocumentFile?): String? {
            if (doc == null) return null
            return doc.name
        }
        val regions = pendingSfzRegions ?: return
        val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, treeUri)
        val missing = mutableListOf<String>()
        val before = internalSynth.getLoadedSampleNames().toSet()
        regions.forEach { r ->
            val name = r.sampleName
            var doc = tree?.findFile(name)
            if (doc == null) {
                // try basename (in case path included)
                val base = name.substringAfterLast('/')
                doc = tree?.findFile(base)
            }
            if (doc == null) {
                missing.add(name)
            } else {
                try {
                    contentResolver.openInputStream(doc.uri)?.use { input ->
                        val bytes = input.readBytes()
                        val bb = java.nio.ByteBuffer.allocateDirect(bytes.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        bb.put(bytes)
                        bb.rewind()
                        val name = getDisplayNameFromDoc(doc) ?: r.sampleName.substringAfterLast('/')
                        internalSynth.registerSampleFromByteBuffer(bb, r.root, r.lo, r.hi, name)
                    }
                } catch (e: java.io.IOException) {
                    missing.add(name)
                }
            }
        }
        val after = internalSynth.getLoadedSampleNames().toSet()
        val added = after - before
        if (missing.isEmpty() && added.isNotEmpty()) {
            Toast.makeText(this, "Imported ${regions.size} regions from SFZ", Toast.LENGTH_SHORT).show()
            pendingSfzRegions = null
            return
        }
        if (missing.isEmpty() && added.isEmpty()) {
            Toast.makeText(this, "Attempted import but no samples were registered (check logs)", Toast.LENGTH_LONG).show()
            return
        }
        // Need user help to pick missing files
        pendingSfzMissing = missing.toMutableList()
        pendingSfzMissingIndex = 0
        Toast.makeText(this, "${missing.size} files missing; please select them when prompted", Toast.LENGTH_LONG).show()
        // Launch picker for first missing
        pickSingleSampleLauncher.launch(arrayOf("audio/wav", "audio/*"))
    }

    private fun onSingleSamplePicked(uri: android.net.Uri) {
        // Ensure helper for display name exists
        fun getDisplayNameLocal(u: android.net.Uri): String? {
            var name: String? = null
            val cursor = contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = it.getString(idx)
                }
            }
            return name
        }
        if (pendingSfzMissingIndex >= pendingSfzMissing.size) return
        val expectedName = pendingSfzMissing[pendingSfzMissingIndex]
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                val bb = java.nio.ByteBuffer.allocateDirect(bytes.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                bb.put(bytes)
                bb.rewind()
                // Find regions referencing expectedName and register; fall back to base name
                val regions = pendingSfzRegions ?: listOf()
                val base = expectedName.substringAfterLast('/')
                val name = getDisplayNameLocal(uri) ?: base
                val before = internalSynth.getLoadedSampleNames().toSet()
                regions.filter { it.sampleName == expectedName || it.sampleName == base }.forEach { r ->
                    internalSynth.registerSampleFromByteBuffer(bb, r.root, r.lo, r.hi, name)
                }
                val after = internalSynth.getLoadedSampleNames().toSet()
                val added = after - before
                if (added.isEmpty()) {
                    Toast.makeText(this, "Selected file registered but no samples were added (check logs)", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: java.io.IOException) {
            Toast.makeText(this, "Failed to read selected sample: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
        pendingSfzMissingIndex += 1
        if (pendingSfzMissingIndex < pendingSfzMissing.size) {
            pickSingleSampleLauncher.launch(arrayOf("audio/wav", "audio/*"))
        } else {
            Toast.makeText(this, "Completed mapping missing files", Toast.LENGTH_SHORT).show()
            pendingSfzRegions = null
            pendingSfzMissing.clear()
            pendingSfzMissingIndex = 0
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