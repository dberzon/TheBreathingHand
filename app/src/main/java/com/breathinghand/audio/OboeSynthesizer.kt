package com.breathinghand.audio

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

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

    /**
     * Ensure the bundled default SF2 exists as a real filesystem path (required by FluidSynth).
     *
     * Copies from assets: "sf2/default.sf2" -> filesDir/sf2/default.sf2
     *
     * This MUST be called off the audio thread.
     */
    fun ensureBundledDefaultSf2(context: Context): String? {
        val assetPath = "sf2/default.sf2"
        val outDir = File(context.filesDir, "sf2")
        if (!outDir.exists() && !outDir.mkdirs()) return null

        val outFile = File(outDir, "default.sf2")
        if (outFile.exists() && outFile.length() > 0L) {
            return outFile.absolutePath
        }

        // Copy asset to internal storage (filesystem path).
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(outFile).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                    }
                    output.flush()
                }
            }
        } catch (e: IOException) {
            // If copy fails, delete partial file so next run can retry cleanly.
            try { outFile.delete() } catch (_: Throwable) {}
            return null
        }

        return if (outFile.exists() && outFile.length() > 0L) outFile.absolutePath else null
    }

    /**
     * One-call setup: init FluidSynth + load bundled default SF2.
     * MUST be called off the audio thread.
     */
    fun initFluidSynthAndLoadBundledDefaultSf2(context: Context): Boolean {
        if (!isFluidSynthCompiled()) return false
        if (!initFluidSynth()) return false
        val path = ensureBundledDefaultSf2(context) ?: return false
        return loadSoundFontFromPath(path)
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
    private external fun nativeLoadSoundFont(handle: Long, path: String?): Boolean
    private external fun nativeInitFluidSynth(handle: Long): Boolean
    private external fun nativeShutdownFluidSynth(handle: Long): Boolean

    /** Returns whether FluidSynth support was compiled into the native library. */
    fun isFluidSynthCompiled(): Boolean {
        return nativeIsFluidSynthCompiled()
    }
    private external fun nativeIsFluidSynthCompiled(): Boolean
}

