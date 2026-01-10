package com.breathinghand.core.midi

class FanOutMidiSink(private val primary: MidiSink) : MidiSink {
    @Volatile
    private var secondary: MidiSink? = null

    fun setSecondary(sink: MidiSink?) {
        secondary?.close()
        secondary = sink
    }

    override fun send3(status: Int, data1: Int, data2: Int) {
        primary.send3(status, data1, data2)
        secondary?.send3(status, data1, data2)
    }

    override fun send2(status: Int, data1: Int) {
        primary.send2(status, data1)
        secondary?.send2(status, data1)
    }

    override fun close() {
        secondary?.close()
        secondary = null
        primary.close()
    }
}
