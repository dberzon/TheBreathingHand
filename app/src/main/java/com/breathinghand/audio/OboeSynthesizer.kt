package com.breathinghand.audio

class OboeSynthesizer {
    private var nativeHandle: Long = 0L

    init {
        System.loadLibrary("oboe_synth")
        nativeHandle = nativeCreate()
    }

    fun start() {
        if (nativeHandle != 0L) {
            nativeStart(nativeHandle)
        }
    }

    fun stop() {
        if (nativeHandle != 0L) {
            nativeStop(nativeHandle)
        }
    }

    fun noteOn(channel: Int, note: Int, velocity: Int) {
        if (nativeHandle != 0L) {
            nativeNoteOn(nativeHandle, channel, note, velocity)
        }
    }

    fun noteOff(channel: Int, note: Int) {
        if (nativeHandle != 0L) {
            nativeNoteOff(nativeHandle, channel, note)
        }
    }

    fun pitchBend(channel: Int, bend14: Int) {
        if (nativeHandle != 0L) {
            nativePitchBend(nativeHandle, channel, bend14)
        }
    }

    fun channelPressure(channel: Int, pressure: Int) {
        if (nativeHandle != 0L) {
            nativeChannelPressure(nativeHandle, channel, pressure)
        }
    }

    fun controlChange(channel: Int, cc: Int, value: Int) {
        if (nativeHandle != 0L) {
            nativeControlChange(nativeHandle, channel, cc, value)
        }
    }

    fun setFilterCutoff(channel: Int, cutoffHz: Float) {
        if (nativeHandle != 0L) {
            nativeSetFilterCutoff(nativeHandle, channel, cutoffHz)
        }
    }

    fun setEnvelope(channel: Int, attackMs: Float, decayMs: Float, sustainLevel: Float, releaseMs: Float) {
        if (nativeHandle != 0L) {
            nativeSetEnvelope(nativeHandle, channel, attackMs, decayMs, sustainLevel, releaseMs)
        }
    }

    fun setWaveform(index: Int) {
        if (nativeHandle != 0L) {
            nativeSetWaveform(nativeHandle, index)
        }
    }

    // Load user-supplied wavetable data from a DirectByteBuffer and pass to native layer
    fun loadWavetableFromByteBuffer(buffer: java.nio.ByteBuffer) {
        if (nativeHandle != 0L) {
            nativeLoadWavetableFromDirectBuffer(nativeHandle, buffer, buffer.capacity().toLong())
        }
    }

    fun registerSampleFromByteBuffer(buffer: java.nio.ByteBuffer, rootNote: Int, loKey: Int, hiKey: Int, name: String? = null) {
        if (nativeHandle != 0L) {
            nativeRegisterSample(nativeHandle, buffer, buffer.capacity().toLong(), rootNote, loKey, hiKey, name)
        }
    }

    fun close() {
        if (nativeHandle != 0L) {
            nativeStop(nativeHandle)
            nativeDelete(nativeHandle)
            nativeHandle = 0L
        }
    }

    /**
     * Load a SoundFont (SF2) from a filesystem path. Must be called off the audio thread.
     * Returns true on success, false on failure (or if synth not initialized).
     */
    fun loadSoundFontFromPath(path: String): Boolean {
        if (nativeHandle == 0L) return false
        return nativeLoadSoundFont(nativeHandle, path)
    }

    /** Initialize FluidSynth state (must be called from non-audio thread). */
    fun initFluidSynth(): Boolean {
        if (nativeHandle == 0L) return false
        return nativeInitFluidSynth(nativeHandle)
    }

    /** Shutdown FluidSynth and free native resources. */
    fun shutdownFluidSynth(): Boolean {
        if (nativeHandle == 0L) return false
        return nativeShutdownFluidSynth(nativeHandle)
    }

    private external fun nativeCreate(): Long
    private external fun nativeDelete(handle: Long)
    private external fun nativeStart(handle: Long)
    private external fun nativeStop(handle: Long)
    private external fun nativeNoteOn(handle: Long, channel: Int, note: Int, velocity: Int)
    private external fun nativeNoteOff(handle: Long, channel: Int, note: Int)
    private external fun nativePitchBend(handle: Long, channel: Int, bend14: Int)
    private external fun nativeChannelPressure(handle: Long, channel: Int, pressure: Int)
    private external fun nativeControlChange(handle: Long, channel: Int, cc: Int, value: Int)
    private external fun nativeSetFilterCutoff(handle: Long, channel: Int, cutoffHz: Float)
    private external fun nativeSetEnvelope(handle: Long, channel: Int, attackMs: Float, decayMs: Float, sustainLevel: Float, releaseMs: Float)
    private external fun nativeSetWaveform(handle: Long, index: Int)
    private external fun nativeLoadWavetableFromDirectBuffer(handle: Long, buffer: java.nio.ByteBuffer?, size: Long)
    private external fun nativeRegisterSample(handle: Long, buffer: java.nio.ByteBuffer?, size: Long, rootNote: Int, loKey: Int, hiKey: Int, name: String?)
    private external fun nativeLoadSoundFont(handle: Long, path: String?): Boolean
    private external fun nativeInitFluidSynth(handle: Long): Boolean
    private external fun nativeShutdownFluidSynth(handle: Long): Boolean

    /** Returns whether FluidSynth support was compiled into the native library. */
    fun isFluidSynthCompiled(): Boolean {
        return nativeIsFluidSynthCompiled()
    }
    private external fun nativeIsFluidSynthCompiled(): Boolean

    fun getLoadedSampleNames(): Array<String> {
        if (nativeHandle == 0L) return arrayOf()
        return nativeGetLoadedSampleNames(nativeHandle) ?: arrayOf()
    }

    fun unloadSample(index: Int) {
        if (nativeHandle == 0L) return
        nativeUnloadSample(nativeHandle, index)
    }

    private external fun nativeGetLoadedSampleNames(handle: Long): Array<String>?
    private external fun nativeUnloadSample(handle: Long, index: Int)
}

