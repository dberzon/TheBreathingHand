package com.breathinghand

import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Bundle
import android.view.Choreographer
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.breathinghand.core.HarmonicEngine
import com.breathinghand.core.HarmonicOverlayView
import com.breathinghand.core.HarmonicState
import com.breathinghand.core.MidiOut
import com.breathinghand.core.MutableTouchPolar
import com.breathinghand.core.OneEuroFilter
import com.breathinghand.core.TouchMath
import com.breathinghand.core.VoiceLeader
import com.breathinghand.core.SmartInputHAL


/**
 * Chapter 3D – Forensics + Ghost-note mitigation (Max responsiveness)
 *
 * What this does:
 * 1) Frame-latched COMMIT:
 *    - Touch physics updates run at touch-event rate.
 *    - MIDI state commits (voiceLeader.update) happen at most once per VSync frame.
 *
 * 2) Frame-latched RELEASE:
 *    - ACTION_UP / ACTION_CANCEL no longer calls allNotesOff immediately.
 *    - Instead we schedule the release on the next VSync frame.
 *    - If any new touch arrives before that frame, the release is cancelled.
 *
 * Why:
 * - Eliminates ultra-short "commit -> allNotesOff within a few ms" ghosts
 * - Adds at most ~1 frame release latency (~16ms) only on release
 *
 * Forensic logging:
 * - TouchLogger.log(...) at top of onTouchEvent
 * - MidiLogger.logCommit(...) when latch fires
 * - MidiLogger.logAllNotesOff(...) when release actually happens
 */
class MainActivity : AppCompatActivity() {

    private val touchState = MutableTouchPolar()
    private val radiusFilter = OneEuroFilter()
    private val harmonicEngine = HarmonicEngine()
    private val startTime = System.nanoTime()

    private var voiceLeader: VoiceLeader? = null
    private lateinit var overlay: HarmonicOverlayView

