package com.omnistream.source.anime

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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.nodes.Element

/**
 * 9AnimeTv source for anime streaming.
 * Site: https://9animetv.to
 *
 * API flow (all plain GETs, no encryption, no VRF needed):
 * 1. GET /ajax/episode/list/{animeId}           → HTML with episode data-id attrs
 * 2. GET /ajax/episode/servers?episodeId={id}   → HTML with server data-id attrs
 * 3. GET /ajax/episode/sources?id={serverId}    → JSON { link: "rapid-cloud.co/.../{embedId}?z=" }
 * 4. GET rapid-cloud.co/embed-2/v2/e-1/getSources?id={embedId} → JSON { sources, tracks }
 * 5. Play sources[0].file (HLS master.m3u8)
 */
class NineAnimeTvSource(
    private val httpClient: SourceHttpClient
) : VideoSource {

    override val id = "9animetv"
    override val name = "9AnimeTV"
    override val baseUrl = "https://9animetv.to"
    override val lang = "en"
    override val supportedTypes = setOf(VideoType.ANIME)

    private val rapidCloudBase = "https://rapid-cloud.co"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
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
            android.util.Log.e("NineAnimeTvSource", "getSection failed: ${e.message}", e)
            PagedResult(emptyList())
        }
    }

    private suspend fun getHomePage(): List<HomeSection> {
        val sections = mutableListOf<HomeSection>()
        try {
            val doc = httpClient.getDocument(baseUrl)

            // Recently updated
            val recent = doc.select(".film_list-wrap .flw-item, .ulclear.appfilter .flw-item")
                .mapNotNull { parseAnimeCard(it) }
                .distinctBy { it.id }
                .take(20)
            if (recent.isNotEmpty()) sections.add(HomeSection("Recently Updated", recent))

            // Top Airing
            try {
                val airingDoc = httpClient.getDocument("$baseUrl/top-airing")
                val airing = airingDoc.select(".film_list-wrap .flw-item")
                    .mapNotNull { parseAnimeCard(it) }
                    .distinctBy { it.id }
                    .take(20)
                if (airing.isNotEmpty()) sections.add(HomeSection("Top Airing", airing))
            } catch (_: Exception) {}

            // Most Popular
            try {
                val popularDoc = httpClient.getDocument("$baseUrl/most-popular")
                val popular = popularDoc.select(".film_list-wrap .flw-item")
                    .mapNotNull { parseAnimeCard(it) }
                    .distinctBy { it.id }
                    .take(20)
                if (popular.isNotEmpty()) sections.add(HomeSection("Most Popular", popular))
            } catch (_: Exception) {}

        } catch (e: Exception) {
            android.util.Log.e("NineAnimeTv", "getHomePage failed", e)
        }
        return sections
    }

    override suspend fun search(query: String, page: Int): PagedResult<Video> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        return try {
            val items = httpClient.getDocument("$baseUrl/search?keyword=$encoded&page=$page")
                .select(".film_list-wrap .flw-item")
                .mapNotNull { parseAnimeCard(it) }
                .distinctBy { it.id }
            PagedResult(items, hasMore = items.size >= 20)
        } catch (e: Exception) {
            android.util.Log.e("NineAnimeTv", "search failed", e)
            PagedResult(emptyList())
        }
    }

    private fun parseAnimeCard(element: Element): Video? {
        val link = element.selectFirst("a[href*='/watch/']")
            ?: element.selectFirst("a[href*='/anime/']")
            ?: element.selectFirst("a")
            ?: return null

        val href = link.attr("href")
        if (href.isBlank()) return null

        // Use full slug as ID: /watch/one-piece-100 → "one-piece-100"
        // The trailing number is extracted when needed for AJAX calls
        val animeId = href.trimEnd('/').substringAfterLast("/").ifBlank { return null }

        val title = element.selectFirst(".film-name, h3.film-name, .dynamic-name")?.text()?.trim()
            ?: link.attr("title").ifBlank { link.text().trim() }
        if (title.isBlank()) return null

        val posterUrl = element.selectFirst("img")?.let { img ->
            img.attr("data-src").ifBlank { img.attr("src") }
        }

        val typeText = element.selectFirst(".tick-item.tick-type")?.text()?.uppercase() ?: ""
        val type = if (typeText.contains("MOVIE")) VideoType.MOVIE else VideoType.ANIME

        val epCount = element.selectFirst(".tick-item.tick-sub, .tick-sub")?.text()?.trim()?.toIntOrNull()

        return Video(
            id = animeId,
            sourceId = id,
            title = title,
            url = if (href.startsWith("http")) href else "$baseUrl$href",
            type = type,
            posterUrl = posterUrl,
            episodeCount = epCount
        )
    }

    override suspend fun getDetails(video: Video): Video {
        return try {
            val doc = httpClient.getDocument(video.url)

            val title = doc.selectFirst("h2.film-name, .film-name.dynamic-name")?.text()?.trim()
                ?: video.title

            val posterUrl = doc.selectFirst(".film-poster img")?.let { img ->
                img.attr("src").ifBlank { img.attr("data-src") }
            } ?: video.posterUrl

            val description = doc.selectFirst(".film-description .text")?.text()?.trim()
                ?: doc.selectFirst(".film-description")?.text()?.trim()

            val genres = doc.select("div.item a[href*='/genre/']")
                .map { it.text().trim() }.filter { it.isNotBlank() }

            val infoItems = doc.select(".film-stats .tick-item, .item-info")
            val infoText = doc.select(".anisc-info .item").joinToString(" ") { it.text() }

            val status = when {
                infoText.contains("Airing", true) || infoText.contains("Ongoing", true) -> VideoStatus.ONGOING
                infoText.contains("Finished", true) || infoText.contains("Completed", true) -> VideoStatus.COMPLETED
                else -> video.status
            }

            val year = Regex("""(\d{4})""").find(
                doc.selectFirst(".item:contains(Aired) span, .item:contains(Premiered) span")?.text() ?: ""
            )?.groupValues?.get(1)?.toIntOrNull()

            val epCount = doc.selectFirst(".item:contains(Episodes) span")?.text()?.trim()?.toIntOrNull()
                ?: video.episodeCount

            video.copy(
                title = title,
                posterUrl = posterUrl,
                description = description,
                genres = genres,
                status = status,
                year = year,
                episodeCount = epCount
            )
        } catch (e: Exception) {
            android.util.Log.e("NineAnimeTv", "getDetails failed", e)
            video
        }
    }

    override suspend fun getEpisodes(video: Video): List<Episode> {
        return try {
            // animeId is the trailing number in the URL
            val animeId = video.url.trimEnd('/').substringAfterLast("-")
            if (animeId.toIntOrNull() == null) {
                android.util.Log.e("NineAnimeTv", "Could not extract animeId from ${video.url}")
                return emptyList()
            }

            val response = httpClient.get("$baseUrl/ajax/episode/list/$animeId",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to video.url))
            val htmlContent = json.parseToJsonElement(response).jsonObject["html"]?.jsonPrimitive?.content
                ?: return emptyList()

            val doc = org.jsoup.Jsoup.parseBodyFragment(htmlContent)
            val episodes = mutableListOf<Episode>()

            doc.select("a[data-id]").forEach { el ->
                val epId = el.attr("data-id")
                if (epId.isBlank()) return@forEach

                val epNum = el.attr("data-number").toIntOrNull()
                    ?: Regex("""Ep\.?\s*(\d+)""", RegexOption.IGNORE_CASE)
                        .find(el.text())?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@forEach

                val epTitle = el.attr("title").ifBlank { "Episode $epNum" }
                val href = el.attr("href").let {
                    if (it.startsWith("http")) it else "$baseUrl$it"
                }

                episodes.add(Episode(
                    id = epId,
                    videoId = video.id,
                    sourceId = id,
                    url = href,
                    title = epTitle,
                    number = epNum
                ))
            }

            episodes.distinctBy { it.number }.sortedBy { it.number }.also {
                android.util.Log.d("NineAnimeTv", "Found ${it.size} episodes for ${video.title}")
            }
        } catch (e: Exception) {
            android.util.Log.e("NineAnimeTv", "getEpisodes failed", e)
            emptyList()
        }
    }

    override suspend fun loadLinks(
        episode: Episode,
        subtitleCallback: (Subtitle) -> Unit,
        callback: (VideoLink) -> Unit
    ): Boolean {
        val links = mutableListOf<VideoLink>()

        try {
            // Step 1: Get server list for this episode
            // episode.id is the numeric episodeId from data-id
            val serversResponse = httpClient.get(
                "$baseUrl/ajax/episode/servers?episodeId=${episode.id}",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to episode.url)
            )
            val serversHtml = json.parseToJsonElement(serversResponse).jsonObject["html"]?.jsonPrimitive?.content
                ?: return false

            val serversDoc = org.jsoup.Jsoup.parseBodyFragment(serversHtml)

            // Step 2: Find RapidCloud server (data-server-id="4") — best quality, no encryption
            val serverItem = serversDoc.selectFirst("[data-server-id='4']")
                ?: serversDoc.selectFirst(".server-item[data-id]")  // fallback: any server
                ?: return false

            val serverId = serverItem.attr("data-id").ifBlank { return false }
            android.util.Log.d("NineAnimeTv", "Using server id=$serverId")

            // Step 3: Get embed link for this server
            val sourcesResponse = httpClient.get(
                "$baseUrl/ajax/episode/sources?id=$serverId",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to episode.url)
            )
            val sourcesObj = json.parseToJsonElement(sourcesResponse).jsonObject
            val embedLink = sourcesObj["link"]?.jsonPrimitive?.content ?: return false

            // Extract embedId from: https://rapid-cloud.co/embed-2/v2/e-1/{embedId}?z=
            val embedId = embedLink.substringAfter("/e-1/").substringBefore("?")
            if (embedId.isBlank()) return false
            android.util.Log.d("NineAnimeTv", "embedId=$embedId")

            // Step 4: Fetch video sources from RapidCloud
            val getSourcesUrl = "$rapidCloudBase/embed-2/v2/e-1/getSources?id=$embedId"
            val getSourcesResponse = httpClient.get(getSourcesUrl, headers = mapOf(
                "Referer" to "$rapidCloudBase/",
                "X-Requested-With" to "XMLHttpRequest"
            ))
            val getSourcesObj = json.parseToJsonElement(getSourcesResponse).jsonObject

            // Parse subtitle tracks first
            val subtitles = mutableListOf<Subtitle>()
            getSourcesObj["tracks"]?.jsonArray?.forEach { trackEl ->
                val track = trackEl.jsonObject
                val kind = track["kind"]?.jsonPrimitive?.content ?: ""
                if (kind != "captions" && kind != "subtitles") return@forEach
                val fileUrl = track["file"]?.jsonPrimitive?.content ?: return@forEach
                val label = track["label"]?.jsonPrimitive?.content ?: return@forEach
                val isDefault = track["default"]?.jsonPrimitive?.content?.toBoolean() ?: false
                subtitles.add(Subtitle(
                    url = fileUrl,
                    language = label.lowercase().take(2),
                    label = label,
                    isDefault = isDefault
                ))
            }
            android.util.Log.d("NineAnimeTv", "Found ${subtitles.size} subtitle tracks")
            subtitles.forEach { subtitleCallback(it) }

            // Parse video sources
            val sources = getSourcesObj["sources"]
            if (sources != null && sources.toString() != "[]") {
                sources.jsonArray.forEach { srcEl ->
                    val src = srcEl.jsonObject
                    val file = src["file"]?.jsonPrimitive?.content ?: return@forEach
                    val type = src["type"]?.jsonPrimitive?.content ?: "hls"
                    links.add(VideoLink(
                        url = file,
                        quality = "Auto",
                        extractorName = "RapidCloud",
                        referer = "$rapidCloudBase/",
                        headers = mapOf("Referer" to "$rapidCloudBase/"),
                        isM3u8 = type == "hls" || file.contains(".m3u8"),
                        subtitles = subtitles
                    ))
                }
            }

        } catch (e: Exception) {
            android.util.Log.e("NineAnimeTv", "getLinks failed", e)
        }

        val unique = links.distinctBy { it.url }
        unique.forEach { callback(it) }
        android.util.Log.d("NineAnimeTv", "Found ${unique.size} links for ep ${episode.number}")
        return unique.isNotEmpty()
    }

    override suspend fun ping(): Boolean {
        return try {
            httpClient.ping(baseUrl) > 0
        } catch (_: Exception) {
            false
        }
    }
}
