package com.breathinghand

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

class MainActivity : AppCompatActivity() {

    private val touchState = MutableTouchPolar()
    private val radiusFilter = OneEuroFilter()
    private val harmonicEngine = HarmonicEngine()
    private val startTime = System.nanoTime()

    private var voiceLeader: VoiceLeader? = null
    private lateinit var overlay: HarmonicOverlayView

    // --- FRAME LATCH (Ghost Note Fix) ---
    private val choreographer: Choreographer by lazy { Choreographer.getInstance() }
    private val pendingState = HarmonicState()
    private var pendingDirty = false
    private var frameArmed = false

    private val frameCallback = Choreographer.FrameCallback {
        frameArmed = false
        if (pendingDirty) {
            voiceLeader?.update(pendingState)
            pendingDirty = false
        }
    }
    // -----------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        overlay = HarmonicOverlayView(this, harmonicEngine)
        setContentView(overlay)

        setupMidi()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        try {
            when (event.actionMasked) {
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    cancelFrameCommit()          // stop any pending "next frame" MIDI commit
                    voiceLeader?.allNotesOff()   // immediate silence
                    TouchMath.reset()
                    radiusFilter.reset()
                    overlay.invalidate()
                    return true
                }
            }

            val cx = overlay.width / 2f
            val cy = overlay.height / 2f

            // Update physics as fast as touch events arrive (no MIDI here)
            TouchMath.update(event, cx, cy, touchState)

            if (touchState.isActive) {
                val tSec = (System.nanoTime() - startTime) / 1_000_000_000f
                val rSmooth = radiusFilter.filter(touchState.radius, tSec)

                val changed = harmonicEngine.update(
                    touchState.angle,
                    rSmooth,
                    touchState.pointerCount
                )

                if (changed) {
                    // Latch the latest desired state; commit once per VSync frame
                    pendingState.setFrom(harmonicEngine.state)
                    pendingDirty = true
                    armFrameCommit()
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
        cancelFrameCommit()
        voiceLeader?.allNotesOff()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelFrameCommit()
        try {
            voiceLeader?.close()
        } catch (_: Exception) {
        }
    }

    // --- LATCH HELPERS ---

    private fun armFrameCommit() {
        if (!frameArmed) {
            frameArmed = true
            choreographer.postFrameCallback(frameCallback)
        }
    }

    private fun cancelFrameCommit() {
        choreographer.removeFrameCallback(frameCallback)
        frameArmed = false
        pendingDirty = false
    }

    // Copy only the stable musical identity fields (extend later if HarmonicState grows)
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
                val name = it.properties.getString(android.media.midi.MidiDeviceInfo.PROPERTY_NAME) ?: ""
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
                                usbDevice.properties.getString(android.media.midi.MidiDeviceInfo.PROPERTY_NAME)
                                    ?: props(usbDevice)
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

    private fun props(device: android.media.midi.MidiDeviceInfo): String? {
        return device.properties.getString(android.media.midi.MidiDeviceInfo.PROPERTY_PRODUCT)
    }
}