    // --- FRAME LATCH ---
    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }

    // Pending "commit" (new desired musical state)
    private val pendingState = HarmonicState()
    private var pendingDirty = false

    private val velBuf = IntArray(5)
    private val atBuf = IntArray(5)
    private var pendingAT = false


    // Pending "release" (all notes off), scheduled for next frame
    private var pendingRelease = false
    private var pendingReleaseReason: String = ""

    private var frameArmed = false

    private val inputHAL = SmartInputHAL(maxSlots = 5)


    private val frameCallback = Choreographer.FrameCallback {
        frameArmed = false

        // 1) Commit first (normally pendingDirty won't be true when pendingRelease is true,
        // because we cancel pendingDirty when scheduling release).
        if (pendingDirty) {
            MidiLogger.logCommit(pendingState)
            voiceLeader?.update(pendingState)
            pendingDirty = false
        }

        // 1b) Flush continuous aftertouch once per frame (more musical sustain)
        if (pendingAT) {
            voiceLeader?.flushAftertouch()
            pendingAT = false
        }

        // 2) Then do release if it’s still pending (and wasn’t cancelled by new touch).
        if (pendingRelease) {
            MidiLogger.logAllNotesOff("FRAME_LATCH_RELEASE:$pendingReleaseReason")
            voiceLeader?.allNotesOff()
            pendingRelease = false
            pendingReleaseReason = ""

            // IMPORTANT: Reset filters/math ONLY when the release actually happens.
            // This avoids spurious UP/CANCEL causing state wipes that generate ghosts.
            TouchMath.reset()
            radiusFilter.reset()
        }

        
    }
    // -------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        overlay = HarmonicOverlayView(this, harmonicEngine)
        setContentView(overlay)

        setupMidi()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        try {


            // --- FORENSIC: Raw touch (before any math) ---
            TouchLogger.log(event, overlay.width, overlay.height)
            // --------------------------------------------
            inputHAL.ingest(event)

            // If a release is pending but we get ANY new touch activity,
            // cancel the release (prevents "commit -> all off within 3-5ms" ghosts).
            if (pendingRelease && isTouchActivity(event)) {
                pendingRelease = false
                pendingReleaseReason = ""
                // If nothing else is pending, we can safely unarm the callback.
                // (If pendingDirty is set later in this event, it will re-arm.)
                if (!pendingDirty) {
                    cancelFrameCallbackIfIdle()
                }
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    // Do NOT silence immediately. Schedule a one-frame-late release.
                    // Also cancel any pending commit so we don't "commit then kill" in the same frame.
                    pendingDirty = false

                    pendingRelease = true
                    pendingReleaseReason = if (event.actionMasked == MotionEvent.ACTION_UP) "ACTION_UP" else "ACTION_CANCEL"

                    armFrameCallback()
                    overlay.invalidate()
                    return true
                }
            }

            val cx = overlay.width / 2f
            val cy = overlay.height / 2f

            // 1) Update physics at full event rate (NO MIDI)
            TouchMath.update(event, cx, cy, touchState)

            if (touchState.isActive) {
                val tSec = (System.nanoTime() - startTime) / 1_000_000_000f
                val rSmooth = radiusFilter.filter(touchState.radius, tSec)

                // Map rSmooth into 0..1 using your HarmonicEngine thresholds as a reference.
                // r1/r2 are internal in HarmonicEngine right now; tune later if needed.
                val expansion01 = ((rSmooth - 150f) / (350f - 150f)).coerceIn(0f, 1f)
                inputHAL.expansion01 = expansion01

                // --- Continuous expression: force -> aftertouch ---
                // Compute every touch event, but send only once per frame (flush in frameCallback).
                run {
                    var ai = 0
                    for (s in 0 until 5) {
                        val pid = inputHAL.slotPointerId[s]
                        if (pid == SmartInputHAL.INVALID_ID) continue

                        val at = (inputHAL.force[s] * 127f).toInt().coerceIn(0, 127)
                        atBuf[ai] = at
                        ai++
                        if (ai >= atBuf.size) break
                    }
                    for (i in ai until atBuf.size) atBuf[i] = 0

                    voiceLeader?.setAftertouch(atBuf, harmonicEngine.state.density)
                    pendingAT = true
                    armFrameCallback()
                }
                // -------------------------------------------------

                // 2) Compute desired musical state (pure logic)
                val changed = harmonicEngine.update(
                    touchState.angle,
                    rSmooth,
                    touchState.pointerCount
                )

                // 3) Latch newest desired state; commit once per frame
                if (changed) {
                    pendingState.setFrom(harmonicEngine.state)
                    pendingDirty = true

                    // --- Chapter 3D MVP dynamics ---
                    // Convert SmartInputHAL per-slot force (0..1) -> MIDI velocity (1..127).
                    // We fill velBuf in the same order we expect voices to be assigned (0..density-1).
                    var vi = 0
                    for (s in 0 until 5) {
                        val pid = inputHAL.slotPointerId[s]
                        if (pid == SmartInputHAL.INVALID_ID) continue

                        // Never send 0 on note-on.
                        var v = (1 + (inputHAL.force[s] * 126f)).toInt().coerceIn(1, 127)

                        // Wack = percussive hit: optional boost.
                        val isWack = (inputHAL.flags[s] and SmartInputHAL.F_WACK) != 0
                        if (isWack) v = (v + 20).coerceIn(1, 127)

                        velBuf[vi] = v
                        vi++
                        if (vi >= velBuf.size) break
                    }

                    // Fill remaining so VoiceLeader doesn’t reuse stale values if density shrinks.
                    for (i in vi until velBuf.size) velBuf[i] = 90

                    // IMPORTANT: set velocities before the frame-latched commit fires.
                    voiceLeader?.setVelocities(velBuf, pendingState.density)
                    // ----------------------------

                    armFrameCallback()
                }
            }

            overlay.invalidate()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return true
    }

    override fun onPause() {
        super.onPause()
        // On lifecycle transitions, be strict: cancel everything and silence immediately.
        cancelFrameCallbackHard()

        MidiLogger.logAllNotesOff("onPause")
        voiceLeader?.allNotesOff()

        TouchMath.reset()
        radiusFilter.reset()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelFrameCallbackHard()

        MidiLogger.logAllNotesOff("onDestroy")
        try { voiceLeader?.allNotesOff() } catch (_: Exception) {}
        try { voiceLeader?.close() } catch (_: Exception) {}
    }

    // --- LATCH HELPERS ---

    private fun armFrameCallback() {
        if (!frameArmed) {
            frameArmed = true
            choreographer.postFrameCallback(frameCallback)
        }
    }

    /**
     * If nothing is pending, remove callback and clear arm flag.
     * (Used when we cancelled a pendingRelease due to new touch.)
     */
    private fun cancelFrameCallbackIfIdle() {
        if (!pendingDirty && !pendingRelease && frameArmed) {
            choreographer.removeFrameCallback(frameCallback)
            frameArmed = false
        }
    }

    /**
     * Hard cancel: used onPause/onDestroy.
     */
    private fun cancelFrameCallbackHard() {
        choreographer.removeFrameCallback(frameCallback)
        frameArmed = false
        pendingDirty = false
        pendingRelease = false
        pendingReleaseReason = ""
    }

    /**
     * Any event that implies continued/renewed interaction.
     * (If a spurious UP/CANCEL happens, a new DOWN or MOVE will cancel the pending release.)
     */
    private fun isTouchActivity(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_POINTER_UP -> true
            else -> false
        }
    }

    // Copy only stable musical identity fields (extend later if HarmonicState grows)
    private fun HarmonicState.setFrom(src: HarmonicState) {
        root = src.root
        quality = src.quality
        density = src.density
    }

    // --- MIDI SETUP ---

    private fun setupMidi() {
        try {
            val midiManager = getSystemService(MIDI_SERVICE) as? MidiManager ?: return

            @Suppress("DEPRECATION")
            val devices = try {
                midiManager.devices
            } catch (_: SecurityException) {
                emptyArray()
            }

            val usbDevice = devices.find {
                val name = it.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: ""
                !name.contains("Android", ignoreCase = true) &&
                        !name.contains("SunVox", ignoreCase = true) &&
                        !name.contains("Fluid", ignoreCase = true)
            } ?: devices.firstOrNull { it.inputPortCount > 0 }

            if (usbDevice != null) {
                midiManager.openDevice(usbDevice, { device ->
                    if (device != null) {
                        val port = device.openInputPort(0)
                        if (port != null) {
                            voiceLeader = VoiceLeader(MidiOut(port))

                            val name =
                                usbDevice.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
                                    ?: usbDevice.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
                                    ?: "USB MIDI Device"

                            runOnUiThread {
                                Toast.makeText(this, "Connected: $name", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }, null)
            } else {
                runOnUiThread {
                    Toast.makeText(this, "No USB MIDI Found. Plug in device.", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
