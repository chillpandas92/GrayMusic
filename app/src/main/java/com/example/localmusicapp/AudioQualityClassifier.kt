package com.example.localmusicapp

import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

object AudioQualityClassifier {
    data class Info(
        val badge: String?,
        val sampleRate: Int? = null,
        val bits: Int? = null,
        val bitrate: Int? = null
    )

    private data class StreamInfo(
        val sampleRate: Int?,
        val bits: Int?,
        val channels: Int?
    )

    private val cache = ConcurrentHashMap<String, Info>()

    fun classify(file: MusicScanner.MusicFile): Info {
        return cache.getOrPut(file.path) { read(file) }
    }

    fun cached(file: MusicScanner.MusicFile): Info? = cache[file.path]

    fun preload(files: List<MusicScanner.MusicFile>) {
        files.forEach { file ->
            if (!cache.containsKey(file.path)) {
                cache[file.path] = read(file)
            }
        }
    }

    private fun read(file: MusicScanner.MusicFile): Info {
        val ext = file.format.ifBlank { file.path.substringAfterLast('.', "") }.lowercase()
        val parsed = when (ext) {
            "flac" -> readFlacStreamInfo(file.path)
            "wav" -> readWavStreamInfo(file.path)
            else -> null
        }
        var extractor: MediaExtractor? = null
        return try {
            extractor = MediaExtractor().apply { setDataSource(file.path) }
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    format = f
                    break
                }
            }
            val sampleRate = parsed?.sampleRate
                ?: format?.takeIf { it.containsKey(MediaFormat.KEY_SAMPLE_RATE) }?.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val bitrate = format?.takeIf { it.containsKey(MediaFormat.KEY_BIT_RATE) }?.getInteger(MediaFormat.KEY_BIT_RATE)
            val bits = parsed?.bits ?: format?.let { inferBitsPerSample(it, ext) }
            Info(resolveBadge(ext, sampleRate, bits, bitrate), sampleRate, bits, bitrate)
        } catch (_: Exception) {
            val sampleRate = parsed?.sampleRate
            val bits = parsed?.bits
            Info(resolveBadge(ext, sampleRate, bits, null), sampleRate, bits, null)
        } finally {
            runCatching { extractor?.release() }
        }
    }

    private fun resolveBadge(ext: String, sampleRate: Int?, bits: Int?, bitrate: Int?): String? {
        val isLossless = ext in setOf("flac", "wav", "alac")
        val sr = sampleRate ?: 0
        val b = bits ?: 0
        return when {
            isLossless && sr >= 192_000 && b >= 24 -> "MQ"
            isLossless && sr >= 96_000 && b >= 24 -> "HR"
            isLossless && sr >= 44_100 && b >= 16 -> "SQ"
            !isLossless && bitrate != null && bitrate >= 320_000 -> "HQ"
            else -> null
        }
    }

    private fun inferBitsPerSample(format: MediaFormat, ext: String): Int? {
        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            return when (format.getInteger(MediaFormat.KEY_PCM_ENCODING)) {
                AudioFormat.ENCODING_PCM_8BIT -> 8
                AudioFormat.ENCODING_PCM_16BIT -> 16
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                AudioFormat.ENCODING_PCM_32BIT,
                AudioFormat.ENCODING_PCM_FLOAT -> 32
                else -> null
            }
        }
        return when (ext.lowercase()) {
            "wav", "flac", "alac" -> 16
            else -> null
        }
    }

    private fun readFlacStreamInfo(path: String): StreamInfo? {
        return runCatching {
            File(path).inputStream().use { input ->
                val header = ByteArray(4)
                if (!input.readFully(header) || String(header) != "fLaC") return@runCatching null
                while (true) {
                    val blockHeader = ByteArray(4)
                    if (!input.readFully(blockHeader)) return@runCatching null
                    val type = blockHeader[0].toInt() and 0x7F
                    val length = ((blockHeader[1].toInt() and 0xFF) shl 16) or
                        ((blockHeader[2].toInt() and 0xFF) shl 8) or
                        (blockHeader[3].toInt() and 0xFF)
                    val isLast = (blockHeader[0].toInt() and 0x80) != 0
                    if (type == 0 && length >= 34) {
                        val data = ByteArray(length)
                        if (!input.readFully(data)) return@runCatching null
                        return@runCatching parseFlacStreamInfoBlock(data)
                    }
                    var remaining = length.toLong()
                    while (remaining > 0L) {
                        val skipped = input.skip(remaining)
                        if (skipped <= 0L) return@runCatching null
                        remaining -= skipped
                    }
                    if (isLast) return@runCatching null
                }
                null
            }
        }.getOrNull()
    }

    private fun parseFlacStreamInfoBlock(data: ByteArray): StreamInfo? {
        if (data.size < 18) return null
        var packed = 0L
        for (i in 10..17) {
            packed = (packed shl 8) or (data[i].toLong() and 0xFFL)
        }
        val sampleRate = ((packed shr 44) and 0xFFFFF).toInt().takeIf { it > 0 }
        val channels = (((packed shr 41) and 0x7).toInt() + 1).takeIf { it > 0 }
        val bits = (((packed shr 36) and 0x1F).toInt() + 1).takeIf { it > 0 }
        return StreamInfo(sampleRate, bits, channels)
    }

    private fun readWavStreamInfo(path: String): StreamInfo? {
        return runCatching {
            File(path).inputStream().use { input ->
                val header = ByteArray(12)
                if (!input.readFully(header)) return@runCatching null
                if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "WAVE") return@runCatching null

                val chunkHeader = ByteArray(8)
                while (input.readFully(chunkHeader)) {
                    val id = String(chunkHeader, 0, 4)
                    val size = leInt(chunkHeader, 4)
                    if (size < 0) return@runCatching null

                    if (id == "fmt ") {
                        if (size < 16) return@runCatching null
                        val fmt = ByteArray(16)
                        if (!input.readFully(fmt)) return@runCatching null
                        val channels = leShort(fmt, 2)
                        val sampleRate = leInt(fmt, 4)
                        val bits = leShort(fmt, 14)
                        val remaining = size - 16
                        if (remaining > 0 && !input.skipFully(remaining.toLong())) return@runCatching null
                        if (size % 2 != 0 && !input.skipFully(1L)) return@runCatching null
                        return@runCatching StreamInfo(
                            sampleRate.takeIf { it > 0 },
                            bits.takeIf { it > 0 },
                            channels.takeIf { it > 0 }
                        )
                    }

                    val skipBytes = size.toLong() + (size % 2).toLong()
                    if (!input.skipFully(skipBytes)) return@runCatching null
                }
                null
            }
        }.getOrNull()
    }

    private fun InputStream.readFully(buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read <= 0) return false
            offset += read
        }
        return true
    }

    private fun InputStream.skipFully(count: Long): Boolean {
        var remaining = count
        while (remaining > 0L) {
            val skipped = skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                if (read() < 0) return false
                remaining--
            }
        }
        return true
    }

    private fun leShort(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun leInt(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }
}
