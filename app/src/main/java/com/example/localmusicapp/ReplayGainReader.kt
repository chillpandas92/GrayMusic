package com.example.localmusicapp

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.Locale

/** Reads existing ReplayGain-style loudness tags from MP3/FLAC/OGG/WAV files. */
object ReplayGainReader {

    data class Gain(val trackGainDb: Float?, val albumGainDb: Float?) {
        fun hasValue(): Boolean = trackGainDb != null || albumGainDb != null
    }

    private val trackGainKeys = setOf(
        "replaygain_track_gain",
        "rg_track_gain",
        "track_gain",
        "r128_track_gain"
    )
    private val albumGainKeys = setOf(
        "replaygain_album_gain",
        "rg_album_gain",
        "album_gain",
        "r128_album_gain"
    )

    fun read(file: MusicScanner.MusicFile): ReplayGainStore.Entry? {
        val f = File(file.path)
        if (!f.exists() || f.length() <= 0L) return null
        val ext = file.format.ifBlank { f.extension }.lowercase(Locale.US)
        val gain = when (ext) {
            "mp3" -> readMp3Id3v2(f) ?: scanPlainTextTags(f)
            "flac" -> readFlacVorbisComment(f) ?: scanPlainTextTags(f)
            "ogg", "oga", "opus" -> scanPlainTextTags(f)
            "wav", "wave" -> scanPlainTextTags(f)
            else -> scanPlainTextTags(f)
        } ?: return null
        if (!gain.hasValue()) return null
        return ReplayGainStore.Entry(
            path = file.path,
            trackGainDb = gain.trackGainDb,
            albumGainDb = gain.albumGainDb,
            scannedAt = System.currentTimeMillis()
        )
    }

