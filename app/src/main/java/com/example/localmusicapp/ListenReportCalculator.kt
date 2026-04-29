package com.example.localmusicapp

import android.content.Context
import java.util.Calendar

object ListenReportCalculator {

    enum class Range {
        DAY,
        WEEK,
        MONTH
    }

    data class RankedSong(
        val rank: Int,
        val file: MusicScanner.MusicFile,
        val durationMs: Long
    )

    // 排行榜里歌手的一项：除了名字、时长、在这一段时间里听过的歌数 / 播放次数，
    // 还带一个"代表作"，用来在 UI 上当作歌手封面显示（就拿该歌手听最久的那首歌的封面）。
    data class RankedArtist(
        val rank: Int,
        val name: String,
        val durationMs: Long,
        val playCount: Int,
        val songCount: Int,
        val representativeFile: MusicScanner.MusicFile?
    )

    data class ReportState(
        val range: Range,
        val summaryLabel: String,
        val bestArtistLabel: String,
        val totalDurationMs: Long,
        val activeDays: Int,
        val bestArtistName: String,
        val bestArtistDurationMs: Long,
        val topSongs: List<RankedSong>,
        val topArtists: List<RankedArtist> = emptyList(),
        val dayHoursMs: LongArray = LongArray(24),
        val weekDaysMs: LongArray = LongArray(7),
        val monthDaysMs: LongArray = LongArray(0),
        val monthYear: Int = 0,
        val monthZeroBased: Int = 0,
        val monthFirstDayOffset: Int = 0,
        val weekDateLabels: List<String> = List(7) { "" }
    )

