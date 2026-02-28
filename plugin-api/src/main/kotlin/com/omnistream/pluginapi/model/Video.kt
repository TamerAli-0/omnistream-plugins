package com.omnistream.pluginapi.model

import kotlinx.serialization.Serializable

/**
 * Video content domain model (anime, movie, TV show).
 * Moved from com.omnistream.domain.model to plugin-api for shared access by plugins.
 */
@Serializable
data class Video(
    val id: String,
    val sourceId: String,
    val title: String,
    val url: String,
    val type: VideoType,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val description: String? = null,
    val year: Int? = null,
    val rating: Float? = null,
    val duration: Int? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val director: String? = null,
    val studio: String? = null,
    val status: VideoStatus = VideoStatus.UNKNOWN,
    val episodeCount: Int? = null,
    val seasonCount: Int? = null,
    val anilistId: Int? = null,
    val malId: Int? = null
)

enum class VideoStatus {
    ONGOING, COMPLETED, UPCOMING, UNKNOWN
}

/** Video content type. Previously VideoType in com.omnistream.source.model — moved here to be part of the shared API. */
enum class VideoType {
    ANIME, MOVIE, TV_SERIES, DOCUMENTARY, LIVE
}

/**
 * Episode domain model.
 */
@Serializable
data class Episode(
    val id: String,
    val videoId: String,
    val sourceId: String,
    val url: String,
    val title: String? = null,
    val number: Int,
    val season: Int? = null,
    val thumbnailUrl: String? = null,
    val description: String? = null,
    val airDate: Long? = null,
    val duration: Int? = null
)

/**
 * Playable video link with quality info.
 */
@Serializable
data class VideoLink(
    val url: String,
    val quality: String,
    val extractorName: String,
    val referer: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val isM3u8: Boolean = false,
    val isDash: Boolean = false,
    val subtitles: List<Subtitle> = emptyList()
)

/**
 * Subtitle track.
 */
@Serializable
data class Subtitle(
    val url: String,
    val language: String,
    val label: String? = null,
    val isDefault: Boolean = false
)
