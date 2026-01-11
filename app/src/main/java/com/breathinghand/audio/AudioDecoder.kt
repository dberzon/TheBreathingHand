package com.breathinghand.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.min

private const val TAG = "AudioDecoder"

/**
 * Decode an audio Uri (OGG, MP3, AAC, etc.) to a valid WAV file byte array (PCM16 little-endian).
 * Returns null on failure.
 */
object AudioDecoder {
    suspend fun decodeToWavBytes(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)

            // Find first audio track
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                Log.w(TAG, "No audio track found for $uri")
                extractor.release()
                return@withContext null
            }

            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            Log.i(TAG, "Decoding: mime=$mime sr=$sampleRate ch=$channels")

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            extractor.selectTrack(trackIndex)

            val inputBuffers = codec.inputBuffers
            val outputBuffers = codec.outputBuffers
            val info = MediaCodec.BufferInfo()

            val pcmOut = ByteArrayOutputStream()
            var sawOutputEOS = false
            var sawInputEOS = false

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val dst = inputBuffers[inIndex]
                        val sampleSize = extractor.readSampleData(dst, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(inIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 10000)
                if (outIndex >= 0) {
                    val outBuf = outputBuffers[outIndex]
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        codec.releaseOutputBuffer(outIndex, false)
                        continue
                    }

                    if (info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)

                        val chunk = ByteArray(info.size)
                        outBuf.get(chunk)
                        pcmOut.write(chunk)
                    }

                    codec.releaseOutputBuffer(outIndex, false)

                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    // update reference
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    Log.i(TAG, "Output format changed: $newFormat")
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            val pcmBytes = pcmOut.toByteArray()
            if (pcmBytes.isEmpty()) {
                Log.w(TAG, "Decoded PCM is empty for $uri")
                return@withContext null
            }

            // Wrap PCM into a WAV (PCM 16-bit little-endian). The decoder commonly produces 16-bit PCM.
            val wav = buildWavFromPcm(pcmBytes, sampleRate, channels, 16)
            Log.i(TAG, "Decoded and built WAV: ${wav.size} bytes")
            return@withContext wav

        } catch (e: Exception) {
            Log.w(TAG, "Decode failed: ${e.message}")
            return@withContext null
        }
    }

    fun buildWavFromPcm(pcm: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val header = ByteArrayOutputStream()

        // RIFF header
        header.write("RIFF".toByteArray())
        header.write(intToLittleEndian(36 + dataSize)) // chunk size
        header.write("WAVE".toByteArray())

        // fmt chunk
        header.write("fmt ".toByteArray())
        header.write(intToLittleEndian(16)) // subchunk1 size
        header.write(shortToLittleEndian(1)) // PCM = 1
        header.write(shortToLittleEndian(channels.toShort()))
        header.write(intToLittleEndian(sampleRate))
        header.write(intToLittleEndian(byteRate))
        header.write(shortToLittleEndian(blockAlign.toShort()))
        header.write(shortToLittleEndian(bitsPerSample.toShort()))

        // data chunk
        header.write("data".toByteArray())
        header.write(intToLittleEndian(dataSize))
        header.write(pcm)

        return header.toByteArray()
    }

    private fun intToLittleEndian(v: Int): ByteArray {
        return byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
    }

    private fun shortToLittleEndian(v: Short): ByteArray {
        val iv = v.toInt()
        return byteArrayOf((iv and 0xFF).toByte(), ((iv shr 8) and 0xFF).toByte())
    }
}
