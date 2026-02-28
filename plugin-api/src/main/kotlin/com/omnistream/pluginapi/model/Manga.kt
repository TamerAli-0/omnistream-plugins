package com.omnistream.pluginapi.model

import kotlinx.serialization.Serializable

/**
 * Manga/Manhwa/Manhua domain model.
 * Moved from com.omnistream.domain.model to plugin-api for shared access by plugins.
 */
@Serializable
data class Manga(
    val id: String,
    val sourceId: String,
    val title: String,
    val url: String,
    val coverUrl: String? = null,
    val description: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val status: MangaStatus = MangaStatus.UNKNOWN,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val rating: Float? = null,
    val isNsfw: Boolean = false,
    val alternativeTitles: List<String> = emptyList(),
    val anilistId: Int? = null,
    val malId: Int? = null
)

enum class MangaStatus {
    ONGOING, COMPLETED, HIATUS, CANCELLED, UNKNOWN
}

/** Manga content type categories. Moved from com.omnistream.source.model.MangaContentType. */
enum class MangaContentType {
    MANGA, MANHWA, MANHUA, COMIC, ALL
}

/**
 * Chapter domain model.
 */
@Serializable
data class Chapter(
    val id: String,
    val mangaId: String,
    val sourceId: String,
    val url: String,
    val title: String? = null,
    val number: Float,
    val volume: Int? = null,
    val scanlator: String? = null,
    val uploadDate: Long? = null,
    val pageCount: Int? = null
)

/**
 * Page domain model (for manga reader).
 */
@Serializable
data class Page(
    val index: Int,
    val imageUrl: String,
    val referer: String? = null
)
