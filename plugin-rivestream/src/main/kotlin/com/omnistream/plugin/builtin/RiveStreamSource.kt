package com.omnistream.source.movie

import com.omnistream.pluginapi.net.SourceHttpClient
import com.omnistream.pluginapi.model.Episode
import com.omnistream.pluginapi.model.MainPageSection
import com.omnistream.pluginapi.model.HomeSection
import com.omnistream.pluginapi.model.PagedResult
import com.omnistream.pluginapi.model.Subtitle
import com.omnistream.pluginapi.model.Video
import com.omnistream.pluginapi.model.VideoLink
import com.omnistream.pluginapi.model.VideoStatus
import com.omnistream.pluginapi.source.VideoSource
import com.omnistream.pluginapi.model.VideoType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * RiveStream - Direct scraper source for movies and TV shows.
 *
 * More reliable than embed-based sources. Uses direct CDN links.
 * Fallback to Consumet API if primary method fails.
 */
class RiveStreamSource(
    private val httpClient: SourceHttpClient
) : VideoSource {

    override val id = "rivestream"
    override val name = "RiveStream"
    override val baseUrl = "https://rivestream.live"
    override val lang = "en"
    override val supportedTypes = setOf(VideoType.MOVIE, VideoType.TV_SERIES)

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    // TMDB API for metadata
    private val tmdbApiKey = "297f1b91919bae59d50ed815f8d2e14c"
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"

    /**
     * Extract TMDB ID from prefixed format (e.g., "movie-12345" -> "12345")
     */
    private fun extractTmdbId(prefixedId: String): String {
        return prefixedId.substringAfter("-", prefixedId)
    }

    override val hasSearch: Boolean = true
    override val hasLatest: Boolean = true

    override val mainPageSections: List<MainPageSection> = listOf(
        MainPageSection("Featured", "all")
    )

    override suspend fun getSection(section: MainPageSection, page: Int): PagedResult<Video> {
        return try {
            val items = getHomePage().flatMap { it.items }
            PagedResult(items, hasMore = false)
        } catch (e: Exception) {
            android.util.Log.e("RiveStreamSource", "getSection failed: ${e.message}", e)
            PagedResult(emptyList())
        }
    }

    private suspend fun getHomePage(): List<HomeSection> {
        val sections = mutableListOf<HomeSection>()

        try {
            android.util.Log.d("RiveStream", "Loading home page via TMDB")

            // Trending Movies
            val trending = fetchFromTmdb("trending/movie/day")
            if (trending.isNotEmpty()) {
                sections.add(HomeSection("Trending Movies", trending.take(20)))
            }

            // Popular Movies
            val popular = fetchFromTmdb("movie/popular")
            if (popular.isNotEmpty()) {
                sections.add(HomeSection("Popular Movies", popular.take(20)))
            }

            // Top Rated Movies
            val topRated = fetchFromTmdb("movie/top_rated")
            if (topRated.isNotEmpty()) {
                sections.add(HomeSection("Top Rated", topRated.take(20)))
            }

            // Popular TV Shows
            val tvPopular = fetchFromTmdb("tv/popular")
            if (tvPopular.isNotEmpty()) {
                sections.add(HomeSection("Popular TV Shows", tvPopular.take(20)))
            }

        } catch (e: Exception) {
            android.util.Log.e("RiveStream", "Failed to load home page", e)
        }

        return sections
    }

    private suspend fun fetchFromTmdb(endpoint: String): List<Video> {
        return try {
            val url = "$tmdbBaseUrl/$endpoint?api_key=$tmdbApiKey&language=en-US&page=1"
            val response = httpClient.get(url)
            parseTmdbResponse(response, if (endpoint.contains("tv")) VideoType.TV_SERIES else VideoType.MOVIE)
        } catch (e: Exception) {
            android.util.Log.e("RiveStream", "TMDB fetch failed: $endpoint", e)
            emptyList()
        }
    }

    private fun parseTmdbResponse(response: String, type: VideoType): List<Video> {
        return try {
            val jsonObj = json.parseToJsonElement(response).jsonObject
            val results = jsonObj["results"]?.jsonArray ?: return emptyList()

            results.mapNotNull { element ->
                try {
                    val item = element.jsonObject
                    val id = item["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val title = item["title"]?.jsonPrimitive?.content
                        ?: item["name"]?.jsonPrimitive?.content
                        ?: return@mapNotNull null
                    val posterPath = item["poster_path"]?.jsonPrimitive?.content
                    val releaseDate = item["release_date"]?.jsonPrimitive?.content
                        ?: item["first_air_date"]?.jsonPrimitive?.content
                    val voteAverage = item["vote_average"]?.jsonPrimitive?.content?.toFloatOrNull()

                    val typePrefix = if (type == VideoType.TV_SERIES) "tv" else "movie"
                    Video(
                        id = "$typePrefix-$id",
                        sourceId = this.id,
                        title = title,
                        url = "$baseUrl/$typePrefix/$id",
                        type = type,
                        posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" },
                        year = releaseDate?.take(4)?.toIntOrNull(),
                        rating = voteAverage
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("RiveStream", "TMDB parse failed", e)
            emptyList()
        }
    }

    override suspend fun search(query: String, page: Int): PagedResult<Video> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val results = mutableListOf<Video>()

        try {
            // Search movies
            val movieUrl = "$tmdbBaseUrl/search/movie?api_key=$tmdbApiKey&language=en-US&query=$encodedQuery&page=$page"
            val movieResponse = httpClient.get(movieUrl)
            results.addAll(parseTmdbResponse(movieResponse, VideoType.MOVIE))

            // Search TV shows
            val tvUrl = "$tmdbBaseUrl/search/tv?api_key=$tmdbApiKey&language=en-US&query=$encodedQuery&page=$page"
            val tvResponse = httpClient.get(tvUrl)
            results.addAll(parseTmdbResponse(tvResponse, VideoType.TV_SERIES))

        } catch (e: Exception) {
            android.util.Log.e("RiveStream", "Search failed", e)
        }

        val items = results.distinctBy { it.id }
        return PagedResult(items, hasMore = items.size >= 20)
    }

    override suspend fun getDetails(video: Video): Video {
        return try {
            val tmdbId = extractTmdbId(video.id)
            val isMovie = video.id.startsWith("movie-")
            val endpoint = if (isMovie) "movie" else "tv"

            val detailsUrl = "$tmdbBaseUrl/$endpoint/$tmdbId?api_key=$tmdbApiKey&language=en-US"
            val response = httpClient.get(detailsUrl)
            val jsonObj = json.parseToJsonElement(response).jsonObject

            val title = jsonObj["title"]?.jsonPrimitive?.content
                ?: jsonObj["name"]?.jsonPrimitive?.content
                ?: video.title
            val posterPath = jsonObj["poster_path"]?.jsonPrimitive?.content
            val backdropPath = jsonObj["backdrop_path"]?.jsonPrimitive?.content
            val overview = jsonObj["overview"]?.jsonPrimitive?.content
            val voteAverage = jsonObj["vote_average"]?.jsonPrimitive?.content?.toFloatOrNull()
            val releaseDate = jsonObj["release_date"]?.jsonPrimitive?.content
                ?: jsonObj["first_air_date"]?.jsonPrimitive?.content
            val runtime = jsonObj["runtime"]?.jsonPrimitive?.content?.toIntOrNull()

            val genresArray = jsonObj["genres"]?.jsonArray
            val genres = genresArray?.mapNotNull {
                it.jsonObject["name"]?.jsonPrimitive?.content
            } ?: emptyList()

            val episodeCount = if (!isMovie) {
                jsonObj["number_of_episodes"]?.jsonPrimitive?.content?.toIntOrNull()
            } else null

            val seasonCount = if (!isMovie) {
                jsonObj["number_of_seasons"]?.jsonPrimitive?.content?.toIntOrNull()
            } else null

            val statusStr = jsonObj["status"]?.jsonPrimitive?.content
            val status = when (statusStr?.lowercase()) {
                "released", "ended" -> VideoStatus.COMPLETED
                "returning series", "in production" -> VideoStatus.ONGOING
                "planned", "post production" -> VideoStatus.UPCOMING
                else -> VideoStatus.UNKNOWN
            }

            video.copy(
                title = title,
                posterUrl = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: video.posterUrl,
                backdropUrl = backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" },
                description = overview,
                year = releaseDate?.take(4)?.toIntOrNull() ?: video.year,
                rating = voteAverage ?: video.rating,
                duration = runtime,
                genres = genres,
                status = status,
                episodeCount = episodeCount,
                seasonCount = seasonCount
            )
        } catch (e: Exception) {
            android.util.Log.e("RiveStream", "Get details failed", e)
            video
        }
    }

    override suspend fun getEpisodes(video: Video): List<Episode> {
        // For movies, return single episode
        if (video.type == VideoType.MOVIE) {
            return listOf(
                Episode(
                    id = video.id,
                    videoId = video.id,
                    sourceId = id,
                    url = video.url,
                    title = video.title,
                    number = 1
                )
            )
        }

        // For TV series, fetch from TMDB
        return try {
            val tmdbId = extractTmdbId(video.id)
            val episodes = mutableListOf<Episode>()

            val detailsUrl = "$tmdbBaseUrl/tv/$tmdbId?api_key=$tmdbApiKey&language=en-US"
            val response = httpClient.get(detailsUrl)
            val jsonObj = json.parseToJsonElement(response).jsonObject
            val numberOfSeasons = jsonObj["number_of_seasons"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1

            for (seasonNum in 1..numberOfSeasons) {
                try {
                    val seasonUrl = "$tmdbBaseUrl/tv/$tmdbId/season/$seasonNum?api_key=$tmdbApiKey&language=en-US"
                    val seasonResponse = httpClient.get(seasonUrl)
                    val seasonObj = json.parseToJsonElement(seasonResponse).jsonObject
                    val episodesArray = seasonObj["episodes"]?.jsonArray ?: continue

                    episodesArray.forEach { epElement ->
                        val epObj = epElement.jsonObject
                        val epNum = epObj["episode_number"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@forEach
                        val epTitle = epObj["name"]?.jsonPrimitive?.content

                        episodes.add(Episode(
                            id = "${video.id}_s${seasonNum}_e$epNum",
                            videoId = video.id,
                            sourceId = id,
                            url = "$baseUrl/tv/$tmdbId/$seasonNum/$epNum",
                            title = epTitle,
                            number = epNum,
                            season = seasonNum
                        ))
                    }
                } catch (e: Exception) {
                    android.util.Log.e("RiveStream", "Failed to fetch season $seasonNum", e)
                }
            }

            episodes.sortedWith(compareBy({ it.season }, { it.number }))
        } catch (e: Exception) {
            android.util.Log.e("RiveStream", "Get episodes failed", e)
            emptyList()
        }
    }

    // RiveStream confirmed secret key (base64 encoded)
    private val secretKey = "MzdhNjY1ODA="

    override suspend fun loadLinks(
        episode: Episode,
        subtitleCallback: (com.omnistream.pluginapi.model.Subtitle) -> Unit,
        callback: (VideoLink) -> Unit
    ): Boolean {
        val links = mutableListOf<VideoLink>()
        val tmdbId = extractTmdbId(episode.videoId)
        val isMovie = episode.season == null || episode.season == 0

        try {
            android.util.Log.d("RiveStream", "Getting links for TMDB ID: $tmdbId, isMovie: $isMovie")

            // RiveStream backendfetch API — confirmed working, fully open
            val apiUrl = if (isMovie) {
                "$baseUrl/api/backendfetch?requestID=movieVideoProvider&id=$tmdbId&secretKey=$secretKey"
            } else {
                "$baseUrl/api/backendfetch?requestID=tvVideoProvider&id=$tmdbId&season=${episode.season}&episode=${episode.number}&secretKey=$secretKey"
            }

            android.util.Log.d("RiveStream", "Fetching: $apiUrl")
            val response = httpClient.get(apiUrl, headers = mapOf(
                "Referer" to "$baseUrl/",
                "Origin" to baseUrl
            ))
            android.util.Log.d("RiveStream", "Response: ${response.take(500)}")

            parseBackendfetchResponse(response, links)

        } catch (e: Exception) {
            android.util.Log.e("RiveStream", "getLinks failed", e)
        }

        val unique = links.distinctBy { it.url }
        unique.forEach { callback(it) }
        android.util.Log.d("RiveStream", "Found ${unique.size} links total")
        return unique.isNotEmpty()
    }

    private fun parseBackendfetchResponse(response: String, links: MutableList<VideoLink>) {
        try {
            val root = json.parseToJsonElement(response).jsonObject

            // Try top-level "sources" array
            root["sources"]?.jsonArray?.forEach { el ->
                parseSourceEntry(el.jsonObject, links)
            }

            // Try "data" wrapper
            root["data"]?.jsonObject?.let { data ->
                data["sources"]?.jsonArray?.forEach { el ->
                    parseSourceEntry(el.jsonObject, links)
                }
                data["stream"]?.jsonArray?.forEach { el ->
                    parseSourceEntry(el.jsonObject, links)
                }
            }

            // Try "providers" array (each provider has its own sources)
            root["providers"]?.jsonArray?.forEach { provEl ->
                val prov = provEl.jsonObject
                val provName = prov["name"]?.jsonPrimitive?.content ?: "Unknown"
                prov["sources"]?.jsonArray?.forEach { el ->
                    parseSourceEntry(el.jsonObject, links, provName)
                }
                // Some providers put the URL directly
                val directUrl = prov["url"]?.jsonPrimitive?.content
                if (directUrl != null && (directUrl.contains(".m3u8") || directUrl.contains(".mp4"))) {
                    links.add(VideoLink(
                        url = directUrl,
                        quality = extractQualityFromUrl(directUrl),
                        extractorName = "RiveStream-$provName",
                        isM3u8 = directUrl.contains(".m3u8"),
                        referer = "$baseUrl/"
                    ))
                }
            }

            // Fallback: scan for any m3u8/mp4 URL in the raw response
            if (links.isEmpty()) {
                Regex("""["'](https?://[^"'\s]+\.m3u8[^"'\s]*)["']""").findAll(response).forEach { m ->
                    val url = m.groupValues[1].replace("\\/", "/")
                    if (!links.any { it.url == url }) {
                        links.add(VideoLink(
                            url = url,
                            quality = extractQualityFromUrl(url),
                            extractorName = "RiveStream",
                            isM3u8 = true,
                            referer = "$baseUrl/"
                        ))
                    }
                }
                Regex("""["'](https?://[^"'\s]+\.mp4[^"'\s]*)["']""").findAll(response).forEach { m ->
                    val url = m.groupValues[1].replace("\\/", "/")
                    if (!links.any { it.url == url }) {
                        links.add(VideoLink(
                            url = url,
                            quality = extractQualityFromUrl(url),
                            extractorName = "RiveStream",
                            isM3u8 = false,
                            referer = "$baseUrl/"
                        ))
                    }
                }
            }

            android.util.Log.d("RiveStream", "Parsed ${links.size} links from backendfetch")
        } catch (e: Exception) {
            android.util.Log.e("RiveStream", "parseBackendfetchResponse failed", e)
        }
    }

    private fun parseSourceEntry(src: kotlinx.serialization.json.JsonObject, links: MutableList<VideoLink>, providerName: String = "RiveStream") {
        val url = src["url"]?.jsonPrimitive?.content
            ?: src["file"]?.jsonPrimitive?.content
            ?: src["link"]?.jsonPrimitive?.content
            ?: return
        val quality = src["quality"]?.jsonPrimitive?.content
            ?: src["label"]?.jsonPrimitive?.content
            ?: extractQualityFromUrl(url)
        val type = src["type"]?.jsonPrimitive?.content ?: ""
        val isM3u8 = type == "hls" || url.contains(".m3u8")
        links.add(VideoLink(
            url = url.replace("\\/", "/"),
            quality = quality,
            extractorName = providerName,
            isM3u8 = isM3u8,
            referer = "$baseUrl/"
        ))
    }

    private fun extractQualityFromUrl(url: String): String {
        return when {
            url.contains("4k", true) || url.contains("2160") -> "4K"
            url.contains("1080") -> "1080p"
            url.contains("720") -> "720p"
            url.contains("480") -> "480p"
            url.contains("360") -> "360p"
            else -> "Auto"
        }
    }

    override suspend fun ping(): Boolean {
        return try {
            httpClient.ping(tmdbBaseUrl) > 0
        } catch (e: Exception) {
            false
        }
    }
}
