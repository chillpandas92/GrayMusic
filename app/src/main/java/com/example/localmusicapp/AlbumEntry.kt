package com.example.localmusicapp

data class AlbumEntry(
    val key: String,
    val title: String,
    val artist: String,
    val year: Int,
    val songs: List<MusicScanner.MusicFile>,
    val coverPath: String,
    val totalDurationMs: Long
) {
    val yearText: String
        get() = if (year > 0) year.toString() else ""

    val songCount: Int
        get() = songs.size

    /**
     * 专辑卡片用的完整艺术家串。按歌曲顺序去重后，用 " / " 连成一行；
     * 由 TextView 的 singleLine/ellipsize 负责在显示过长时截断为 "..."。
     */
    val allArtistsLabel: String
        get() {
            val unique = LinkedHashSet<String>()
            for (song in songs) {
                for (name in ArtistUtils.splitArtists(song.artist)) {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) unique.add(trimmed)
                }
            }
            return if (unique.isEmpty()) {
                artist.ifBlank { "未知艺术家" }
            } else {
                unique.joinToString(" / ")
            }
        }
}
