package com.example.localmusicapp

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File

object PlaybackUiResolver {

    fun displayFile(context: Context): MusicScanner.MusicFile? {
        PlaybackManager.currentFile()?.let { return it }
        val snapshot = PlaybackStateStore.load(context) ?: return null
        val libraryFiles = ScanResultHolder.files(context)
        libraryFiles.firstOrNull { it.path == snapshot.currentPath }?.let { return it }
        return buildFallbackFile(snapshot.currentPath)
    }

    fun displayPath(context: Context): String? = displayFile(context)?.path

    fun savedPositionMs(context: Context): Long {
        val snapshot = PlaybackStateStore.load(context) ?: return 0L
        val displayPath = displayPath(context) ?: return 0L
        return if (snapshot.currentPath == displayPath) snapshot.positionMs.coerceAtLeast(0L) else 0L
    }

    fun restoreLibraryFiles(context: Context): List<MusicScanner.MusicFile> {
        val libraryFiles = ScanResultHolder.files(context)
        if (libraryFiles.isNotEmpty()) return libraryFiles
        val snapshot = PlaybackStateStore.load(context) ?: return emptyList()
        return buildFallbackFile(snapshot.currentPath)?.let(::listOf) ?: emptyList()
    }

    private fun buildFallbackFile(path: String): MusicScanner.MusicFile? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.exists() || !file.isFile) return null

        var retriever: MediaMetadataRetriever? = null
        return try {
            retriever = MediaMetadataRetriever().apply { setDataSource(path) }
            val title = MusicScanner.fixEncoding(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.takeIf { it.isNotBlank() }
                    ?: file.nameWithoutExtension
            )
            val artist = MusicScanner.fixEncoding(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?.takeIf { it.isNotBlank() }
                    ?: "未知艺术家"
            )
            val album = MusicScanner.fixEncoding(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    ?.takeIf { it.isNotBlank() }
                    ?: "未知专辑"
            )
            val albumArtist = MusicScanner.fixEncoding(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                    ?.takeIf { it.isNotBlank() }
                    ?: ""
            )
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            val rawYear = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?.trim().orEmpty()
            val rawDate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                ?.trim().orEmpty()
            val year = extractYear(rawYear) ?: extractYear(rawDate) ?: 0
            MusicScanner.MusicFile(
                id = path.hashCode().toLong(),
                title = title,
                artist = artist,
                album = album,
                path = path,
                duration = durationMs,
                format = file.extension.lowercase(),
                size = file.length().coerceAtLeast(0L),
                dateAddedSec = (file.lastModified() / 1000L).coerceAtLeast(0L),
                albumArtist = albumArtist,
                discNumber = 0,
                trackNumber = 0,
                year = year
            )
        } catch (_: Exception) {
            MusicScanner.MusicFile(
                id = path.hashCode().toLong(),
                title = file.nameWithoutExtension.ifBlank { "未知歌曲" },
                artist = "未知艺术家",
                album = "未知专辑",
                path = path,
                duration = 0L,
                format = file.extension.lowercase(),
                size = file.length().coerceAtLeast(0L),
                dateAddedSec = (file.lastModified() / 1000L).coerceAtLeast(0L)
            )
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }
    }


    private fun extractYear(raw: String): Int? {
        if (raw.isBlank()) return null
        var index = 0
        while (index <= raw.length - 4) {
            val slice = raw.substring(index, index + 4)
            if (slice.all { it.isDigit() }) {
                val year = slice.toIntOrNull()
                if (year != null && year in 1000..9999) return year
            }
            index++
        }
        return null
    }

}
