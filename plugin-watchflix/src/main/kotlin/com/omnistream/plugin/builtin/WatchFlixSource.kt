package com.omnistream.source.movie

import com.omnistream.pluginapi.net.SourceHttpClient
import com.omnistream.pluginapi.model.Episode
import com.omnistream.pluginapi.model.MainPageSection
import com.omnistream.pluginapi.model.HomeSection
import com.omnistream.pluginapi.model.PagedResult
import com.omnistream.pluginapi.model.Subtitle
import com.omnistream.pluginapi.model.Video
import com.omnistream.pluginapi.model.VideoLink
import com.omnistream.pluginapi.source.VideoSource
import com.omnistream.pluginapi.model.VideoType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup

/**
 * WatchFlix source for movies and TV shows.
 * Site: https://watchflix.to
 *
 * Extraction chain (NO ENCRYPTION):
 * 1. vidsrc-embed.ru/embed/{type}?tmdb={id} → extract data-hash from iframe
 * 2. cloudnestra.com/rcp/{hash1} → extract prorcp hash from loadIframe()
 * 3. cloudnestra.com/prorcp/{hash2} → extract m3u8 URL from Playerjs file: param
 * 4. Replace CDN placeholders {v1},{v2},etc with actual domain
 */
class WatchFlixSource(
    private val httpClient: SourceHttpClient
) : VideoSource {

    override val id = "watchflix"
    override val name = "WatchFlix"
    override val baseUrl = "https://watchflix.to"
    override val lang = "en"
    override val supportedTypes = setOf(VideoType.MOVIE, VideoType.TV_SERIES)

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    // TMDB proxy for metadata (same as FlickyStream)
    private val tmdbProxyUrl = "https://mid.vidzee.wtf/tmdb"
    private val tmdbApiKey = "297f1b91919bae59d50ed815f8d2e14c"

    // Embed providers - try multiple (ordered by reliability)
    private val embedProviders = listOf(
        "https://vidsrc.cc",
        "https://vidsrc.in",
        "https://vidsrc.pm",
        "https://vidsrc.to",
        "https://vidsrc.xyz",
        "https://vidsrc.me",
        "https://vidsrc.net",
        "https://embed.su",
        "https://autoembed.cc"
    )
    private val cloudnestraUrl = "https://cloudnestra.com"

    // CDN placeholder replacements discovered from reverse engineering
    // Placeholders like {v1} are the BASE domain, subdomains are added in the URL
    // e.g., URL is "tmstr3.{v1}/pl/..." so {v1} = "quibblezoomfable.com"
    private val cdnDomains = mapOf(
        "{v1}" to "quibblezoomfable.com",
        "{v2}" to "quibblezoomfable.com",
        "{v3}" to "quibblezoomfable.com",
        "{v4}" to "quibblezoomfable.com",
        "{v5}" to "quibblezoomfable.com"
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * Extract the actual TMDB ID from prefixed format (e.g., "tv-12345" -> "12345")
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
            android.util.Log.e("WatchFlixSource", "getSection failed: ${e.message}", e)
            PagedResult(emptyList())
        }
    }

    private suspend fun getHomePage(): List<HomeSection> {
        val sections = mutableListOf<HomeSection>()

        try {
            android.util.Log.d("WatchFlix", "Loading home page via TMDB proxy")

            // Trending
            val trending = fetchFromTmdbProxy("trending/movie/day")
            if (trending.isNotEmpty()) {
                sections.add(HomeSection("Trending", trending.take(20)))
            }

            // Popular Movies
            val popular = fetchFromTmdbProxy("movie/popular")
            if (popular.isNotEmpty()) {
                sections.add(HomeSection("Popular Movies", popular.take(20)))
            }

            // Top Rated
            val topRated = fetchFromTmdbProxy("movie/top_rated")
            if (topRated.isNotEmpty()) {
                sections.add(HomeSection("Top Rated", topRated.take(20)))
            }

            // Upcoming
            val upcoming = fetchFromTmdbProxy("movie/upcoming")
            if (upcoming.isNotEmpty()) {
                sections.add(HomeSection("Coming Soon", upcoming.take(20)))
            }

            // Popular TV Shows
            val tvPopular = fetchFromTmdbProxy("tv/popular")
            if (tvPopular.isNotEmpty()) {
                sections.add(HomeSection("Popular TV Shows", tvPopular.take(20)))
            }

        } catch (e: Exception) {
            android.util.Log.e("WatchFlix", "Failed to load home page", e)
        }

        return sections
    }

    private suspend fun fetchFromTmdbProxy(endpoint: String): List<Video> {
        return try {
            val url = "$tmdbProxyUrl/$endpoint?api_key=$tmdbApiKey&language=en-US&page=1"
            android.util.Log.d("WatchFlix", "Fetching: $url")

            val response = httpClient.get(url)
            parseTmdbResponse(response, if (endpoint.contains("tv")) VideoType.TV_SERIES else VideoType.MOVIE)
        } catch (e: Exception) {
            android.util.Log.e("WatchFlix", "TMDB proxy fetch failed: $endpoint", e)
            emptyList()
        }
    }

    private fun parseTmdbResponse(response: String, type: VideoType): List<Video> {
        return try {
            val jsonObj = json.parseToJsonElement(response)
            if (jsonObj !is JsonObject) return emptyList()

            val results = jsonObj["results"]
            if (results !is JsonArray) return emptyList()

            results.mapNotNull { element ->
                try {
                    val item = element as? JsonObject ?: return@mapNotNull null
                    val tmdbId = (item["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                    val title = (item["title"] as? JsonPrimitive)?.content
                        ?: (item["name"] as? JsonPrimitive)?.content
                        ?: return@mapNotNull null
                    val posterPath = (item["poster_path"] as? JsonPrimitive)?.content
                    val releaseDate = (item["release_date"] as? JsonPrimitive)?.content
                        ?: (item["first_air_date"] as? JsonPrimitive)?.content
                    val voteAverage = (item["vote_average"] as? JsonPrimitive)?.content?.toFloatOrNull()

                    // Prefix ID with type for VideoDetailViewModel type detection
                    val typePrefix = if (type == VideoType.TV_SERIES) "tv" else "movie"
                    Video(
                        id = "$typePrefix-$tmdbId",
                        sourceId = this.id,
                        title = title,
                        url = "$baseUrl/$typePrefix/$tmdbId",
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
            android.util.Log.e("WatchFlix", "TMDB parse failed", e)
            emptyList()
        }
    }

    override suspend fun search(query: String, page: Int): PagedResult<Video> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val results = mutableListOf<Video>()

        try {
            // Search movies via TMDB proxy
            val movieUrl = "$tmdbProxyUrl/search/movie?api_key=$tmdbApiKey&language=en-US&query=$encodedQuery&page=$page"
            val movieResponse = httpClient.get(movieUrl)
            results.addAll(parseTmdbResponse(movieResponse, VideoType.MOVIE))

            // Search TV shows via TMDB proxy
            val tvUrl = "$tmdbProxyUrl/search/tv?api_key=$tmdbApiKey&language=en-US&query=$encodedQuery&page=$page"
            val tvResponse = httpClient.get(tvUrl)
            results.addAll(parseTmdbResponse(tvResponse, VideoType.TV_SERIES))

        } catch (e: Exception) {
            android.util.Log.e("WatchFlix", "Search failed", e)
        }

        val items = results.distinctBy { it.id }
        return PagedResult(items, hasMore = items.size >= 20)
    }

    override suspend fun getDetails(video: Video): Video {
        return try {
            // Fetch details from TMDB proxy
            val tmdbId = extractTmdbId(video.id)
            val isMovie = video.id.startsWith("movie-") || video.type == VideoType.MOVIE
            val type = if (isMovie) "movie" else "tv"
            val url = "$tmdbProxyUrl/$type/$tmdbId?api_key=$tmdbApiKey&language=en-US"
            android.util.Log.d("WatchFlix", "Getting details from TMDB: $type/$tmdbId")
            val response = httpClient.get(url)

            val jsonObj = json.parseToJsonElement(response) as? JsonObject ?: return video

            val title = (jsonObj["title"] as? JsonPrimitive)?.content
                ?: (jsonObj["name"] as? JsonPrimitive)?.content
                ?: video.title
            val posterPath = (jsonObj["poster_path"] as? JsonPrimitive)?.content
            val backdropPath = (jsonObj["backdrop_path"] as? JsonPrimitive)?.content
            val overview = (jsonObj["overview"] as? JsonPrimitive)?.content
            val releaseDate = (jsonObj["release_date"] as? JsonPrimitive)?.content
                ?: (jsonObj["first_air_date"] as? JsonPrimitive)?.content
            val voteAverage = (jsonObj["vote_average"] as? JsonPrimitive)?.content?.toFloatOrNull()
            val runtime = (jsonObj["runtime"] as? JsonPrimitive)?.content?.toIntOrNull()

            val genres = (jsonObj["genres"] as? JsonArray)?.mapNotNull { genre ->
                ((genre as? JsonObject)?.get("name") as? JsonPrimitive)?.content
            } ?: emptyList()

            // Get episode/season count for TV shows
            val episodeCount = if (!isMovie) {
                (jsonObj["number_of_episodes"] as? JsonPrimitive)?.content?.toIntOrNull()
            } else null

            val seasonCount = if (!isMovie) {
                (jsonObj["number_of_seasons"] as? JsonPrimitive)?.content?.toIntOrNull()
            } else null

            // Get status
            val statusStr = (jsonObj["status"] as? JsonPrimitive)?.content
            val status = when (statusStr?.lowercase()) {
                "released", "ended" -> com.omnistream.pluginapi.model.VideoStatus.COMPLETED
                "returning series", "in production" -> com.omnistream.pluginapi.model.VideoStatus.ONGOING
                "planned", "post production" -> com.omnistream.pluginapi.model.VideoStatus.UPCOMING
                else -> com.omnistream.pluginapi.model.VideoStatus.UNKNOWN
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
            ).also {
                android.util.Log.d("WatchFlix", "Details loaded: ${it.title}, poster: ${it.posterUrl}, backdrop: ${it.backdropUrl}")
            }
        } catch (e: Exception) {
            android.util.Log.e("WatchFlix", "Get details failed", e)
            video
        }
    }

    override suspend fun getEpisodes(video: Video): List<Episode> {
        // For movies, return single episode
        if (video.type == VideoType.MOVIE || video.url.contains("/movie/")) {
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

        // For TV series, fetch seasons and episodes from TMDB
        return try {
            val tmdbId = extractTmdbId(video.id)
            val episodes = mutableListOf<Episode>()
            val detailsUrl = "$tmdbProxyUrl/tv/$tmdbId?api_key=$tmdbApiKey&language=en-US"
            val response = httpClient.get(detailsUrl)
            val jsonObj = json.parseToJsonElement(response) as? JsonObject ?: return emptyList()

            val seasons = jsonObj["seasons"] as? JsonArray ?: return emptyList()
            val numberOfSeasons = (jsonObj["number_of_seasons"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 1

            // Fetch episodes for each season
            for (seasonNum in 1..numberOfSeasons) {
                try {
                    val seasonUrl = "$tmdbProxyUrl/tv/$tmdbId/season/$seasonNum?api_key=$tmdbApiKey&language=en-US"
                    val seasonResponse = httpClient.get(seasonUrl)
                    val seasonObj = json.parseToJsonElement(seasonResponse) as? JsonObject ?: continue

                    val episodesArray = seasonObj["episodes"] as? JsonArray ?: continue
                    episodesArray.forEach { epElement ->
                        val epObj = epElement as? JsonObject ?: return@forEach
                        val epNum = (epObj["episode_number"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return@forEach
                        val epTitle = (epObj["name"] as? JsonPrimitive)?.content

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
                    android.util.Log.e("WatchFlix", "Failed to fetch season $seasonNum", e)
                }
            }

            episodes.sortedWith(compareBy({ it.season }, { it.number }))
        } catch (e: Exception) {
            android.util.Log.e("WatchFlix", "Get episodes failed", e)
            emptyList()
        }
    }

    override suspend fun loadLinks(
        episode: Episode,
        subtitleCallback: (com.omnistream.pluginapi.model.Subtitle) -> Unit,
        callback: (VideoLink) -> Unit
    ): Boolean {
        val links = mutableListOf<VideoLink>()

        // Determine if movie or TV based on episode data
        val isMovie = episode.season == null || episode.season == 0
        val tmdbId = extractTmdbId(episode.videoId)

        android.util.Log.d("WatchFlix", "Getting links for TMDB ID: $tmdbId, isMovie: $isMovie")

        // Method 1: Try vidsrc-embed.ru directly (seen in network traffic)
        try {
            val vidsrcEmbedUrl = if (isMovie) {
                "https://vidsrc-embed.ru/embed/movie/$tmdbId"
            } else {
                "https://vidsrc-embed.ru/embed/tv/$tmdbId/${episode.season}/${episode.number}"
            }
            android.util.Log.d("WatchFlix", "Trying vidsrc-embed.ru: $vidsrcEmbedUrl")
            val response = httpClient.get(vidsrcEmbedUrl, headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to cloudnestraUrl,
                "Origin" to cloudnestraUrl
            ))
            android.util.Log.d("WatchFlix", "vidsrc-embed.ru response (${response.length} chars)")
            extractM3u8FromResponse(response, links)
            extractFromVidsrcEmbed(response, vidsrcEmbedUrl, links)
        } catch (e: Exception) {
            android.util.Log.d("WatchFlix", "vidsrc-embed.ru failed: ${e.message}")
        }

        // Method 2: Try 2embed.cc (often has simpler extraction)
        if (links.isEmpty()) {
            try {
                val embed2Url = if (isMovie) {
                    "https://2embed.cc/embed/movie/$tmdbId"
                } else {
                    "https://2embed.cc/embed/tv/$tmdbId/${episode.season}/${episode.number}"
                }
                android.util.Log.d("WatchFlix", "Trying 2embed.cc: $embed2Url")
                val response = httpClient.get(embed2Url, headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to baseUrl
                ))
                extractM3u8FromResponse(response, links)
            } catch (e: Exception) {
                android.util.Log.d("WatchFlix", "2embed.cc failed: ${e.message}")
            }
        }

        // Method 3: Try multiembed.mov
        if (links.isEmpty()) {
            try {
                val multiUrl = if (isMovie) {
                    "https://multiembed.mov/directstream.php?video_id=$tmdbId&tmdb=1"
                } else {
                    "https://multiembed.mov/directstream.php?video_id=$tmdbId&tmdb=1&s=${episode.season}&e=${episode.number}"
                }
                android.util.Log.d("WatchFlix", "Trying multiembed.mov: $multiUrl")
                val response = httpClient.get(multiUrl, headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to baseUrl
                ))
                extractM3u8FromResponse(response, links)
            } catch (e: Exception) {
                android.util.Log.d("WatchFlix", "multiembed.mov failed: ${e.message}")
            }
        }

        // Method 4: Try multiple embed providers with iframe following
        if (links.isEmpty()) {
            for (embedBase in embedProviders) {
                if (links.isNotEmpty()) break

                try {
                    val embedUrl = if (isMovie) {
                        "$embedBase/embed/movie/$tmdbId"
                    } else {
                        "$embedBase/embed/tv/$tmdbId/${episode.season}/${episode.number}"
                    }

                    android.util.Log.d("WatchFlix", "Trying provider: $embedUrl")
                    val response = httpClient.get(embedUrl, headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to "$baseUrl/"
                    ))

                    // Try to extract m3u8 directly from response
                    extractM3u8FromResponse(response, links)

                    // Try to follow iframes
                    val doc = Jsoup.parse(response)
                    doc.select("iframe[src]").forEach { iframe ->
                        if (links.isNotEmpty()) return@forEach
                        val iframeSrc = iframe.attr("src").let {
                            if (it.startsWith("//")) "https:$it" else it
                        }
                        if (iframeSrc.isNotBlank() && iframeSrc.startsWith("http")) {
                            try {
                                android.util.Log.d("WatchFlix", "Following iframe: $iframeSrc")
                                val iframeResponse = httpClient.get(iframeSrc, headers = mapOf(
                                    "User-Agent" to userAgent,
                                    "Referer" to embedBase
                                ))
                                extractM3u8FromResponse(iframeResponse, links)

                                // Try cloudnestra/prorcp extraction
                                extractFromCloudnestra(iframeResponse, iframeSrc, links)
                            } catch (e: Exception) {
                                android.util.Log.d("WatchFlix", "Iframe failed: ${e.message}")
                            }
                        }
                    }

                    // Look for data-hash and try cloudnestra
                    doc.select("[data-hash]").forEach { element ->
                        if (links.isNotEmpty()) return@forEach
                        val hash = element.attr("data-hash")
                        if (hash.isNotBlank()) {
                            try {
                                android.util.Log.d("WatchFlix", "Found data-hash: ${hash.take(50)}...")
                                val rcpUrl = "$cloudnestraUrl/rcp/$hash"
                                val rcpResponse = httpClient.get(rcpUrl, headers = mapOf(
                                    "User-Agent" to userAgent,
                                    "Referer" to embedBase
                                ))
                                extractFromCloudnestra(rcpResponse, rcpUrl, links)
                            } catch (e: Exception) {
                                android.util.Log.d("WatchFlix", "RCP failed: ${e.message}")
                            }
                        }
                    }

                } catch (e: Exception) {
                    android.util.Log.d("WatchFlix", "Provider $embedBase failed: ${e.message}")
                }
            }
        }

        // Method 5: Try vidsrc.pro API
        if (links.isEmpty()) {
            try {
                val apiUrl = if (isMovie) {
                    "https://vidsrc.pro/embed/movie/$tmdbId"
                } else {
                    "https://vidsrc.pro/embed/tv/$tmdbId/${episode.season}/${episode.number}"
                }
                android.util.Log.d("WatchFlix", "Trying vidsrc.pro: $apiUrl")
                val response = httpClient.get(apiUrl, headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to baseUrl
                ))
                extractM3u8FromResponse(response, links)
            } catch (e: Exception) {
                android.util.Log.d("WatchFlix", "vidsrc.pro failed: ${e.message}")
            }
        }

        // Method 6: Try superembed.stream
        if (links.isEmpty()) {
            try {
                val superUrl = if (isMovie) {
                    "https://superembed.stream/embed/movie/$tmdbId"
                } else {
                    "https://superembed.stream/embed/tv/$tmdbId/${episode.season}/${episode.number}"
                }
                android.util.Log.d("WatchFlix", "Trying superembed.stream: $superUrl")
                val response = httpClient.get(superUrl, headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to baseUrl
                ))
                extractM3u8FromResponse(response, links)
            } catch (e: Exception) {
                android.util.Log.d("WatchFlix", "superembed.stream failed: ${e.message}")
            }
        }

        val unique = links.distinctBy { it.url }
        unique.forEach { callback(it) }
        android.util.Log.d("WatchFlix", "Found ${unique.size} links total")
        return unique.isNotEmpty()
    }

    private suspend fun extractFromVidsrcEmbed(response: String, referer: String, links: MutableList<VideoLink>) {
        android.util.Log.d("WatchFlix", "Extracting from vidsrc-embed response...")

        // Try to find direct m3u8 URLs
        extractM3u8FromResponse(response, links)

        // Parse for any iframes or data attributes
        val doc = Jsoup.parse(response)

        // Look for iframes
        doc.select("iframe[src]").forEach { iframe ->
            val iframeSrc = iframe.attr("src").let {
                if (it.startsWith("//")) "https:$it" else it
            }
            if (iframeSrc.isNotBlank() && iframeSrc.startsWith("http")) {
                try {
                    android.util.Log.d("WatchFlix", "vidsrc-embed iframe: $iframeSrc")
                    val iframeResponse = httpClient.get(iframeSrc, headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to referer,
                        "Origin" to "https://vidsrc-embed.ru"
                    ))
                    extractM3u8FromResponse(iframeResponse, links)

                    // If iframe points to cloudnestra
                    if (iframeSrc.contains("cloudnestra")) {
                        extractFromCloudnestra(iframeResponse, iframeSrc, links)
                    }
                } catch (e: Exception) {
                    android.util.Log.d("WatchFlix", "vidsrc-embed iframe failed: ${e.message}")
                }
            }
        }

        // Look for data-hash attributes
        doc.select("[data-hash]").forEach { element ->
            val hash = element.attr("data-hash")
            if (hash.isNotBlank()) {
                try {
                    android.util.Log.d("WatchFlix", "vidsrc-embed data-hash: ${hash.take(50)}...")
                    // Try cloudnestra with the hash
                    val rcpUrl = "$cloudnestraUrl/rcp/$hash"
                    val rcpResponse = httpClient.get(rcpUrl, headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to referer,
                        "Origin" to "https://vidsrc-embed.ru"
                    ))
                    extractFromCloudnestra(rcpResponse, rcpUrl, links)
                } catch (e: Exception) {
                    android.util.Log.d("WatchFlix", "Hash extraction failed: ${e.message}")
                }
            }
        }

        // Look for script-based sources (Playerjs format)
        val scriptPattern = Regex("""new\s+Playerjs\s*\(\s*\{[^}]*file\s*:\s*["']([^"']+)["']""")
        scriptPattern.find(response)?.let { match ->
            var url = match.groupValues[1]
            cdnDomains.forEach { (placeholder, domain) ->
                url = url.replace(placeholder, domain)
            }
            if (url.startsWith("http") && !links.any { it.url == url }) {
                android.util.Log.d("WatchFlix", "Found Playerjs file: $url")
                links.add(VideoLink(
                    url = url,
                    quality = "Auto",
                    extractorName = "WatchFlix",
                    isM3u8 = url.contains(".m3u8"),
                    referer = cloudnestraUrl,
                    headers = mapOf(
                        "Referer" to cloudnestraUrl,
                        "Origin" to cloudnestraUrl
                    )
                ))
            }
        }
    }

    private fun extractM3u8FromResponse(response: String, links: MutableList<VideoLink>) {
        // Look for m3u8 URLs - standard pattern
        val m3u8Pattern = Regex("""(https?://[^\s"'<>]+\.m3u8[^\s"'<>]*)""")
        m3u8Pattern.findAll(response).forEach { match ->
            var url = match.groupValues[1]
                .replace("\\u002F", "/")
                .replace("\\/", "/")

            // Replace CDN placeholders
            cdnDomains.forEach { (placeholder, domain) ->
                url = url.replace(placeholder, domain)
            }

            if (url.contains(".m3u8") && !links.any { it.url == url }) {
                android.util.Log.d("WatchFlix", "Found m3u8: $url")
                links.add(VideoLink(
                    url = url,
                    quality = "Auto",
                    extractorName = "WatchFlix",
                    isM3u8 = true,
                    referer = cloudnestraUrl,
                    headers = mapOf("Referer" to "$cloudnestraUrl/", "Origin" to cloudnestraUrl)
                ))
            }
        }

        // Look for /pl/{encoded}/master.m3u8 pattern (gzip+base64 encoded path)
        // The encoded part starts with H4sI (gzip magic bytes in base64)
        val plPattern = Regex("""(https?://[^/\s"']+/pl/[A-Za-z0-9+/=_-]+/master\.m3u8)""")
        plPattern.findAll(response).forEach { match ->
            val url = match.groupValues[1]
                .replace("\\u002F", "/")
                .replace("\\/", "/")

            if (!links.any { it.url == url }) {
                android.util.Log.d("WatchFlix", "Found /pl/ m3u8: $url")
                links.add(VideoLink(
                    url = url,
                    quality = "Auto",
                    extractorName = "WatchFlix",
                    isM3u8 = true,
                    referer = cloudnestraUrl,
                    headers = mapOf("Referer" to "$cloudnestraUrl/", "Origin" to cloudnestraUrl)
                ))
            }
        }

        // Look for quibblezoomfable.com URLs specifically
        val cdnPattern = Regex("""(https?://[a-z0-9]+\.quibblezoomfable\.com/[^\s"'<>]+)""")
        cdnPattern.findAll(response).forEach { match ->
            val url = match.groupValues[1]
                .replace("\\u002F", "/")
                .replace("\\/", "/")

            if ((url.contains(".m3u8") || url.contains("/pl/")) && !links.any { it.url == url }) {
                android.util.Log.d("WatchFlix", "Found CDN URL: $url")
                links.add(VideoLink(
                    url = url,
                    quality = "Auto",
                    extractorName = "WatchFlix",
                    isM3u8 = true,
                    referer = cloudnestraUrl,
                    headers = mapOf("Referer" to "$cloudnestraUrl/", "Origin" to cloudnestraUrl)
                ))
            }
        }

        // Look for file: "url" pattern
        val filePattern = Regex("""file:\s*["']([^"']+)["']""")
        filePattern.find(response)?.let { match ->
            var url = match.groupValues[1].split(" or ").first().trim()
            cdnDomains.forEach { (placeholder, domain) ->
                url = url.replace(placeholder, domain)
            }
            if (url.startsWith("http") && (url.contains(".m3u8") || url.contains("/pl/")) && !links.any { it.url == url }) {
                android.util.Log.d("WatchFlix", "Found file m3u8: $url")
                links.add(VideoLink(
                    url = url,
                    quality = "Auto",
                    extractorName = "WatchFlix",
                    isM3u8 = true,
                    referer = cloudnestraUrl,
                    headers = mapOf("Referer" to "$cloudnestraUrl/", "Origin" to cloudnestraUrl)
                ))
            }
        }
    }

    private suspend fun extractFromCloudnestra(response: String, referer: String, links: MutableList<VideoLink>) {
        android.util.Log.d("WatchFlix", "Cloudnestra response (${response.length} chars): ${response.take(1000)}")

        // Look for prorcp hash with multiple patterns
        val prorcpPatterns = listOf(
            Regex("""/prorcp/([a-zA-Z0-9]+)"""),
            Regex("""loadIframe\s*\(\s*['"]([^'"]+)['"]"""),
            Regex("""data-src=["']([^"']*prorcp[^"']*)["']""")
        )

        for (pattern in prorcpPatterns) {
            val match = pattern.find(response)
            if (match != null) {
                val matchValue = match.groupValues[1]
                val prorcpPath = if (matchValue.startsWith("/")) matchValue else "/prorcp/$matchValue"
                val prorcpUrl = "$cloudnestraUrl$prorcpPath"

                try {
                    android.util.Log.d("WatchFlix", "Found prorcp URL: $prorcpUrl")
                    val prorcpResponse = httpClient.get(prorcpUrl, headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to referer
                    ))

                    android.util.Log.d("WatchFlix", "Prorcp response (${prorcpResponse.length} chars): ${prorcpResponse.take(1000)}")
                    extractM3u8FromResponse(prorcpResponse, links)

                    if (links.isNotEmpty()) break
                } catch (e: Exception) {
                    android.util.Log.d("WatchFlix", "Prorcp failed: ${e.message}")
                }
            }
        }

        // Also try direct extraction from the original response
        extractM3u8FromResponse(response, links)
    }

    override suspend fun ping(): Boolean {
        return try {
            httpClient.ping(baseUrl) > 0
        } catch (e: Exception) {
            false
        }
    }
}