    private fun readMp3Id3v2(file: File): Gain? {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 10L) return@use null
                val header = ByteArray(10)
                raf.readFully(header)
                if (String(header, 0, 3, Charsets.ISO_8859_1) != "ID3") return@use null
                val major = header[3].toInt() and 0xFF
                if (major !in 2..4) return@use null
                val flags = header[5].toInt() and 0xFF
                val tagSize = synchsafeInt(header, 6).coerceAtMost(8 * 1024 * 1024)
                if (tagSize <= 0) return@use null
                var data = ByteArray(tagSize)
                raf.readFully(data)
                if ((flags and 0x80) != 0) data = removeUnsynchronization(data)
                parseId3Frames(data, major, flags)
            }
        }.getOrNull()
    }

    private fun parseId3Frames(data: ByteArray, major: Int, flags: Int): Gain? {
        var offset = skipId3ExtendedHeader(data, major, flags)
        var track: Float? = null
        var album: Float? = null

        while (offset < data.size) {
            if (major == 2) {
                if (offset + 6 > data.size) break
                val id = String(data, offset, 3, Charsets.ISO_8859_1)
                if (!id.all { it.isLetterOrDigit() } || id.all { it == '\u0000' }) break
                val size = int24(data, offset + 3)
                if (size <= 0 || offset + 6 + size > data.size) break
                val body = data.copyOfRange(offset + 6, offset + 6 + size)
                if (id == "TXX") {
                    val fields = parseUserTextFrame(body)
                    val applied = applyGainKey(fields.first, fields.second, track, album)
                    track = applied.first
                    album = applied.second
                }
                offset += 6 + size
            } else {
                if (offset + 10 > data.size) break
                val id = String(data, offset, 4, Charsets.ISO_8859_1)
                if (!id.all { it.isLetterOrDigit() } || id.all { it == '\u0000' }) break
                val size = if (major >= 4) synchsafeInt(data, offset + 4) else int32(data, offset + 4)
                if (size <= 0 || offset + 10 + size > data.size) break
                val body = data.copyOfRange(offset + 10, offset + 10 + size)
                when (id) {
                    "TXXX" -> {
                        val fields = parseUserTextFrame(body)
                        val applied = applyGainKey(fields.first, fields.second, track, album)
                        track = applied.first
                        album = applied.second
                    }
                    "RVA2" -> {
                        val rva = parseRva2Frame(body)
                        if (rva != null) {
                            if (rva.first) album = rva.second ?: album else track = rva.second ?: track
                        }
                    }
                }
                offset += 10 + size
            }
        }
        return if (track != null || album != null) Gain(track, album) else null
    }

    private fun skipId3ExtendedHeader(data: ByteArray, major: Int, flags: Int): Int {
        if ((flags and 0x40) == 0) return 0
        return runCatching {
            when (major) {
                3 -> {
                    if (data.size < 4) 0 else (4 + int32(data, 0)).coerceIn(0, data.size)
                }
                4 -> {
                    if (data.size < 4) 0 else synchsafeInt(data, 0).coerceIn(0, data.size)
                }
                else -> 0
            }
        }.getOrDefault(0)
    }

    private fun parseUserTextFrame(body: ByteArray): Pair<String, String> {
        if (body.isEmpty()) return "" to ""
        val encoding = body[0].toInt() and 0xFF
        val bytes = body.copyOfRange(1, body.size)
        val charset: Charset = when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charset.forName("UTF-16BE")
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        val decoded = runCatching { String(bytes, charset) }.getOrDefault("")
            .replace("\uFEFF", "")
            .trim('\u0000', ' ', '\n', '\r', '\t')
        val parts = decoded.split('\u0000')
        val description = parts.getOrNull(0).orEmpty().trim()
        val value = parts.drop(1).joinToString(" ").trim()
            .ifBlank { parts.drop(1).firstOrNull { it.isNotBlank() }.orEmpty().trim() }
        return description to value
    }

    /** Parses ID3 RVA2. The adjustment is a signed fixed-point dB value with 512 steps per dB. */
    private fun parseRva2Frame(body: ByteArray): Pair<Boolean, Float?>? {
        if (body.isEmpty()) return null
        val descEnd = body.indexOf(0)
        if (descEnd < 0 || descEnd + 4 > body.size) return null
        val description = String(body, 0, descEnd, Charsets.ISO_8859_1).lowercase(Locale.US)
        var offset = descEnd + 1
        while (offset + 4 <= body.size) {
            val channelType = body[offset].toInt() and 0xFF
            val raw = ((body[offset + 1].toInt() and 0xFF) shl 8) or (body[offset + 2].toInt() and 0xFF)
            val signed = if (raw and 0x8000 != 0) raw - 0x10000 else raw
            val gainDb = signed / 512f
            val peakBits = body[offset + 3].toInt() and 0xFF
            val peakBytes = ((peakBits + 7) / 8).coerceAtLeast(0)
            if (channelType == 1 || channelType == 2) {
                return (description.contains("album") || description.contains("audiophile")) to gainDb
            }
            offset += 4 + peakBytes
        }
        return null
    }

    private fun readFlacVorbisComment(file: File): Gain? {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 8L) return@use null
                val marker = ByteArray(4)
                raf.readFully(marker)
                if (String(marker, Charsets.ISO_8859_1) != "fLaC") return@use null
                var last = false
                while (!last && raf.filePointer + 4 <= raf.length()) {
                    val header = raf.readInt()
                    last = (header ushr 31) != 0
                    val type = (header ushr 24) and 0x7F
                    val length = header and 0xFFFFFF
                    if (length < 0 || length > 8 * 1024 * 1024) return@use null
                    if (type == 4) {
                        val data = ByteArray(length)
                        raf.readFully(data)
                        return@use parseVorbisCommentBlock(data)
                    } else {
                        raf.seek(raf.filePointer + length)
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun parseVorbisCommentBlock(data: ByteArray): Gain? {
        if (data.size < 8) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val vendorLength = buffer.int
        if (vendorLength < 0 || vendorLength > data.size - 8) return null
        buffer.position(buffer.position() + vendorLength)
        if (buffer.remaining() < 4) return null
        val count = buffer.int
        var track: Float? = null
        var album: Float? = null
        repeat(count.coerceAtMost(8192)) {
            if (buffer.remaining() < 4) return@repeat
            val len = buffer.int
            if (len < 0 || len > buffer.remaining()) return@repeat
            val bytes = ByteArray(len)
            buffer.get(bytes)
            val comment = String(bytes, Charsets.UTF_8)
            val eq = comment.indexOf('=')
            if (eq > 0) {
                val applied = applyGainKey(comment.substring(0, eq), comment.substring(eq + 1), track, album)
                track = applied.first
                album = applied.second
            }
        }
        return if (track != null || album != null) Gain(track, album) else null
    }

    private fun scanPlainTextTags(file: File): Gain? {
        return runCatching {
            val maxWindow = 1024 * 1024
            val parts = mutableListOf<ByteArray>()
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val first = ByteArray(kotlin.math.min(maxWindow.toLong(), len).toInt())
                raf.seek(0L)
                raf.readFully(first)
                parts.add(first)
                if (len > maxWindow) {
                    val secondSize = kotlin.math.min(maxWindow.toLong(), len).toInt()
                    val second = ByteArray(secondSize)
                    raf.seek((len - secondSize).coerceAtLeast(0L))
                    raf.readFully(second)
                    parts.add(second)
                }
            }
            val joined = ByteArray(parts.sumOf { it.size + 1 })
            var offset = 0
            for (part in parts) {
                System.arraycopy(part, 0, joined, offset, part.size)
                offset += part.size + 1
            }
            val candidates = listOf(
                String(joined, Charsets.ISO_8859_1),
                runCatching { String(joined, Charsets.UTF_8) }.getOrDefault("")
            )
            var track: Float? = null
            var album: Float? = null
            for (text in candidates) {
                for (key in trackGainKeys) track = findTextGain(text, key) ?: track
                for (key in albumGainKeys) album = findTextGain(text, key) ?: album
            }
            if (track != null || album != null) Gain(track, album) else null
        }.getOrNull()
    }

    private fun applyGainKey(keyRaw: String, valueRaw: String, currentTrack: Float?, currentAlbum: Float?): Pair<Float?, Float?> {
        val key = normalizeKey(keyRaw)
        val value = parseGainDb(valueRaw, r128 = key.startsWith("r128_"))
        var track = currentTrack
        var album = currentAlbum
        when (key) {
            in trackGainKeys -> track = value ?: track
            in albumGainKeys -> album = value ?: album
        }
        return track to album
    }

    private fun findTextGain(text: String, key: String): Float? {
        val keyPattern = key.split("_").joinToString("[_ -]*") { Regex.escape(it) }
        val pattern = Regex(
            keyPattern + "\\s*[=:]?\\s*([+-]?\\d+(?:\\.\\d+)?)\\s*(?:dB)?",
            RegexOption.IGNORE_CASE
        )
        val match = pattern.find(text) ?: return null
        val raw = match.groupValues.getOrNull(1) ?: return null
        val value = raw.toFloatOrNull() ?: return null
        return if (key.startsWith("r128_")) value / 256f else value
    }

    private fun parseGainDb(raw: String, r128: Boolean = false): Float? {
        val match = Regex("([+-]?\\d+(?:\\.\\d+)?)\\s*dB", RegexOption.IGNORE_CASE).find(raw)
            ?: Regex("([+-]?\\d+(?:\\.\\d+)?)").find(raw)
        val value = match?.groupValues?.getOrNull(1)?.toFloatOrNull() ?: return null
        return if (r128) value / 256f else value
    }

    private fun normalizeKey(raw: String): String {
        return raw.trim()
            .trim('\u0000', ' ', '\n', '\r', '\t')
            .lowercase(Locale.US)
            .replace('-', '_')
            .replace(' ', '_')
    }

    private fun removeUnsynchronization(data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var write = 0
        var i = 0
        while (i < data.size) {
            val b = data[i]
            out[write++] = b
            if ((b.toInt() and 0xFF) == 0xFF && i + 1 < data.size && data[i + 1].toInt() == 0x00) {
                i += 2
            } else {
                i++
            }
        }
        return out.copyOf(write)
    }

    private fun ByteArray.indexOf(value: Int): Int {
        for (i in indices) if ((this[i].toInt() and 0xFF) == value) return i
        return -1
    }

    private fun synchsafeInt(data: ByteArray, offset: Int): Int {
        if (offset + 3 >= data.size) return 0
        return ((data[offset].toInt() and 0x7F) shl 21) or
            ((data[offset + 1].toInt() and 0x7F) shl 14) or
            ((data[offset + 2].toInt() and 0x7F) shl 7) or
            (data[offset + 3].toInt() and 0x7F)
    }

    private fun int32(data: ByteArray, offset: Int): Int {
        if (offset + 3 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun int24(data: ByteArray, offset: Int): Int {
        if (offset + 2 >= data.size) return 0
        return ((data[offset].toInt() and 0xFF) shl 16) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            (data[offset + 2].toInt() and 0xFF)
    }
}
