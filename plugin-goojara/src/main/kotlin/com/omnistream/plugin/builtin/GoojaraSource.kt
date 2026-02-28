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
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Goojara source — English movies and TV shows.
 * Site: https://ww1.goojara.to
 *
 * URL scheme (base62 short codes):
 *   /mXXXXXX = movie player page
 *   /sXXXXXX = TV series listing page
 *   /eXXXXXX = TV episode player page
 *
 * Confirmed selectors (via omni-recon):
 *   Listing cards   : .dflex (each element is one card)
 *   Poster          : img inside card — URL is md.goojara.to/{numericId}.jpg
 *                     (NOT derivable from the short code slug!)
 *   Title           : h1 on detail page
 *   Search          : POST /xmre.php  param q={query}
 *   Player iframe   : iframe[src] → vidsrc.net/embed/movie?imdb={id}
 *   Stream          : M3U8 from tmstr2.neonhorizonworkshops.com (JS-loaded by vidsrc)
 *
 * Note on stream extraction:
 *   vidsrc.net loads the stream URL via JS (XHR). Plain HTTP fetch may not
 *   capture it. The M3U8 lives at tmstr2.neonhorizonworkshops.com/pl/{base64path}.
 *   If plain extraction fails, a WebView extractor would be needed for this source.
 */
