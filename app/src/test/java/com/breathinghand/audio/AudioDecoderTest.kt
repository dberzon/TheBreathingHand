package com.breathinghand.audio

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class AudioDecoderTest {
    @Test
    fun buildWavHeaderProducesCorrectSizes() {
        val sampleRate = 44100
        val channels = 2
        val bits = 16
        val pcm = ByteArray(44100 * channels * (bits/8)) { 0 }

        val wav = AudioDecoder.buildWavFromPcm(pcm, sampleRate, channels, bits)
        // RIFF header
        assertEquals('R'.code.toByte(), wav[0])
        assertEquals('I'.code.toByte(), wav[1])
        assertEquals('F'.code.toByte(), wav[2])
        assertEquals('F'.code.toByte(), wav[3])

        // data size is at offset 40 (little-endian int)
        val dataSize = ByteBuffer.wrap(wav, 40, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        assertEquals(pcm.size, dataSize)

        // sample rate at offset 24
        val sr = ByteBuffer.wrap(wav, 24, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
        assertEquals(sampleRate, sr)

        // channels at offset 22 (little-endian short)
        val ch = ByteBuffer.wrap(wav, 22, 2).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt()
        assertEquals(channels, ch)
    }
}
