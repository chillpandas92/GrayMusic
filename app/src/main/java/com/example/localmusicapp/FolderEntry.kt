package com.example.localmusicapp

data class FolderEntry(
    val key: String,
    val name: String,
    val displayPath: String,
    val songs: List<MusicScanner.MusicFile>,
    val coverPath: String,
    val totalDurationMs: Long,
    val hidden: Boolean = false
) {
    val songCount: Int get() = songs.size
}
