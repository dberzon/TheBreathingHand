package com.breathinghand.core.midi

import android.media.midi.MidiInputPort
import android.util.Log
import java.io.IOException

class AndroidMidiSink(private val port: MidiInputPort) : MidiSink {
    private val buffer3 = ByteArray(3)
    private val buffer2 = ByteArray(2)

    override fun send3(status: Int, data1: Int, data2: Int) {
        try {
            buffer3[0] = status.toByte()
            buffer3[1] = data1.toByte()
            buffer3[2] = data2.toByte()
            port.send(buffer3, 0, 3)
        } catch (e: IOException) {
            Log.w("AndroidMidiSink", "send3 failed", e)
        }
    }

    override fun send2(status: Int, data1: Int) {
        try {
            buffer2[0] = status.toByte()
            buffer2[1] = data1.toByte()
            port.send(buffer2, 0, 2)
        } catch (e: IOException) {
            Log.w("AndroidMidiSink", "send2 failed", e)
        }
    }

    override fun close() {
        try { port.close() } catch (e: IOException) {
            Log.w("AndroidMidiSink", "close failed", e)
        }
    }
}
