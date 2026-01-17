package com.breathinghand.core.midi

import android.media.midi.MidiReceiver

/**
 * Fan-out MIDI router:
 * - primary: usually internal synth
 * - secondary: optional external MIDI device
 *
 * Also retains MidiReceiver helper send2/send3 for Android sinks that need them.
 */
open class FanOutMidiSink(
    primary: MidiSink? = null
) : MidiSink {

    @Volatile private var primarySink: MidiSink? = primary
    @Volatile private var secondarySink: MidiSink? = null

    // Scratch buffer for MidiReceiver helper methods (zero-alloc hot path).
    protected val buffer: ByteArray = ByteArray(3)

    fun setPrimary(sink: MidiSink?) {
        primarySink = sink
    }

    fun setSecondary(sink: MidiSink?) {
        secondarySink = sink
    }

    override fun send3(status: Int, data1: Int, data2: Int) {
        primarySink?.send3(status, data1, data2)
        secondarySink?.send3(status, data1, data2)
    }

    override fun send2(status: Int, data1: Int) {
        primarySink?.send2(status, data1)
        secondarySink?.send2(status, data1)
    }

    override fun close() {
        try { secondarySink?.close() } catch (_: Exception) {}
        try { primarySink?.close() } catch (_: Exception) {}
        secondarySink = null
        primarySink = null
    }

    // --- Android-only helper methods for subclasses that talk to MidiReceiver directly.

    protected fun send2(receiver: MidiReceiver?, b0: Int, b1: Int, timestamp: Long = 0L) {
        if (receiver == null) return
        buffer[0] = b0.toByte()
        buffer[1] = b1.toByte()
        try {
            receiver.send(buffer, 0, 2, timestamp)
        } catch (_: Exception) { }
    }

    protected fun send3(receiver: MidiReceiver?, b0: Int, b1: Int, b2: Int, timestamp: Long = 0L) {
        if (receiver == null) return
        buffer[0] = b0.toByte()
        buffer[1] = b1.toByte()
        buffer[2] = b2.toByte()
        try {
            receiver.send(buffer, 0, 3, timestamp)
        } catch (_: Exception) { }
    }
}
