package com.breathinghand

import android.media.midi.MidiManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.breathinghand.core.*

class MainActivity : AppCompatActivity() {

    private val touchState = MutableTouchPolar()
    private val radiusFilter = OneEuroFilter()
    private val harmonicEngine = HarmonicEngine()
    private val startTime = System.nanoTime()

    private var voiceLeader: VoiceLeader? = null
    private lateinit var overlay: HarmonicOverlayView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        overlay = HarmonicOverlayView(this, harmonicEngine)
        setContentView(overlay)
        setupMidi()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        try {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL) {
                voiceLeader?.allNotesOff()
                TouchMath.reset()
                radiusFilter.reset()
                return true
            }

            val cx = overlay.width / 2f
            val cy = overlay.height / 2f

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
                    voiceLeader?.update(harmonicEngine.state)
                }
            }
            overlay.invalidate()

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return true
    }

    private fun setupMidi() {
        try {
            val midiManager = getSystemService(MIDI_SERVICE) as? MidiManager ?: return
            @Suppress("DEPRECATION")
            val devices = try { midiManager.devices } catch (_: SecurityException) { emptyArray() }

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
                            val name = usbDevice.properties.getString(android.media.midi.MidiDeviceInfo.PROPERTY_NAME)
                                ?: props(usbDevice) ?: "USB MIDI Device"

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

    override fun onDestroy() {
        super.onDestroy()
        try { voiceLeader?.close() } catch (_: Exception) {}
    }
}