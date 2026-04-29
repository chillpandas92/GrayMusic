package com.example.localmusicapp

data class ArtistEntry(
    val name: String,
    val songs: List<MusicScanner.MusicFile>,
    val albums: List<AlbumEntry>,
    val coverPath: String,
    val totalDurationMs: Long
) {
    val key: String
        get() = SortKeyHelper.keyOf(name).ifBlank { name.trim().lowercase() }

    val albumCount: Int
        get() = albums.size

    val songCount: Int
        get() = songs.size

    val metaText: String
        get() = "${albumCount}张专辑 / ${songCount}首歌曲"
}
