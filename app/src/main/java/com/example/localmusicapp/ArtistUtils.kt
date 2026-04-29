package com.example.localmusicapp

object ArtistUtils {

    private val splitRegex = Regex(
        pattern = """\s*(?:/|／|&|＆|、|,|，|;|；|\||(?:\bfeat\b|\bft\b)\.?)\s*""",
        option = RegexOption.IGNORE_CASE
    )

    fun splitArtists(raw: String): List<String> {
        if (raw.isBlank()) return listOf("未知艺术家")
        return raw.split(splitRegex)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .ifEmpty { listOf("未知艺术家") }
    }

    fun primaryArtist(raw: String): String {
        return splitArtists(raw).firstOrNull().orEmpty().ifBlank { "未知艺术家" }
    }

    fun displayArtists(raw: String): String {
        return splitArtists(raw).joinToString(" / ")
    }
}