    fun build(
        context: Context,
        libraryFiles: List<MusicScanner.MusicFile>,
        range: Range
    ): ReportState {
        ListenStats.load(context)

        val now = System.currentTimeMillis()
        val rangeStart = when (range) {
            Range.DAY -> startOfToday(now)
            Range.WEEK -> startOfWeek(now)
            Range.MONTH -> startOfMonth(now)
        }

        val byPath = libraryFiles.associateBy { it.path }
        val events = ListenStats.listenEventsSnapshot()
        val qualifiedCounts = ListenStats.countSince(rangeStart)

        val durationByPath = linkedMapOf<String, Long>()
        val artistDurations = linkedMapOf<String, Long>()
        // 为"歌手排行"服务：每个歌手对应的 path → 时长 映射，用来挑代表作、算歌数。
        val perArtistPathDurations = linkedMapOf<String, LinkedHashMap<String, Long>>()
        val dayHours = LongArray(24)
        val weekDays = LongArray(7)

        val monthCalendar = Calendar.getInstance().apply {
            timeInMillis = rangeStart
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val daysInMonth = if (range == Range.MONTH) {
            monthCalendar.getActualMaximum(Calendar.DAY_OF_MONTH).coerceIn(28, 31)
        } else {
            0
        }
        val monthDays = if (range == Range.MONTH) LongArray(daysInMonth) else LongArray(0)
        val monthYear = if (range == Range.MONTH) monthCalendar.get(Calendar.YEAR) else 0
        val monthZeroBased = if (range == Range.MONTH) monthCalendar.get(Calendar.MONTH) else 0
        val monthFirstDayOffset = if (range == Range.MONTH) {
            Calendar.getInstance().apply {
                timeInMillis = rangeStart
                set(Calendar.DAY_OF_MONTH, 1)
            }.let { cal ->
                (cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
            }
        } else {
            0
        }
        val weekDateLabels = if (range == Range.WEEK) {
            List(7) { index ->
                Calendar.getInstance().apply {
                    timeInMillis = rangeStart
                    add(Calendar.DAY_OF_YEAR, index)
                }.let { cal ->
                    "${cal.get(Calendar.MONTH) + 1}/${cal.get(Calendar.DAY_OF_MONTH)}"
                }
            }
        } else {
            emptyList()
        }

        for (event in events) {
            val file = byPath[event.path] ?: continue
            val eventEnd = event.startedAt + event.listenedMs
            val clampedStart = maxOf(rangeStart, event.startedAt)
            val clampedEnd = minOf(now, eventEnd)
            if (clampedEnd <= clampedStart) continue

            val overlappedMs = clampedEnd - clampedStart
            durationByPath[file.path] = (durationByPath[file.path] ?: 0L) + overlappedMs

            val artistKey = file.artistGroup().ifBlank {
                ArtistUtils.displayArtists(file.artist).ifBlank { "未知歌手" }
            }
            artistDurations[artistKey] = (artistDurations[artistKey] ?: 0L) + overlappedMs
            val perPath = perArtistPathDurations.getOrPut(artistKey) { LinkedHashMap() }
            perPath[file.path] = (perPath[file.path] ?: 0L) + overlappedMs

            when (range) {
                Range.DAY -> accumulateByHour(clampedStart, clampedEnd, dayHours)
                Range.WEEK -> accumulateByWeekday(clampedStart, clampedEnd, weekDays)
                Range.MONTH -> accumulateByMonthDay(clampedStart, clampedEnd, monthDays)
            }
        }

        val sortedCandidates = durationByPath.entries
            .asSequence()
            .filter { it.value > 0L }
            .mapNotNull { entry -> byPath[entry.key]?.let { file -> file to entry.value } }
            .toList()

        // 阈值只收"这个时间段里至少达标过一次"的歌曲。去掉原来的"达标为空就回退到全部"的
        // fallback：只要用户调了阈值，就严格按阈值来，没达标就不上榜（宁可榜空也不掺假）。
        val thresholdQualified = sortedCandidates
            .filter { (qualifiedCounts[it.first.path] ?: 0) > 0 }
        val topSongs = thresholdQualified
            .sortedWith(
                compareByDescending<Pair<MusicScanner.MusicFile, Long>> { it.second }
                    .thenBy { SortKeyHelper.keyOf(it.first.title) }
                    .thenBy { it.first.path }
            )
            .take(3)
            .mapIndexed { index, (file, durationMs) ->
                RankedSong(
                    rank = index + 1,
                    file = file,
                    durationMs = durationMs
                )
            }

        val totalDurationMs = when (range) {
            Range.DAY -> dayHours.sum()
            Range.WEEK -> weekDays.sum()
            Range.MONTH -> monthDays.sum()
        }
        val activeDays = when (range) {
            Range.DAY -> if (totalDurationMs > 0L) 1 else 0
            Range.WEEK -> weekDays.count { it > 0L }
            Range.MONTH -> monthDays.count { it > 0L }
        }

        // 歌手排行：只聚合"已达阈值"的歌曲时长，避免把零碎的试听也算进来。
        // bestArtist 和 topArtists 都用这一套，跟 topSongs 保持一致的口径。
        val qualifiedPaths = thresholdQualified.mapTo(HashSet()) { it.first.path }
        val thresholdArtistDurations = linkedMapOf<String, Long>()
        val thresholdPerArtistPathDurations = linkedMapOf<String, LinkedHashMap<String, Long>>()
        for ((artistName, perPath) in perArtistPathDurations) {
            for ((path, durMs) in perPath) {
                if (path !in qualifiedPaths) continue
                thresholdArtistDurations[artistName] =
                    (thresholdArtistDurations[artistName] ?: 0L) + durMs
                val sub = thresholdPerArtistPathDurations.getOrPut(artistName) { LinkedHashMap() }
                sub[path] = (sub[path] ?: 0L) + durMs
            }
        }

        val bestArtist = thresholdArtistDurations.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Long>> { it.value }
                    .thenBy { SortKeyHelper.keyOf(it.key) }
            )
            .firstOrNull()

        val topArtists = thresholdArtistDurations.entries
            .asSequence()
            .filter { it.value > 0L }
            .sortedWith(
                compareByDescending<Map.Entry<String, Long>> { it.value }
                    .thenBy { SortKeyHelper.keyOf(it.key) }
            )
            .take(3)
            .toList()
            .mapIndexed { index, entry ->
                val name = entry.key
                val durationMs = entry.value
                val pathDurations = thresholdPerArtistPathDurations[name] ?: LinkedHashMap()
                // 代表作：该歌手在这一段时间里听得最久（且已达阈值）的那一首
                val bestPath = pathDurations.entries
                    .maxWithOrNull(
                        compareBy<Map.Entry<String, Long>> { it.value }
                            .thenByDescending { it.key }
                    )?.key
                val repFile = bestPath?.let { byPath[it] }
                // 播放次数：该歌手名下"已达阈值"歌曲的 qualified count 之和
                val playCount = pathDurations.keys.sumOf { qualifiedCounts[it] ?: 0 }
                RankedArtist(
                    rank = index + 1,
                    name = name,
                    durationMs = durationMs,
                    playCount = playCount,
                    songCount = pathDurations.size,
                    representativeFile = repFile
                )
            }

        return ReportState(
            range = range,
            summaryLabel = when (range) {
                Range.DAY -> "今日听歌"
                Range.WEEK -> "本周听歌"
                Range.MONTH -> "本月听歌"
            },
            bestArtistLabel = when (range) {
                Range.DAY -> "本日最佳歌手"
                Range.WEEK -> "本周最佳歌手"
                Range.MONTH -> "本月最佳歌手"
            },
            totalDurationMs = totalDurationMs,
            activeDays = activeDays,
            bestArtistName = bestArtist?.key.orEmpty(),
            bestArtistDurationMs = bestArtist?.value ?: 0L,
            topSongs = topSongs,
            topArtists = topArtists,
            dayHoursMs = dayHours,
            weekDaysMs = weekDays,
            monthDaysMs = monthDays,
            monthYear = monthYear,
            monthZeroBased = monthZeroBased,
            monthFirstDayOffset = monthFirstDayOffset,
            weekDateLabels = weekDateLabels
        )
    }

    private fun accumulateByHour(startMs: Long, endMs: Long, buckets: LongArray) {
        var cursor = startMs
        while (cursor < endMs) {
            val cal = Calendar.getInstance().apply { timeInMillis = cursor }
            val hour = cal.get(Calendar.HOUR_OF_DAY).coerceIn(0, 23)
            val boundary = Calendar.getInstance().apply {
                timeInMillis = cursor
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.HOUR_OF_DAY, 1)
            }.timeInMillis
            val chunkEnd = minOf(endMs, boundary)
            buckets[hour] += (chunkEnd - cursor).coerceAtLeast(0L)
            cursor = chunkEnd
        }
    }

