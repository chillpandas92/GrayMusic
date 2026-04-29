package com.example.localmusicapp

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Fallback ReplayGain scanner for files that do not carry ReplayGain tags.
 *
 * It decodes the audio track to PCM and estimates the required gain from full-track RMS.
 * This keeps FLAC/WAV/OGG/MP3 files without tags usable for volume normalization without
 * requiring an external native library.
 */
object ReplayGainAnalyzer {
    private const val TARGET_RMS_DBFS = -18.0
    private const val MAX_DECODE_US = 45L * 1_000_000L // short sampling cap to keep scans responsive

    fun analyze(file: MusicScanner.MusicFile): ReplayGainStore.Entry? {
        val source = File(file.path)
        if (!source.exists() || !source.isFile || source.length() <= 0L) return null

        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        return try {
            extractor = MediaExtractor().apply { setDataSource(source.absolutePath) }
            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) return null
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            if (!mime.startsWith("audio/")) return null

            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false
            var sumSquares = 0.0
            var sampleCount = 0L
            var lastSampleUs = 0L
            var tryAgainCount = 0
            val timeoutUs = 10_000L

            decodeLoop@ while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        inputBuffer?.clear()
                        val sampleSize = if (inputBuffer != null) extractor.readSampleData(inputBuffer, 0) else -1
                        val sampleTime = extractor.sampleTime
                        if (sampleSize < 0 || sampleTime > MAX_DECODE_US) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            lastSampleUs = sampleTime
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                when {
                    outputIndex >= 0 -> {
                        tryAgainCount = 0
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val pair = accumulatePcm(codec.outputFormat, outputBuffer)
                            sumSquares += pair.first
                            sampleCount += pair.second
                        }
                        sawOutputEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        tryAgainCount++
                        if ((sawInputEos && tryAgainCount > 40) || tryAgainCount > 200) break@decodeLoop
                        if (sawInputEos && lastSampleUs <= 0L) break@decodeLoop
                    }
                }
            }

            if (sampleCount <= 0L || sumSquares <= 0.0) return null
            val rms = sqrt(sumSquares / sampleCount.toDouble()).coerceAtLeast(1.0e-9)
            val currentDbfs = 20.0 * log10(rms)
            val gainDb = (TARGET_RMS_DBFS - currentDbfs).coerceIn(-18.0, 18.0).toFloat()

            ReplayGainStore.Entry(
                path = file.path,
                trackGainDb = gainDb,
                albumGainDb = null,
                scannedAt = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    private fun accumulatePcm(format: MediaFormat, buffer: ByteBuffer): Pair<Double, Long> {
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            android.media.AudioFormat.ENCODING_PCM_16BIT
        }

        return when (encoding) {
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> accumulateFloat(buffer)
            android.media.AudioFormat.ENCODING_PCM_8BIT -> accumulate8Bit(buffer)
            android.media.AudioFormat.ENCODING_PCM_16BIT -> accumulate16Bit(buffer)
            android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> accumulate24Bit(buffer)
            android.media.AudioFormat.ENCODING_PCM_32BIT -> accumulate32Bit(buffer)
            else -> accumulate16Bit(buffer)
        }
    }

    private fun accumulateFloat(buffer: ByteBuffer): Pair<Double, Long> {
        val local = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        var count = 0L
        while (local.remaining() >= 4) {
            val v = local.float.coerceIn(-1f, 1f).toDouble()
            sum += v * v
            count++
        }
        return sum to count
    }

    private fun accumulate8Bit(buffer: ByteBuffer): Pair<Double, Long> {
        val local = buffer.slice()
        var sum = 0.0
        var count = 0L
        while (local.hasRemaining()) {
            val v = ((local.get().toInt() and 0xFF) - 128) / 128.0
            sum += v * v
            count++
        }
        return sum to count
    }

    private fun accumulate16Bit(buffer: ByteBuffer): Pair<Double, Long> {
        val local = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        var count = 0L
        while (local.remaining() >= 2) {
            val v = local.short / 32768.0
            sum += v * v
            count++
        }
        return sum to count
    }

    private fun accumulate24Bit(buffer: ByteBuffer): Pair<Double, Long> {
        val local = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        var count = 0L
        while (local.remaining() >= 3) {
            val b0 = local.get().toInt() and 0xFF
            val b1 = local.get().toInt() and 0xFF
            val b2 = local.get().toInt()
            val raw = (b0 or (b1 shl 8) or (b2 shl 16))
            val signed = if (raw and 0x800000 != 0) raw or -0x1000000 else raw
            val v = signed / 8388608.0
            sum += v * v
            count++
        }
        return sum to count
    }

    private fun accumulate32Bit(buffer: ByteBuffer): Pair<Double, Long> {
        val local = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        var count = 0L
        while (local.remaining() >= 4) {
            val v = local.int / 2147483648.0
            sum += v * v
            count++
        }
        return sum to count
    }
}
