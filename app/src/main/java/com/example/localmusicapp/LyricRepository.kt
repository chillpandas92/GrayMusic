package com.example.localmusicapp

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.math.min

object LyricRepository {

    data class LyricWord(
        val startTimeMs: Long,
        val endTimeMs: Long,
        val startChar: Int,
        val endChar: Int
    )

    data class LyricLine(
        val timeMs: Long,
        val text: String,
        val sourceIndex: Int,
        val groupIndex: Int,
        val groupSize: Int,
        val isContinuationInGroup: Boolean,
        val endTimeMs: Long = -1L,
        val words: List<LyricWord> = emptyList()
    )

    data class LyricResult(
        val lines: List<LyricLine>,
        val isTimed: Boolean,
        val source: String
    ) {
        companion object {
            val EMPTY = LyricResult(emptyList(), isTimed = false, source = "")
        }
    }

    private data class RawLyricWord(
        val startTimeMs: Long,
        val startChar: Int,
        val endChar: Int,
        val explicitEndTimeMs: Long = -1L
    )

    private data class RawTimedLine(
        val timeMs: Long,
        val text: String,
        val sourceIndex: Int,
        val words: List<RawLyricWord> = emptyList()
    )

    private data class ParsedLyricPayload(
        val text: String,
        val words: List<RawLyricWord>
    )

    private data class ParsedText(
        val timedLines: List<RawTimedLine>,
        val plainLines: List<String>
    )

    private data class LyricCandidate(
        val source: String,
        val text: String = "",
        val timedLines: List<RawTimedLine> = emptyList()
    )

    fun load(context: Context, file: MusicScanner.MusicFile): LyricResult {
        return loadInternal(context, file)
    }

    fun load(file: MusicScanner.MusicFile): LyricResult {
        return loadInternal(null, file)
    }

    fun loadFromUri(context: Context, uri: Uri, source: String = "LRC文件"): LyricResult {
        val text = readLrcTextFromUri(context, uri) ?: return LyricResult.EMPTY
        return resultFromParsedText(source = source, text = text)
    }

    private fun loadInternal(context: Context?, file: MusicScanner.MusicFile): LyricResult {
        context?.let { ctx ->
            val selectedUri = LyricOverrideStore.get(ctx, file.path)
            if (selectedUri.isNotBlank()) {
                val selected = loadFromUri(ctx, Uri.parse(selectedUri), source = "LRC文件")
                if (selected.lines.isNotEmpty()) return selected
            }
        }

        // 严格按"内嵌歌词优先"的顺序走：用户没显式选 LRC 文件覆盖时，只要文件里
        // 内嵌着任意形式的歌词（带时间戳的逐行 / 逐字，或纯文本），就以内嵌为准；
        // 外挂 LRC 只在内嵌完全没有歌词时作为兜底。这样避免歌曲明明已经写了最新
        // 内嵌歌词，却被同目录下旧的 .lrc 文件挡住。
        val embeddedCandidates = readEmbeddedCandidatesForFile(context, file)
        val embeddedTimed = embeddedCandidates.asSequence()
            .mapNotNull { candidate ->
                val rawLines = if (candidate.timedLines.isNotEmpty()) {
                    candidate.timedLines
                } else {
                    parseLrcText(candidate.text).timedLines
                }
                if (rawLines.isNotEmpty()) {
                    LyricResult(
                        lines = normalizeTimedLines(rawLines),
                        isTimed = true,
                        source = candidate.source
                    )
                } else {
                    null
                }
            }
            .firstOrNull()

        if (embeddedTimed != null) return embeddedTimed

        // 内嵌纯文本歌词：在去找外挂 LRC 之前先用它，确保"最新内嵌"严格胜出。
        for (candidate in embeddedCandidates) {
            val parsed = parseLrcText(candidate.text)
            val plainLines = if (parsed.plainLines.isNotEmpty()) {
                parsed.plainLines
            } else {
                candidate.text.lineSequence()
                    .map { cleanPlainLine(it) }
                    .filter { it.isNotBlank() }
                    .toList()
            }
            if (plainLines.isNotEmpty()) {
                return LyricResult(
                    lines = normalizePlainLines(plainLines),
                    isTimed = false,
                    source = candidate.source
                )
            }
        }

        // 内嵌歌词完全为空时，才用外挂（同目录或扫描时记到 externalLrcUri 的）LRC。
        val external = when {
            context != null && file.externalLrcUri.isNotBlank() -> {
                readLrcTextFromUri(context, Uri.parse(file.externalLrcUri))?.let { text ->
                    CandidateResult(source = "外挂歌词", parsed = parseLrcText(text))
                }
            }
            // 扫描时如果没把同目录的 LRC 记到 externalLrcUri（比如 LRC 是后来才加的，
            // 或当时扫描走了快通道但 LRC 在别的子目录），这里运行时再去 SAF 查一次父目录，
            // 保证"同目录同名 LRC 一定能被自动检测到"。
            context != null && file.path.startsWith("content://", ignoreCase = true) -> {
                val foundUri = findExternalLrcUriDocument(context, file)
                if (foundUri.isNotBlank()) {
                    readLrcTextFromUri(context, Uri.parse(foundUri))?.let { text ->
                        CandidateResult(source = "外挂歌词", parsed = parseLrcText(text))
                    }
                } else null
            }
            else -> findExternalLrc(file)?.let { lrcFile ->
                val text = decodeText(lrcFile.readBytes())
                CandidateResult(source = "外挂歌词", parsed = parseLrcText(text))
            }
        }

        if (external?.parsed?.timedLines?.isNotEmpty() == true) {
            return LyricResult(
                lines = normalizeTimedLines(external.parsed.timedLines),
                isTimed = true,
                source = external.source
            )
        }

        if (external != null && external.parsed.plainLines.isNotEmpty()) {
            return LyricResult(
                lines = normalizePlainLines(external.parsed.plainLines),
                isTimed = false,
                source = external.source
            )
        }

        return LyricResult.EMPTY
    }