    private fun accumulateByWeekday(startMs: Long, endMs: Long, buckets: LongArray) {
        var cursor = startMs
        while (cursor < endMs) {
            val cal = Calendar.getInstance().apply { timeInMillis = cursor }
            val weekday = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
            val boundary = Calendar.getInstance().apply {
                timeInMillis = cursor
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
            val chunkEnd = minOf(endMs, boundary)
            if (weekday in buckets.indices) {
                buckets[weekday] += (chunkEnd - cursor).coerceAtLeast(0L)
            }
            cursor = chunkEnd
        }
    }

    private fun accumulateByMonthDay(startMs: Long, endMs: Long, buckets: LongArray) {
        if (buckets.isEmpty()) return
        var cursor = startMs
        while (cursor < endMs) {
            val cal = Calendar.getInstance().apply { timeInMillis = cursor }
            val dayIndex = cal.get(Calendar.DAY_OF_MONTH) - 1
            val boundary = Calendar.getInstance().apply {
                timeInMillis = cursor
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, 1)
            }.timeInMillis
            val chunkEnd = minOf(endMs, boundary)
            if (dayIndex in buckets.indices) {
                buckets[dayIndex] += (chunkEnd - cursor).coerceAtLeast(0L)
            }
            cursor = chunkEnd
        }
    }

    private fun startOfToday(nowMs: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun startOfWeek(nowMs: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            val daysSinceMonday = (get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
            add(Calendar.DAY_OF_YEAR, -daysSinceMonday)
        }.timeInMillis
    }

    private fun startOfMonth(nowMs: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