class GoojaraSource(
    private val httpClient: SourceHttpClient
) : VideoSource {

    override val id = "goojara"
    override val name = "Goojara"
    override val baseUrl = "https://ww1.goojara.to"
    override val lang = "en"
    override val supportedTypes = setOf(VideoType.MOVIE, VideoType.TV_SERIES)

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
            android.util.Log.e("GoojaraSource", "getSection failed: ${e.message}", e)
            PagedResult(emptyList())
        }
    }

    private suspend fun getHomePage(): List<HomeSection> {
        val sections = mutableListOf<HomeSection>()

        // Homepage featured items
        try {
            val featured = fetchVideoList(baseUrl)
            if (featured.isNotEmpty()) sections.add(HomeSection("Featured", featured.take(20)))
        } catch (e: Exception) {
            android.util.Log.e("GoojaraSource", "getHomePage featured failed", e)
        }

        // Movie listing page
        try {
            val movies = fetchVideoList("$baseUrl/watch-movies")
            if (movies.isNotEmpty()) sections.add(HomeSection("Movies", movies.take(20)))
        } catch (e: Exception) {
            android.util.Log.e("GoojaraSource", "getHomePage movies failed", e)
        }

        // TV series listing page
        try {
            val series = fetchVideoList("$baseUrl/watch-series")
            if (series.isNotEmpty()) sections.add(HomeSection("TV Series", series.take(20)))
        } catch (e: Exception) {
            android.util.Log.e("GoojaraSource", "getHomePage series failed", e)
        }

        return sections
    }

    /**
     * Fetches [url] and parses video cards.
     * Uses .dflex selector (confirmed by omni-recon). Falls back to .item / article if needed.
     */
    private suspend fun fetchVideoList(url: String): List<Video> {
        return try {
            val doc = httpClient.getDocument(url)

            var cards = doc.select(".dflex")
            if (cards.isEmpty()) cards = doc.select("article, .item, .ml-item, .movie-item")

            cards.mapNotNull { parseCard(it) }.distinctBy { it.id }
        } catch (e: Exception) {
            android.util.Log.e("GoojaraSource", "fetchVideoList failed: $url", e)
            emptyList()
        }
    }

    /**
     * Parses a single card element from a listing page.
     *
     * Goojara homepage cards link to /episode/{shortCode} (episode path format).
     * We strip /episode/ and use just the shortCode so the URL resolves correctly.
     *
     * IMPORTANT: poster URL cannot be constructed from the slug — it uses a different
     * numeric ID (e.g. md.goojara.to/10230729.jpg vs slug mB4Q0R). Must read from img tag.
     */
    private val episodePathRe = Regex("""/episode/([A-Za-z0-9]{3,12})""")

    private fun parseCard(el: Element): Video? {
        val link = el.selectFirst("a") ?: return null
        val href = link.attr("href").ifBlank { return null }

        // Strip /episode/ prefix if present — goojara cards use /episode/{code} but
        // the actual player page lives at /{code} directly
        val shortCode = episodePathRe.find(href)?.groupValues?.get(1)
        val path = if (shortCode != null) "/$shortCode"
                   else if (href.startsWith("http")) href.substringAfter(baseUrl)
                   else href
        val videoId = path.trimStart('/').ifBlank { return null }

        // Poster: read directly from img — cannot be derived from slug
        val poster = el.selectFirst("img")?.let { img ->
            // Try all common lazy-load attributes
            val src = img.attr("data-src")
                .ifBlank { img.attr("data-lazy-src") }
                .ifBlank { img.attr("data-original") }
                .ifBlank { img.attr("src") }
                .let { if (it.startsWith("data:") || it.isBlank()) "" else it }
            when {
                src.startsWith("//")   -> "https:$src"
                src.startsWith("/")    -> "$baseUrl$src"
                src.startsWith("http") -> src
                else                   -> null
            }
        }

        val title = el.selectFirst(
            "h2, h3, h4, .title, span.tt, .dynamic-name, .movie-title"
        )?.text()?.trim()?.ifBlank { null }
            ?: link.attr("title").ifBlank { null }
            ?: link.text().trim().ifBlank { return null }

        val fullUrl = "$baseUrl/$videoId"

        // s-prefix = TV series, everything else assumed movie/episode
        val type = if (videoId.startsWith("s")) VideoType.TV_SERIES else VideoType.MOVIE

        return Video(
            id = videoId,
            sourceId = id,
            title = title,
            url = fullUrl,
            type = type,
            posterUrl = poster
        )
    }

    override suspend fun search(query: String, page: Int): PagedResult<Video> {
        return try {
            val response = httpClient.post(
                url = "$baseUrl/xmre.php",
                data = mapOf("q" to query),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to baseUrl
                )
            )

            val doc = Jsoup.parse(response)

            val items = doc.select("a[href]").mapNotNull { element ->
                try {
                    val href = element.attr("href")
                    val path = if (href.startsWith("http")) href.substringAfter(baseUrl) else href
                    val videoId = path.trimStart('/').ifBlank { return@mapNotNull null }

                    val isMovie = videoId.startsWith("m")
                    val isSeries = videoId.startsWith("s")
                    if (!isMovie && !isSeries) return@mapNotNull null

                    val rawTitle = element.text().trim().ifBlank { return@mapNotNull null }
                    val yearMatch = Regex("""\((\d{4})\)""").find(rawTitle)
                    val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()
                    val cleanTitle = rawTitle.replace(Regex("""\s*\(\d{4}\)\s*"""), "").trim()

                    Video(
                        id = videoId,
                        sourceId = id,
                        title = cleanTitle,
                        url = "$baseUrl/$videoId",
                        type = if (isSeries) VideoType.TV_SERIES else VideoType.MOVIE,
                        posterUrl = null,
                        year = year
                    )
                } catch (e: Exception) {
                    null
                }
            }.distinctBy { it.id }
            PagedResult(items, hasMore = items.size >= 20)
        } catch (e: Exception) {
            android.util.Log.e("GoojaraSource", "search failed", e)
            PagedResult(emptyList())
        }
    }

    override suspend fun getDetails(video: Video): Video {
        return try {
            val doc = httpClient.getDocument(video.url)

            val title = doc.selectFirst("h1, .entry-title, .title, meta[property=og:title]")
                ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }
                ?.trim() ?: video.title

            // Log first img tags to find the right poster selector
            val allImgs = doc.select("img").take(5)
            allImgs.forEach { img ->
                android.util.Log.e("GoojaraSource", "img: src=${img.attr("src")} data-src=${img.attr("data-src")} class=${img.attr("class")}")
            }

            val poster = video.posterUrl
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst("meta[name=twitter:image]")?.attr("content")
                // goojara image CDN is md.goojara.to — target it directly
                ?: doc.selectFirst("img[src*='md.goojara.to'], img[data-src*='md.goojara.to']")
                    ?.let { img -> img.attr("src").ifBlank { img.attr("data-src") } }
                ?: doc.selectFirst(".poster img, .thumb img, img.poster, img[class*=cover], img[class*=thumb]")
                    ?.let { img -> img.attr("data-src").ifBlank { img.attr("src") } }

            val description = doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("p.description, div.synopsis, .plot, .desc, div.info p")
                    ?.text()?.trim()

            android.util.Log.e("GoojaraSource", "getDetails: title=$title poster=$poster")

            val year = Regex("""(\d{4})""").find(
                doc.selectFirst("span.year, .info, .extra, h2, h3, title")?.text() ?: ""
            )?.groupValues?.get(1)?.toIntOrNull() ?: video.year

            val genres = doc.select("a[href*='/genre/'], .genres a, a[href*='/cat/']")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }

            video.copy(
                title = title,
                posterUrl = poster,
                description = description,
                year = year,
                genres = genres
            )
        } catch (e: Exception) {
            android.util.Log.e("GoojaraSource", "getDetails failed", e)
            video
        }
    }

    override suspend fun getEpisodes(video: Video): List<Episode> {
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

        return try {
            val doc = httpClient.getDocument(video.url)
            val episodes = mutableListOf<Episode>()

            // Goojara episode links come in two forms:
            //   /episode/{shortCode}  — path-style (strip /episode/, use /$shortCode directly)
            //   /eXXXXXX             — short code with e-prefix
            // Both resolve to $baseUrl/{shortCode} as the actual player page.
            val episodePathRe = Regex("""/episode/([A-Za-z0-9]{3,10})""")
            val shortCodeRe   = Regex("""^[a-z][A-Za-z0-9]{3,9}$""")

            doc.select("a[href]").forEach { element ->
                try {
                    val href = element.attr("href")

                    // Determine the short code and final player URL
                    val (shortCode, epUrl) = when {
                        // /episode/mB4Q0R  →  mB4Q0R, https://ww1.goojara.to/mB4Q0R
                        episodePathRe.containsMatchIn(href) -> {
                            val code = episodePathRe.find(href)!!.groupValues[1]
                            code to "$baseUrl/$code"
                        }
                        // /eXXXXXX or absolute URL with short code
                        else -> {
                            val path = if (href.startsWith("http")) href.substringAfter(baseUrl) else href
                            val code = path.trimStart('/')
                            if (!shortCodeRe.matches(code)) return@forEach
                            code to (if (href.startsWith("http")) href else "$baseUrl/$code")
                        }
                    }

                    val epText = element.text().trim()
                    val match = Regex("""S(\d+)\s*[Ee](\d+)""").find(epText)
                        ?: Regex("""(\d+)[xX](\d+)""").find(epText)

                    val season = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val epNum = match?.groupValues?.get(2)?.toIntOrNull()
                        ?: Regex("""[Ee]p?\.?\s*(\d+)""", RegexOption.IGNORE_CASE).find(epText)
                            ?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""(\d+)""").find(epText)?.groupValues?.get(1)?.toIntOrNull()
                        ?: return@forEach

                    android.util.Log.e("GoojaraSource", "Episode: $epText → $epUrl")

                    episodes.add(
                        Episode(
                            id = shortCode,
                            videoId = video.id,
                            sourceId = id,
                            url = epUrl,
                            title = epText.ifBlank { "S${season}E$epNum" },
                            number = epNum,
                            season = season
                        )
                    )
                } catch (e: Exception) {
                    // skip malformed entry
                }
            }

            episodes.distinctBy { it.id }
                .sortedWith(compareBy({ it.season }, { it.number }))
                .also { android.util.Log.e("GoojaraSource", "Found ${it.size} episodes for ${video.url}") }
        } catch (e: Exception) {
            android.util.Log.e("GoojaraSource", "getEpisodes failed", e)
            emptyList()
        }
    }

    override suspend fun loadLinks(
        episode: Episode,
        subtitleCallback: (com.omnistream.pluginapi.model.Subtitle) -> Unit,
        callback: (VideoLink) -> Unit
    ): Boolean {
        val links = mutableListOf<VideoLink>()

        // Normalize: /episode/{code} → /{code}
        val playerUrl = episodePathRe.find(episode.url)
            ?.let { "$baseUrl/${it.groupValues[1]}" }
            ?: episode.url

        android.util.Log.e("GoojaraSource", "getLinks: playerUrl=$playerUrl")

        try {
            val doc = httpClient.getDocument(playerUrl)

            // Extract iframe embeds (vidsrc.net on goojara)
            doc.select("iframe[src], iframe[data-src]").forEach { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.isBlank()) return@forEach

                val embedUrl = when {
                    src.startsWith("//")   -> "https:$src"
                    src.startsWith("/")    -> "$baseUrl$src"
                    src.startsWith("http") -> src
                    else                   -> return@forEach
                }

                // Pass playerUrl as Referer — vidsrc checks the Referer header
                links.addAll(extractFromEmbed(embedUrl, referer = playerUrl))
            }

            // Also scan inline scripts on the goojara page
            doc.select("script").forEach { script ->
                extractLinksFromText(script.data(), links, referer = playerUrl)
            }

        } catch (e: Exception) {
            android.util.Log.e("GoojaraSource", "getLinks failed: ${episode.url}", e)
        }

        val unique = links.distinctBy { it.url }
        unique.forEach { callback(it) }
        android.util.Log.e("GoojaraSource", "Found ${unique.size} links for ${episode.url}")
        return unique.isNotEmpty()
    }

    /**
     * Fetches an embed page and scans it for stream URLs.
     *
     * NOTE: vidsrc.net loads streams via XHR after JS execution.
     * Plain HTTP captures the URL only if it appears in the page source
     * (script var, data attribute, etc). If this returns empty, a WebView
     * extractor would be needed.
     *
     * [referer] should be the goojara episode page URL, not baseUrl.
     */
    private suspend fun extractFromEmbed(embedUrl: String, referer: String): List<VideoLink> {
        val links = mutableListOf<VideoLink>()

        try {
            val response = httpClient.get(
                embedUrl,
                headers = mapOf(
                    "Referer" to referer,
                    "Origin"  to baseUrl
                )
            )
            extractLinksFromText(response, links, referer = embedUrl)
        } catch (e: Exception) {
            android.util.Log.e("GoojaraSource", "extractFromEmbed failed: $embedUrl", e)
        }

        return links
    }

    private fun extractLinksFromText(
        content: String,
        links: MutableList<VideoLink>,
        referer: String
    ) {
        // M3U8 stream URLs
        Regex("""["'](https?://[^"'\s]+\.m3u8[^"'\s]*)["']""").findAll(content).forEach {
            val url = it.groupValues[1].replace("\\/", "/")
            links.add(VideoLink(url = url, quality = qualityFrom(url), extractorName = "Goojara", isM3u8 = true, referer = referer))
        }

        // MP4 direct URLs
        Regex("""["'](https?://[^"'\s]+\.mp4[^"'\s]*)["']""").findAll(content).forEach {
            val url = it.groupValues[1].replace("\\/", "/")
            links.add(VideoLink(url = url, quality = qualityFrom(url), extractorName = "Goojara", isM3u8 = false, referer = referer))
        }

        // JW Player / video.js file:"url" pattern
        Regex("""["']?file["']?\s*:\s*["']([^"']+)["']""").findAll(content).forEach {
            val url = it.groupValues[1].replace("\\/", "/")
            if (url.contains(".m3u8") || url.contains(".mp4")) {
                links.add(VideoLink(url = url, quality = qualityFrom(url), extractorName = "Goojara", isM3u8 = url.contains(".m3u8"), referer = referer))
            }
        }
    }

    private fun qualityFrom(url: String): String = when {
        url.contains("2160") || url.contains("4k", true) -> "4K"
        url.contains("1080") -> "1080p"
        url.contains("720")  -> "720p"
        url.contains("480")  -> "480p"
        url.contains("360")  -> "360p"
        else                 -> "Auto"
    }

    override suspend fun ping(): Boolean = try {
        httpClient.ping(baseUrl) > 0
    } catch (e: Exception) {
        false
    }
}