    private fun resultFromParsedText(source: String, text: String): LyricResult {
        val parsed = parseLrcText(text)
        if (parsed.timedLines.isNotEmpty()) {
            return LyricResult(
                lines = normalizeTimedLines(parsed.timedLines),
                isTimed = true,
                source = source
            )
        }
        val plainLines = if (parsed.plainLines.isNotEmpty()) {
            parsed.plainLines
        } else {
            text.lineSequence()
                .map { cleanPlainLine(it) }
                .filter { it.isNotBlank() }
                .toList()
        }
        return if (plainLines.isNotEmpty()) {
            LyricResult(
                lines = normalizePlainLines(plainLines),
                isTimed = false,
                source = source
            )
        } else {
            LyricResult.EMPTY
        }
    }

    private fun readLrcTextFromUri(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                decodeText(input.readBytes())
            }
        }.getOrNull()
    }

    fun activeIndexFor(lines: List<LyricLine>, positionMs: Long): Int {
        if (positionMs < 0L || lines.isEmpty()) return -1
        var activeIndex = -1
        for (i in lines.indices) {
            val line = lines[i]
            if (line.timeMs < 0L) continue
            if (line.timeMs <= positionMs) {
                activeIndex = i
            } else {
                break
            }
        }
        if (activeIndex < 0) return -1
        val activeTime = lines[activeIndex].timeMs
        var firstInGroup = activeIndex
        while (firstInGroup > 0 && lines[firstInGroup - 1].timeMs == activeTime) {
            firstInGroup--
        }
        return firstInGroup
    }

    /**
     * 返回当前位置处于 [timeMs, endTimeMs) 之间的所有行的索引。
     * 用于支持"时间重叠的多行同时高亮"（和声、叠唱等）。
     *
     * 注意：一行没有 endTimeMs（< 0）时，仍沿用旧的"直到下一组起始"语义，由
     * normalizeTimedLines 里回退逻辑保证。
     */
    fun activeIndicesFor(lines: List<LyricLine>, positionMs: Long): Set<Int> {
        if (positionMs < 0L || lines.isEmpty()) return emptySet()
        val result = HashSet<Int>()
        for (i in lines.indices) {
            val line = lines[i]
            if (line.timeMs < 0L) continue
            if (line.timeMs > positionMs) break
            val end = line.endTimeMs
            val inRange = if (end < 0L) {
                // 最后一行：没有结束时间，只要 start <= pos 就算活跃。
                true
            } else {
                positionMs < end
            }
            if (inRange) result.add(i)
        }
        return result
    }

    private data class CandidateResult(
        val source: String,
        val parsed: ParsedText
    )

    private fun findExternalLrc(file: MusicScanner.MusicFile): File? {
        if (file.path.startsWith("content://", ignoreCase = true)) return null
        val audioFile = File(file.path)
        val parent = audioFile.parentFile ?: return null
        val baseName = audioFile.nameWithoutExtension
        val titleName = file.title.trim()
        val candidates = try {
            parent.listFiles { child ->
                child.isFile && child.extension.equals("lrc", ignoreCase = true)
            }?.toList().orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        if (candidates.isEmpty()) return null

        return candidates.firstOrNull { it.nameWithoutExtension.equals(baseName, ignoreCase = true) }
            ?: candidates.firstOrNull { titleName.isNotBlank() && it.nameWithoutExtension.equals(titleName, ignoreCase = true) }
            ?: candidates.firstOrNull { it.nameWithoutExtension.contains(baseName, ignoreCase = true) }
    }

    /**
     * SAF 文档场景的同目录 LRC 兜底查询：
     *   - 输入：来自 SAF 树的歌曲 URI（content://.../tree/X/document/Y）
     *   - 做法：从 docId 推父 docId，用 DocumentsContract 查父目录的所有子项，
     *     取扩展名 == lrc 的，按 (完全匹配文件名 → 匹配歌曲标题 → 部分包含) 顺序选一个
     *   - 输出：找到时返回 LRC 的 SAF URI 字符串；找不到或无权限时返回空串
     *
     * 这条兜底只在歌曲是 content:// 且 externalLrcUri 没填的时候才走。
     * 大部分情况下扫描就已经把 LRC URI 写进 externalLrcUri，不会进到这里。
     */
    private fun findExternalLrcUriDocument(
        context: Context,
        file: MusicScanner.MusicFile
    ): String {
        val path = file.path
        if (!path.startsWith("content://", ignoreCase = true)) return ""
        val uri = runCatching { Uri.parse(path) }.getOrNull() ?: return ""
        val authority = uri.authority ?: return ""

        val treeDocId = runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull() ?: return ""
        val docId = runCatching {
            android.provider.DocumentsContract.getDocumentId(uri)
        }.getOrNull() ?: return ""

        // 父 docId：用最后一个 '/' 切。primary:Music/Sub/song.mp3 -> primary:Music/Sub
        // 没有 '/' 的（已经在 root）就保持自身，让 buildChildDocumentsUriUsingTree 当 root 处理。
        val sep = docId.lastIndexOf('/')
        val parentDocId = if (sep > 0) docId.substring(0, sep) else docId

        val treeUri = android.provider.DocumentsContract.buildTreeDocumentUri(authority, treeDocId)
        val parentChildrenUri = android.provider.DocumentsContract
            .buildChildDocumentsUriUsingTree(treeUri, parentDocId)

        // 拼匹配用的两个 base name：歌曲文件名（去扩展名）+ 用户看到的标题（兜底场景）。
        val fileNameFromDoc = docId.substringAfterLast('/')
        val baseName = fileNameFromDoc.substringBeforeLast('.', fileNameFromDoc).lowercase()
        val titleName = file.title.trim().lowercase()

        val projection = arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        return runCatching {
            context.contentResolver.query(parentChildrenUri, projection, null, null, null)
                ?.use { cursor ->
                    var exactMatch: String? = null
                    var titleMatch: String? = null
                    var partialMatch: String? = null
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                        if (!name.endsWith(".lrc", ignoreCase = true)) continue
                        val foundDocId = cursor.getString(0)?.takeIf { it.isNotBlank() }
                            ?: continue
                        val lrcBase = name.substringBeforeLast('.', name).lowercase()
                        val lrcUri = android.provider.DocumentsContract
                            .buildDocumentUriUsingTree(treeUri, foundDocId).toString()
                        when {
                            lrcBase == baseName -> {
                                exactMatch = lrcUri
                            }
                            titleName.isNotBlank() && lrcBase == titleName -> {
                                if (titleMatch == null) titleMatch = lrcUri
                            }
                            baseName.isNotEmpty() && lrcBase.contains(baseName) -> {
                                if (partialMatch == null) partialMatch = lrcUri
                            }
                        }
                        if (exactMatch != null) break
                    }
                    exactMatch ?: titleMatch ?: partialMatch
                }
        }.getOrNull() ?: ""
    }

    private fun readEmbeddedCandidatesForFile(
        context: Context?,
        file: MusicScanner.MusicFile
    ): List<LyricCandidate> {
        if (!file.path.startsWith("content://", ignoreCase = true)) {
            return readEmbeddedCandidates(file.path)
        }
        if (context == null) return emptyList()

        val ext = file.format.trim().lowercase().ifBlank { "audio" }
        val temp = try {
            File.createTempFile("gray_music_lyrics_", ".$ext", context.cacheDir)
        } catch (_: Exception) {
            return emptyList()
        }

        return try {
            val uri = Uri.parse(file.path)
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output ->
                    input.copyTo(output)
                }
                true
            } ?: false
            if (!copied || temp.length() <= 0L) emptyList() else readEmbeddedCandidates(temp.absolutePath)
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { temp.delete() }
        }
    }

    private fun parseLrcText(text: String): ParsedText {
        if (text.isBlank()) return ParsedText(emptyList(), emptyList())
        val timed = mutableListOf<RawTimedLine>()
        val plain = mutableListOf<String>()
        var offsetMs = 0L
        var sourceIndex = 0
        val timeTagRegex = Regex("\\[((?:\\d{1,3}:)?\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?)\\]")
        val metadataOnlyRegex = Regex("^\\[[a-zA-Z][a-zA-Z0-9_ -]*:.*]$")
        val offsetRegex = Regex("^\\[offset:([+-]?\\d+)]$", RegexOption.IGNORE_CASE)

        text.replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .forEach { rawLine ->
                val line = rawLine.replace('\uFEFF', ' ').trim()
                if (line.isBlank()) return@forEach
                val offsetMatch = offsetRegex.matchEntire(line)
                if (offsetMatch != null) {
                    offsetMs = offsetMatch.groupValues.getOrNull(1)?.toLongOrNull() ?: 0L
                    return@forEach
                }

                val leadingMatches = leadingSquareTimeTags(line, timeTagRegex)
                if (leadingMatches.isNotEmpty()) {
                    val payloadAfterAllLeading = line.substring(leadingMatches.last().range.last + 1)
                    val bracketWordTagsRemain = timeTagRegex.containsMatchIn(payloadAfterAllLeading)
                    val lineTimeMatches = if (leadingMatches.size > 1 && bracketWordTagsRemain) {
                        leadingMatches.take(1)
                    } else {
                        leadingMatches
                    }
                    val payload = line.substring(lineTimeMatches.last().range.last + 1)

                    lineTimeMatches.forEach { match ->
                        val lineTimeWithoutOffset = parseLrcTime(match.groupValues[1])
                        if (lineTimeWithoutOffset != null) {
                            val lineTimeMs = (lineTimeWithoutOffset + offsetMs).coerceAtLeast(0L)
                            val parsedPayload = parseLyricPayload(
                                raw = payload,
                                lineTimeWithoutOffsetMs = lineTimeWithoutOffset,
                                lineTimeMs = lineTimeMs,
                                offsetMs = offsetMs
                            )
                            if (parsedPayload.text.isNotBlank()) {
                                timed.add(
                                    RawTimedLine(
                                        timeMs = lineTimeMs,
                                        text = parsedPayload.text,
                                        sourceIndex = sourceIndex++,
                                        words = parsedPayload.words
                                    )
                                )
                            }
                        }
                    }
                } else if (!metadataOnlyRegex.matches(line)) {
                    val plainText = cleanPlainLine(line)
                    if (plainText.isNotBlank()) plain.add(plainText)
                }
            }
        return ParsedText(timed, plain)
    }

    private fun leadingSquareTimeTags(line: String, timeTagRegex: Regex): List<MatchResult> {
        val matches = mutableListOf<MatchResult>()
        var index = 0
        while (index < line.length) {
            while (index < line.length && line[index].isWhitespace()) index++
            val match = timeTagRegex.find(line, index)
            if (match == null || match.range.first != index) break
            matches.add(match)
            index = match.range.last + 1
        }
        return matches
    }

    private fun parseLyricPayload(
        raw: String,
        lineTimeWithoutOffsetMs: Long,
        lineTimeMs: Long,
        offsetMs: Long
    ): ParsedLyricPayload {
        val normalized = raw.replace('\uFEFF', ' ')
        parseEnhancedWordPayload(
            normalized = normalized,
            lineTimeWithoutOffsetMs = lineTimeWithoutOffsetMs,
            lineTimeMs = lineTimeMs,
            offsetMs = offsetMs
        )?.let { return it }
        parseBracketWordPayload(
            normalized = normalized,
            lineTimeWithoutOffsetMs = lineTimeWithoutOffsetMs,
            lineTimeMs = lineTimeMs,
            offsetMs = offsetMs
        )?.let { return it }
        return ParsedLyricPayload(cleanLyricText(normalized), emptyList())
    }

    private fun parseEnhancedWordPayload(
        normalized: String,
        lineTimeWithoutOffsetMs: Long,
        lineTimeMs: Long,
        offsetMs: Long
    ): ParsedLyricPayload? {
        val wordTagRegex = Regex("<((?:\\d{1,3}:)?\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?)>")
        val matches = wordTagRegex.findAll(normalized).toList()
        if (matches.isEmpty()) return null

        val rawTimes = matches.mapNotNull { parseLrcTime(it.groupValues[1]) }
        val useRelativeWordTimes = shouldUseRelativeWordTimes(rawTimes, lineTimeWithoutOffsetMs)
        val textBuilder = StringBuilder()
        val rawWords = mutableListOf<RawLyricWord>()

        fun resolved(rawTimeMs: Long): Long {
            return resolveWordTimeMs(
                rawWordTimeMs = rawTimeMs,
                useRelativeWordTimes = useRelativeWordTimes,
                lineTimeMs = lineTimeMs,
                offsetMs = offsetMs
            )
        }

        val firstRawTime = parseLrcTime(matches.first().groupValues[1])
        appendRawWordSegment(
            textBuilder = textBuilder,
            rawWords = rawWords,
            segment = normalized.substring(0, matches.first().range.first),
            startTimeMs = lineTimeMs,
            explicitEndTimeMs = firstRawTime?.let { resolved(it) } ?: -1L
        )

        matches.forEachIndexed { index, match ->
            val rawStartTimeMs = parseLrcTime(match.groupValues[1]) ?: return@forEachIndexed
            val nextRawStartTimeMs = matches.getOrNull(index + 1)?.groupValues?.getOrNull(1)?.let { parseLrcTime(it) }
            val segmentStart = match.range.last + 1
            val segmentEnd = matches.getOrNull(index + 1)?.range?.first ?: normalized.length
            if (segmentStart > segmentEnd || segmentStart > normalized.length) return@forEachIndexed
            appendRawWordSegment(
                textBuilder = textBuilder,
                rawWords = rawWords,
                segment = normalized.substring(segmentStart, segmentEnd.coerceAtMost(normalized.length)),
                startTimeMs = resolved(rawStartTimeMs),
                explicitEndTimeMs = nextRawStartTimeMs?.let { resolved(it) } ?: -1L
            )
        }

        return trimParsedPayload(textBuilder.toString(), rawWords)
    }

    private fun parseBracketWordPayload(
        normalized: String,
        lineTimeWithoutOffsetMs: Long,
        lineTimeMs: Long,
        offsetMs: Long
    ): ParsedLyricPayload? {
        val wordTagRegex = Regex("\\[((?:\\d{1,3}:)?\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?)\\]")
        val matches = wordTagRegex.findAll(normalized).toList()
        if (matches.isEmpty()) return null

        val rawTimes = matches.mapNotNull { parseLrcTime(it.groupValues[1]) }
        val useRelativeWordTimes = shouldUseRelativeWordTimes(rawTimes, lineTimeWithoutOffsetMs)
        val textBuilder = StringBuilder()
        val rawWords = mutableListOf<RawLyricWord>()
        var currentStartTimeMs = lineTimeMs
        var segmentStart = 0

        matches.forEach { match ->
            val rawTagTimeMs = parseLrcTime(match.groupValues[1]) ?: return@forEach
            val resolvedTagTimeMs = resolveWordTimeMs(
                rawWordTimeMs = rawTagTimeMs,
                useRelativeWordTimes = useRelativeWordTimes,
                lineTimeMs = lineTimeMs,
                offsetMs = offsetMs
            )
            if (segmentStart <= match.range.first) {
                appendRawWordSegment(
                    textBuilder = textBuilder,
                    rawWords = rawWords,
                    segment = normalized.substring(segmentStart, match.range.first),
                    startTimeMs = currentStartTimeMs,
                    explicitEndTimeMs = resolvedTagTimeMs
                )
            }
            currentStartTimeMs = resolvedTagTimeMs
            segmentStart = match.range.last + 1
        }

        if (segmentStart <= normalized.length) {
            appendRawWordSegment(
                textBuilder = textBuilder,
                rawWords = rawWords,
                segment = normalized.substring(segmentStart),
                startTimeMs = currentStartTimeMs,
                explicitEndTimeMs = -1L
            )
        }

        return trimParsedPayload(textBuilder.toString(), rawWords)
    }

    private fun appendRawWordSegment(
        textBuilder: StringBuilder,
        rawWords: MutableList<RawLyricWord>,
        segment: String,
        startTimeMs: Long,
        explicitEndTimeMs: Long
    ) {
        val startChar = textBuilder.length
        textBuilder.append(segment)
        val endChar = textBuilder.length
        if (segment.isNotBlank() && startChar < endChar) {
            rawWords.add(
                RawLyricWord(
                    startTimeMs = startTimeMs.coerceAtLeast(0L),
                    startChar = startChar,
                    endChar = endChar,
                    explicitEndTimeMs = explicitEndTimeMs.coerceAtLeast(-1L)
                )
            )
        }
    }

    private fun shouldUseRelativeWordTimes(rawTimes: List<Long>, lineTimeWithoutOffsetMs: Long): Boolean {
        if (rawTimes.isEmpty()) return false
        val minTimeMs = rawTimes.minOrNull() ?: return false
        val maxTimeMs = rawTimes.maxOrNull() ?: return false
        return maxTimeMs < lineTimeWithoutOffsetMs || minTimeMs + 1_000L <= lineTimeWithoutOffsetMs
    }

    private fun resolveWordTimeMs(
        rawWordTimeMs: Long,
        useRelativeWordTimes: Boolean,
        lineTimeMs: Long,
        offsetMs: Long
    ): Long {
        val resolved = if (useRelativeWordTimes) {
            lineTimeMs + rawWordTimeMs
        } else {
            rawWordTimeMs + offsetMs
        }
        return resolved.coerceAtLeast(0L)
    }

    private fun trimParsedPayload(text: String, words: List<RawLyricWord>): ParsedLyricPayload {
        val first = text.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return ParsedLyricPayload("", emptyList())
        val lastExclusive = text.indexOfLast { !it.isWhitespace() } + 1
        val trimmedText = text.substring(first, lastExclusive)
        val adjustedWords = words.mapNotNull { word ->
            val startChar = (word.startChar - first).coerceAtLeast(0)
            val endChar = (word.endChar - first).coerceAtMost(trimmedText.length)
            if (startChar < endChar && trimmedText.substring(startChar, endChar).isNotBlank()) {
                RawLyricWord(word.startTimeMs, startChar, endChar, word.explicitEndTimeMs)
            } else {
                null
            }
        }
        return ParsedLyricPayload(trimmedText, adjustedWords)
    }

    private fun parseLrcTime(raw: String): Long? {
        val pieces = raw.split(':')
        if (pieces.size !in 2..3) return null
        val hours: Long
        val minutes: Long
        val secondPart: String
        if (pieces.size == 3) {
            hours = pieces[0].toLongOrNull() ?: return null
            minutes = pieces[1].toLongOrNull() ?: return null
            secondPart = pieces[2]
        } else {
            hours = 0L
            minutes = pieces[0].toLongOrNull() ?: return null
            secondPart = pieces[1]
        }
        val normalizedSecond = secondPart.replace(':', '.')
        val secPieces = normalizedSecond.split('.', limit = 2)
        val seconds = secPieces.getOrNull(0)?.toLongOrNull() ?: return null
        val fraction = secPieces.getOrNull(1).orEmpty()
        val millis = when {
            fraction.isEmpty() -> 0L
            fraction.length == 1 -> (fraction.toLongOrNull() ?: 0L) * 100L
            fraction.length == 2 -> (fraction.toLongOrNull() ?: 0L) * 10L
            else -> fraction.take(3).toLongOrNull() ?: 0L
        }
        return hours * 3_600_000L + minutes * 60_000L + seconds * 1_000L + millis
    }

    private fun cleanLyricText(raw: String): String {
        return raw.replace('\uFEFF', ' ')
            .replace(Regex("<(?:\\d{1,3}:)?\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?>"), "")
            .replace(Regex("\\[(?:\\d{1,3}:)?\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?\\]"), "")
            .trim()
    }

    private fun cleanPlainLine(raw: String): String {
        val line = cleanLyricText(raw)
        if (line.matches(Regex("^\\[[a-zA-Z][a-zA-Z0-9_ -]*:.*]$"))) return ""
        return line
    }

    private fun normalizeTimedLines(rawLines: List<RawTimedLine>): List<LyricLine> {
        if (rawLines.isEmpty()) return emptyList()
        val sorted = rawLines
            .filter { it.text.isNotBlank() }
            .sortedWith(compareBy<RawTimedLine> { it.timeMs }.thenBy { it.sourceIndex })
        val result = ArrayList<LyricLine>(sorted.size)
        var index = 0
        while (index < sorted.size) {
            val time = sorted[index].timeMs
            val groupStart = index
            while (index < sorted.size && sorted[index].timeMs == time) index++
            val groupSize = index - groupStart
            val nextTimeMs = sorted.getOrNull(index)?.timeMs ?: -1L
            for (groupIndex in 0 until groupSize) {
                val raw = sorted[groupStart + groupIndex]
                val displayText = truncateLyricLine(raw.text)
                val normalizedWords = normalizeWords(
                    rawWords = raw.words,
                    textLength = displayText.length,
                    lineEndTimeMs = nextTimeMs
                )
                // 每行独立的 endTimeMs：如果这行的逐字时间延伸到下一组之后（比如和声、
                // 叠唱），就用自己最后一个字的 endTimeMs；否则退回到下一组起始时间。
                // 没有 word 级时间信息时退回到 nextTimeMs（之前的行为）。
                val ownEnd = normalizedWords.maxOfOrNull { it.endTimeMs } ?: -1L
                val effectiveEnd = when {
                    nextTimeMs < 0L -> ownEnd
                    ownEnd < 0L -> nextTimeMs
                    ownEnd > nextTimeMs -> ownEnd
                    else -> nextTimeMs
                }
                result.add(
                    LyricLine(
                        timeMs = raw.timeMs,
                        text = displayText,
                        sourceIndex = raw.sourceIndex,
                        groupIndex = groupIndex,
                        groupSize = groupSize,
                        isContinuationInGroup = groupIndex > 0,
                        endTimeMs = effectiveEnd,
                        words = normalizedWords
                    )
                )
            }
        }
        return result
    }

    private fun normalizeWords(
        rawWords: List<RawLyricWord>,
        textLength: Int,
        lineEndTimeMs: Long
    ): List<LyricWord> {
        if (rawWords.isEmpty() || textLength <= 0) return emptyList()
        val clippedWords = rawWords.mapNotNull { word ->
            val startChar = word.startChar.coerceIn(0, textLength)
            val endChar = word.endChar.coerceIn(0, textLength)
            if (startChar < endChar) {
                RawLyricWord(word.startTimeMs, startChar, endChar, word.explicitEndTimeMs)
            } else {
                null
            }
        }.sortedWith(compareBy<RawLyricWord> { it.startTimeMs }.thenBy { it.startChar })

        return clippedWords.mapIndexed { index, word ->
            val nextStartTimeMs = clippedWords.getOrNull(index + 1)?.startTimeMs
            val previousStartTimeMs = clippedWords.getOrNull(index - 1)?.startTimeMs
            val estimatedDurationMs = previousStartTimeMs
                ?.let { (word.startTimeMs - it).coerceIn(180L, 1_200L) }
                ?: 620L
            val fallbackEndTimeMs = if (lineEndTimeMs > word.startTimeMs) {
                minOf(lineEndTimeMs, word.startTimeMs + estimatedDurationMs)
            } else {
                word.startTimeMs + estimatedDurationMs
            }
            val explicitEndTimeMs = word.explicitEndTimeMs.takeIf { it > word.startTimeMs }
            val exactEndTimeMs = explicitEndTimeMs ?: nextStartTimeMs
            val resolvedEndTimeMs = exactEndTimeMs
                ?.coerceAtLeast(word.startTimeMs + 1L)
                ?: fallbackEndTimeMs.coerceAtLeast(word.startTimeMs + 24L)
            LyricWord(
                startTimeMs = word.startTimeMs,
                endTimeMs = resolvedEndTimeMs,
                startChar = word.startChar,
                endChar = word.endChar
            )
        }
    }

    private fun normalizePlainLines(lines: List<String>): List<LyricLine> {
        return lines.mapIndexedNotNull { index, line ->
            val text = truncateLyricLine(line.trim())
            if (text.isBlank()) {
                null
            } else {
                LyricLine(
                    timeMs = -1L,
                    text = text,
                    sourceIndex = index,
                    groupIndex = 0,
                    groupSize = 1,
                    isContinuationInGroup = false
                )
            }
        }
    }

    private fun truncateLyricLine(raw: String): String {
        // 不在数据层提前截断歌词。是否换行应由 TextView 根据实际可用宽度决定，
        // 这样一行能放下就保持一行，放不下才自然换到第二行或后续行。
        return raw.trim()
    }

    private fun readEmbeddedCandidates(path: String): List<LyricCandidate> {
        val ext = File(path).extension.lowercase()
        val candidates = mutableListOf<LyricCandidate>()
        if (ext == "mp3") {
            candidates.addAll(readId3Lyrics(path))
        }
        if (ext == "m4a" || ext == "mp4" || ext == "aac") {
            candidates.addAll(readMp4Lyrics(path))
        }
        if (ext == "flac") {
            candidates.addAll(readFlacVorbisLyrics(path))
        }
        if (candidates.isEmpty()) {
            candidates.addAll(readId3Lyrics(path))
            candidates.addAll(readMp4Lyrics(path))
            candidates.addAll(readFlacVorbisLyrics(path))
        }
        return candidates.distinctBy { it.source + "\u0001" + it.text + "\u0001" + it.timedLines.joinToString("|") }
    }

    private fun readId3Lyrics(path: String): List<LyricCandidate> {
        val file = File(path)
        if (!file.exists() || file.length() < 10) return emptyList()
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(10)
                if (input.read(header) != 10) return emptyList()
                if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
                    return emptyList()
                }
                val version = header[3].toInt() and 0xFF
                val flags = header[5].toInt() and 0xFF
                val tagSize = syncSafeInt(header, 6).coerceAtMost(8 * 1024 * 1024)
                if (tagSize <= 0) return emptyList()
                var body = input.readBytesLimited(tagSize)
                if ((flags and 0x80) != 0) body = removeUnsynchronisation(body)
                val startOffset = id3FrameStartOffset(body, version, flags)
                val candidates = mutableListOf<LyricCandidate>()
                var pos = startOffset
                if (version == 2) {
                    while (pos + 6 <= body.size) {
                        val frameId = String(body, pos, 3, StandardCharsets.ISO_8859_1)
                        if (frameId.all { it.code == 0 }) break
                        if (!frameId.all { it in 'A'..'Z' || it in '0'..'9' }) break
                        val frameSize = int24(body, pos + 3)
                        if (frameSize <= 0 || pos + 6 + frameSize > body.size) break
                        val content = body.copyOfRange(pos + 6, pos + 6 + frameSize)
                        when (frameId) {
                            "ULT" -> decodeUsltFrame(content)?.let {
                                candidates.add(LyricCandidate(source = "内嵌ID3歌词", text = it))
                            }
                            "SLT" -> decodeSyltFrame(content)?.takeIf { it.isNotEmpty() }?.let {
                                candidates.add(LyricCandidate(source = "内嵌ID3同步歌词", timedLines = it))
                            }
                            "TXX" -> decodeTxxxLyricsFrame(content)?.let {
                                candidates.add(LyricCandidate(source = "内嵌ID3歌词", text = it))
                            }
                        }
                        pos += 6 + frameSize
                    }
                } else {
                    while (pos + 10 <= body.size) {
                        val frameId = String(body, pos, 4, StandardCharsets.ISO_8859_1)
                        if (frameId.all { it.code == 0 }) break
                        if (!frameId.all { it in 'A'..'Z' || it in '0'..'9' }) break
                        val frameSize = if (version >= 4) syncSafeInt(body, pos + 4) else int32(body, pos + 4)
                        if (frameSize <= 0 || pos + 10 + frameSize > body.size) break
                        val content = body.copyOfRange(pos + 10, pos + 10 + frameSize)
                        when (frameId) {
                            "USLT" -> decodeUsltFrame(content)?.let {
                                candidates.add(LyricCandidate(source = "内嵌ID3歌词", text = it))
                            }
                            "SYLT" -> decodeSyltFrame(content)?.takeIf { it.isNotEmpty() }?.let {
                                candidates.add(LyricCandidate(source = "内嵌ID3同步歌词", timedLines = it))
                            }
                            "TXXX" -> decodeTxxxLyricsFrame(content)?.let {
                                candidates.add(LyricCandidate(source = "内嵌ID3歌词", text = it))
                            }
                        }
                        pos += 10 + frameSize
                    }
                }
                candidates
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun id3FrameStartOffset(body: ByteArray, version: Int, flags: Int): Int {
        if ((flags and 0x40) == 0 || body.size < 4) return 0
        return try {
            if (version >= 4) {
                syncSafeInt(body, 0).coerceAtLeast(4).coerceAtMost(body.size)
            } else {
                (int32(body, 0) + 4).coerceAtMost(body.size)
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun decodeUsltFrame(content: ByteArray): String? {
        if (content.size < 5) return null
        val encoding = content[0].toInt() and 0xFF
        var pos = 4
        val descEnd = findTextTerminator(content, pos, encoding)
        if (descEnd < 0) return null
        pos = descEnd + terminatorLength(encoding)
        if (pos >= content.size) return null
        return decodeId3Text(content.copyOfRange(pos, content.size), encoding).trim().takeIf { it.isNotBlank() }
    }

    private fun decodeTxxxLyricsFrame(content: ByteArray): String? {
        if (content.size < 2) return null
        val encoding = content[0].toInt() and 0xFF
        var pos = 1
        val descEnd = findTextTerminator(content, pos, encoding)
        if (descEnd < 0) return null
        val description = decodeId3Text(content.copyOfRange(pos, descEnd), encoding)
        pos = descEnd + terminatorLength(encoding)
        if (pos >= content.size) return null
        val value = decodeId3Text(content.copyOfRange(pos, content.size), encoding).trim()
        val key = description.lowercase()
        return if (value.isNotBlank() && (key.contains("lyric") || key.contains("lrc") || key.contains("sync") || key.contains("歌词"))) {
            value
        } else {
            null
        }
    }

    private fun decodeSyltFrame(content: ByteArray): List<RawTimedLine>? {
        if (content.size < 7) return null
        val encoding = content[0].toInt() and 0xFF
        val timestampFormat = content[4].toInt() and 0xFF
        if (timestampFormat != 2) return null
        var pos = 6
        val descEnd = findTextTerminator(content, pos, encoding)
        if (descEnd < 0) return null
        pos = descEnd + terminatorLength(encoding)
        val result = mutableListOf<RawTimedLine>()
        var sourceIndex = 0
        while (pos < content.size - 4) {
            val textEnd = findTextTerminator(content, pos, encoding)
            if (textEnd < 0 || textEnd + terminatorLength(encoding) + 4 > content.size) break
            val text = decodeId3Text(content.copyOfRange(pos, textEnd), encoding).trim()
            pos = textEnd + terminatorLength(encoding)
            val timestamp = uint32(content, pos)
            pos += 4
            if (text.isNotBlank()) {
                result.add(RawTimedLine(timestamp, text, sourceIndex++))
            }
        }
        return result
    }

    private fun findTextTerminator(bytes: ByteArray, start: Int, encoding: Int): Int {
        if (start >= bytes.size) return -1
        return if (terminatorLength(encoding) == 2) {
            var i = start
            while (i + 1 < bytes.size) {
                if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0) return i
                i += 2
            }
            -1
        } else {
            var i = start
            while (i < bytes.size) {
                if (bytes[i].toInt() == 0) return i
                i++
            }
            -1
        }
    }

    private fun terminatorLength(encoding: Int): Int = if (encoding == 1 || encoding == 2) 2 else 1

    private fun decodeId3Text(bytes: ByteArray, encoding: Int): String {
        if (bytes.isEmpty()) return ""
        val decoded = when (encoding) {
            1 -> String(bytes, Charsets.UTF_16)
            2 -> String(bytes, Charsets.UTF_16BE)
            3 -> String(bytes, Charsets.UTF_8)
            else -> decodePossiblyUtf8OrLatin1(bytes)
        }
        return decoded.trimEnd('\u0000').replace("\u0000", "")
    }

    private fun readMp4Lyrics(path: String): List<LyricCandidate> {
        return try {
            RandomAccessFile(path, "r").use { raf ->
                val candidates = mutableListOf<LyricCandidate>()
                parseMp4Atoms(
                    raf = raf,
                    start = 0L,
                    end = raf.length(),
                    depth = 0,
                    inLyricsAtom = false,
                    candidates = candidates
                )
                candidates
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseMp4Atoms(
        raf: RandomAccessFile,
        start: Long,
        end: Long,
        depth: Int,
        inLyricsAtom: Boolean,
        candidates: MutableList<LyricCandidate>
    ) {
        if (depth > 8 || start < 0 || end <= start) return
        var pos = start
        while (pos + 8 <= end) {
            raf.seek(pos)
            val size32 = raf.readUnsignedInt()
            val typeBytes = ByteArray(4)
            raf.readFully(typeBytes)
            val type = String(typeBytes, StandardCharsets.ISO_8859_1)
            var headerSize = 8L
            val atomSize = when (size32) {
                0L -> end - pos
                1L -> {
                    headerSize = 16L
                    raf.readLong()
                }
                else -> size32
            }
            if (atomSize < headerSize || pos + atomSize > end) break
            var payloadStart = pos + headerSize
            val payloadEnd = pos + atomSize
            val isLyricsAtom = inLyricsAtom || isMp4LyricsAtom(typeBytes)

            when {
                type == "data" && inLyricsAtom -> {
                    val payload = readMp4DataPayload(raf, payloadStart, payloadEnd)
                    val text = decodeText(payload).trim()
                    if (text.isNotBlank()) {
                        candidates.add(LyricCandidate(source = "内嵌M4A歌词", text = text))
                    }
                }
                type == "----" -> {
                    readMp4FreeformLyrics(raf, payloadStart, payloadEnd)?.let { text ->
                        if (text.isNotBlank()) {
                            candidates.add(LyricCandidate(source = "内嵌M4A歌词", text = text))
                        }
                    }
                }
                isMp4Container(type) || isLyricsAtom -> {
                    if (type == "meta" && payloadStart + 4 <= payloadEnd) {
                        payloadStart += 4
                    }
                    parseMp4Atoms(raf, payloadStart, payloadEnd, depth + 1, isLyricsAtom, candidates)
                }
            }
            pos += atomSize
        }
    }

    private fun isMp4Container(type: String): Boolean {
        return type == "moov" || type == "udta" || type == "meta" || type == "ilst"
    }

    private fun isMp4LyricsAtom(typeBytes: ByteArray): Boolean {
        return typeBytes.size == 4 &&
            (typeBytes[0].toInt() and 0xFF) == 0xA9 &&
            typeBytes[1] == 'l'.code.toByte() &&
            typeBytes[2] == 'y'.code.toByte() &&
            typeBytes[3] == 'r'.code.toByte()
    }

    private fun readMp4DataPayload(raf: RandomAccessFile, start: Long, end: Long): ByteArray {
        val payloadStart = if (start + 8 <= end) start + 8 else start
        val length = (end - payloadStart).coerceAtMost(2L * 1024L * 1024L).toInt()
        if (length <= 0) return ByteArray(0)
        raf.seek(payloadStart)
        return ByteArray(length).also { raf.readFully(it) }
    }

    private fun readMp4FreeformLyrics(raf: RandomAccessFile, start: Long, end: Long): String? {
        var pos = start
        var name = ""
        var data = ""
        while (pos + 8 <= end) {
            raf.seek(pos)
            val size = raf.readUnsignedInt()
            val typeBytes = ByteArray(4)
            raf.readFully(typeBytes)
            val type = String(typeBytes, StandardCharsets.ISO_8859_1)
            val header = 8L
            if (size < header || pos + size > end) break
            val payloadStart = pos + header
            val payloadEnd = pos + size
            when (type) {
                "name" -> {
                    val payload = readMp4NamePayload(raf, payloadStart, payloadEnd)
                    name = decodeText(payload).trim()
                }
                "data" -> {
                    data = decodeText(readMp4DataPayload(raf, payloadStart, payloadEnd)).trim()
                }
            }
            pos += size
        }
        val lowered = name.lowercase()
        return if (data.isNotBlank() && (lowered.contains("lyric") || lowered.contains("lrc") || lowered.contains("歌词"))) data else null
    }

    private fun readMp4NamePayload(raf: RandomAccessFile, start: Long, end: Long): ByteArray {
        val payloadStart = if (start + 4 <= end) start + 4 else start
        val length = (end - payloadStart).coerceAtMost(256L * 1024L).toInt()
        if (length <= 0) return ByteArray(0)
        raf.seek(payloadStart)
        return ByteArray(length).also { raf.readFully(it) }
    }

    private fun readFlacVorbisLyrics(path: String): List<LyricCandidate> {
        val file = File(path)
        if (!file.exists() || file.length() < 16) return emptyList()
        return try {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                if (input.read(magic) != 4 || String(magic, StandardCharsets.US_ASCII) != "fLaC") {
                    return emptyList()
                }
                val candidates = mutableListOf<LyricCandidate>()
                var isLast = false
                while (!isLast) {
                    val header = ByteArray(4)
                    if (input.read(header) != 4) break
                    isLast = (header[0].toInt() and 0x80) != 0
                    val type = header[0].toInt() and 0x7F
                    val length = ((header[1].toInt() and 0xFF) shl 16) or
                        ((header[2].toInt() and 0xFF) shl 8) or
                        (header[3].toInt() and 0xFF)
                    if (length <= 0 || length > 8 * 1024 * 1024) break
                    val block = input.readBytesLimited(length)
                    if (type == 4) {
                        extractVorbisCommentLyrics(block).forEach { text ->
                            candidates.add(LyricCandidate(source = "内嵌歌词", text = text))
                        }
                        break
                    }
                }
                candidates
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun extractVorbisCommentLyrics(block: ByteArray): List<String> {
        return try {
            var pos = 0
            val vendorLength = littleEndianInt(block, pos)
            pos += 4 + vendorLength
            if (pos + 4 > block.size) return emptyList()
            val commentCount = littleEndianInt(block, pos)
            pos += 4
            val result = mutableListOf<String>()
            repeat(commentCount.coerceAtMost(10_000)) {
                if (pos + 4 > block.size) return@repeat
                val length = littleEndianInt(block, pos)
                pos += 4
                if (length <= 0 || pos + length > block.size) return@repeat
                val comment = String(block, pos, length, StandardCharsets.UTF_8)
                pos += length
                val key = comment.substringBefore('=', "").lowercase()
                val value = comment.substringAfter('=', "")
                if (value.isNotBlank() && (key == "lyrics" || key == "unsyncedlyrics" || key == "syncedlyrics" || key == "lrc")) {
                    result.add(value)
                }
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val clean = bytes.trimZeroBytes()
        if (clean.isEmpty()) return ""
        return when {
            clean.size >= 3 && clean[0] == 0xEF.toByte() && clean[1] == 0xBB.toByte() && clean[2] == 0xBF.toByte() -> {
                String(clean.copyOfRange(3, clean.size), Charsets.UTF_8)
            }
            clean.size >= 2 && clean[0] == 0xFF.toByte() && clean[1] == 0xFE.toByte() -> {
                String(clean, Charsets.UTF_16LE)
            }
            clean.size >= 2 && clean[0] == 0xFE.toByte() && clean[1] == 0xFF.toByte() -> {
                String(clean, Charsets.UTF_16BE)
            }
            looksLikeUtf16Le(clean) -> String(clean, Charsets.UTF_16LE)
            looksLikeUtf16Be(clean) -> String(clean, Charsets.UTF_16BE)
            else -> decodeUtf8OrCjk(clean)
        }.replace("\u0000", "").trim()
    }

    private fun decodeUtf8OrCjk(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        val charsetNames = listOf("GB18030", "GBK", "Big5")
        for (name in charsetNames) {
            try {
                val decoded = String(bytes, Charset.forName(name))
                if (!decoded.contains('\uFFFD')) return decoded
            } catch (_: Exception) {
            }
        }
        return utf8
    }

    private fun decodePossiblyUtf8OrLatin1(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        val latin1 = String(bytes, StandardCharsets.ISO_8859_1)
        return MusicScanner.fixEncoding(latin1)
    }

    private fun looksLikeUtf16Le(bytes: ByteArray): Boolean {
        val sample = min(bytes.size, 160)
        if (sample < 8) return false
        var zeroCount = 0
        var evenCount = 0
        var oddCount = 0
        for (i in 0 until sample) {
            if (bytes[i].toInt() == 0) {
                zeroCount++
                if (i % 2 == 0) evenCount++ else oddCount++
            }
        }
        return zeroCount >= sample / 4 && oddCount > evenCount * 2
    }

    private fun looksLikeUtf16Be(bytes: ByteArray): Boolean {
        val sample = min(bytes.size, 160)
        if (sample < 8) return false
        var zeroCount = 0
        var evenCount = 0
        var oddCount = 0
        for (i in 0 until sample) {
            if (bytes[i].toInt() == 0) {
                zeroCount++
                if (i % 2 == 0) evenCount++ else oddCount++
            }
        }
        return zeroCount >= sample / 4 && evenCount > oddCount * 2
    }

    private fun ByteArray.trimZeroBytes(): ByteArray {
        var start = 0
        var end = size
        while (start < end && this[start].toInt() == 0) start++
        while (end > start && this[end - 1].toInt() == 0) end--
        return copyOfRange(start, end)
    }

    private fun java.io.InputStream.readBytesLimited(length: Int): ByteArray {
        val result = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = read(result, read, length - read)
            if (count <= 0) break
            read += count
        }
        return if (read == length) result else result.copyOf(read)
    }

    private fun syncSafeInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 3 >= bytes.size) return 0
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun int24(bytes: ByteArray, offset: Int): Int {
        if (offset + 2 >= bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            (bytes[offset + 2].toInt() and 0xFF)
    }

    private fun int32(bytes: ByteArray, offset: Int): Int {
        if (offset + 3 >= bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun uint32(bytes: ByteArray, offset: Int): Long {
        if (offset + 3 >= bytes.size) return 0L
        return ((bytes[offset].toLong() and 0xFFL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFFL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFFL) shl 8) or
            (bytes[offset + 3].toLong() and 0xFFL)
    }

    private fun RandomAccessFile.readUnsignedInt(): Long {
        return readInt().toLong() and 0xFFFFFFFFL
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 3 >= bytes.size) return 0
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun removeUnsynchronisation(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty()) return bytes
        val result = ArrayList<Byte>(bytes.size)
        var i = 0
        while (i < bytes.size) {
            val current = bytes[i]
            result.add(current)
            if ((current.toInt() and 0xFF) == 0xFF && i + 1 < bytes.size && bytes[i + 1].toInt() == 0) {
                i += 2
            } else {
                i++
            }
        }
        return result.toByteArray()
    }
}
