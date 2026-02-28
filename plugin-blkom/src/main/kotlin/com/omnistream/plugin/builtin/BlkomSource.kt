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
import org.jsoup.nodes.Element

/**
 * Blkom source — Arabic anime streaming site.
 * Site: https://blkom.com
 *
 * Arabic-dubbed and Arabic-subbed anime. Protected by Cloudflare Turnstile —
 * handled transparently by OmniHttpClient + CloudFlareTurnstileResolver.
 *
 * Confirmed selectors (via omni-recon):
 *   Homepage cards : .recent-episodes  (30 items, episode update cards)
 *   Card href      : /watch/{slug}/{episode-number}
 *   Anime URL      : /anime/{slug}   (derived from card href)
 *   Detail title   : h1
 *   Episode page   : /watch/{slug}/{episode-number}
 *   Iframe         : iframe[src]  →  bkvideo.online/embedvideo/{id}?s={token}
 *   Stream         : cdn.bkvideo.online/videos/{path}/480p.mp4?expires=...&token=...
 *   Search         : GET /search?s={query}
 */
class BlkomSource(
    private val httpClient: SourceHttpClient
) : VideoSource {

    override val id = "blkom"
    override val name = "بلكم (Blkom)"
    override val baseUrl = "https://blkom.com"
    override val lang = "ar"
    override val supportedTypes = setOf(VideoType.ANIME)

    // Episode URL pattern: /watch/{slug}/{episode-number}
    private val WATCH_SLUG_RE = Regex("""/watch/([^/]+)/\d+""")

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
            android.util.Log.e("BlkomSource", "getSection failed: ${e.message}", e)
            PagedResult(emptyList())
        }
    }

    private suspend fun getHomePage(): List<HomeSection> {
        val sections = mutableListOf<HomeSection>()

        try {
            val doc = httpClient.getDocument(baseUrl)
            // Confirmed by omni-recon: .recent-episodes holds episode-update cards
            val recentItems = doc.select(".recent-episodes")
                .flatMap { it.children() }
                .mapNotNull { parseAnimeCard(it) }
                .distinctBy { it.id }

            if (recentItems.isNotEmpty()) {
                sections.add(HomeSection("حلقات جديدة", recentItems.take(20)))
            }

            // Full anime list page — قائمة الأنمي
            try {
                val listDoc = httpClient.getDocument("$baseUrl/anime-list/")
                val listItems = listDoc.select(
                    "div.items article, div.listupd article, article.bs, " +
                    ".anime-list li, div.anime-list .anime-card"
                ).mapNotNull { parseAnimeCard(it) }.distinctBy { it.id }

                if (listItems.isNotEmpty()) {
                    sections.add(HomeSection("قائمة الأنمي", listItems.take(20)))
                }
            } catch (e: Exception) {
                android.util.Log.w("BlkomSource", "Could not load anime list: ${e.message}")
            }

        } catch (e: Exception) {
            android.util.Log.e("BlkomSource", "getHomePage failed: ${e.message}")
        }

        return sections
    }

    override suspend fun search(query: String, page: Int): PagedResult<Video> {
        return try {
            // Confirmed by omni-recon: GET /search?s={query}
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/search?s=$encodedQuery"
            val doc = httpClient.getDocument(url)

            val items = doc.select(
                "div.result-item article, div.listupd article, " +
                "div.items article, article.bs, .recent-episodes > *"
            ).mapNotNull { parseAnimeCard(it) }.distinctBy { it.id }

            PagedResult(items, hasMore = items.size >= 20)
        } catch (e: Exception) {
            android.util.Log.e("BlkomSource", "search failed: ${e.message}")
            PagedResult(emptyList())
        }
    }

    override suspend fun getDetails(video: Video): Video {
        return try {
            val doc = httpClient.getDocument(video.url)

            // Confirmed by omni-recon: h1 holds the series title
            val title = doc.selectFirst("h1")?.text()?.trim()
                ?.replace(Regex("""\s*\(ANIME\)\s*""", RegexOption.IGNORE_CASE), "")
                ?.trim()
                ?: video.title

            val poster = doc.selectFirst(
                "div.poster img, div.thumb img, img.poster, img[class*=cover], img[class*=thumb]"
            )?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                ?: video.posterUrl

            val description = doc.selectFirst(
                "div.entry-content p, div.sinopsis p, div.desc, div.summary"
            )?.text()?.trim()

            val infoText = doc.select(
                "div.info-content span, div.details div.custom_fields, div.spe span"
            ).text()

            val status = when {
                infoText.contains("مكتمل", ignoreCase = true) ||
                infoText.contains("Completed", ignoreCase = true) -> VideoStatus.COMPLETED
                infoText.contains("مستمر", ignoreCase = true) ||
                infoText.contains("Ongoing", ignoreCase = true)  -> VideoStatus.ONGOING
                else -> video.status
            }

            val genres = doc.select(
                "div.genres a, span.genres a, div.genre-list a, .genxed a"
            ).map { it.text().trim() }.filter { it.isNotBlank() }

            val year = Regex("""(\d{4})""").find(infoText)?.groupValues?.get(1)?.toIntOrNull()

            // Try known episode list selectors
            val epCount = doc.select(
                "ul.episodios li, div.eplister ul li, div.ep-list li, " +
                "ul.episodes-list li, ul.listeps li, .eps-list a, " +
                "select.episodes option, a[href*='/watch/']"
            ).size.takeIf { it > 0 }

            video.copy(
                title = title,
                posterUrl = poster,
                description = description,
                genres = genres,
                status = status,
                year = year,
                episodeCount = epCount ?: video.episodeCount
            )
        } catch (e: Exception) {
            android.util.Log.e("BlkomSource", "getDetails failed: ${e.message}")
            video
        }
    }

    override suspend fun getEpisodes(video: Video): List<Episode> {
        return try {
            val doc = httpClient.getDocument(video.url)

            // Try static episode list selectors
            val staticSelectors = listOf(
                "ul.episodios li",
                "div.eplister ul li",
                "div.ep-list li",
                "ul.episodes-list li",
                "ul.listeps li",
                ".eps-list a",
                "select.episodes option"
            )

            for (selector in staticSelectors) {
                val items = doc.select(selector)
                if (items.size < 1) continue

                val episodes = items.mapNotNull { el ->
                    val link = el.selectFirst("a") ?: (if (el.tagName() == "a") el else null)
                        ?: el.selectFirst("option")?.let { opt ->
                            // <select> approach — value is the URL or episode number
                            val val_ = opt.attr("value")
                            if (val_.isBlank()) return@mapNotNull null
                            val epNum = val_.toIntOrNull()
                                ?: Regex("""\d+$""").find(val_)?.value?.toIntOrNull()
                                ?: return@mapNotNull null
                            val slug = WATCH_SLUG_RE.find(video.url)?.groupValues?.get(1) ?: return@mapNotNull null
                            return@mapNotNull Episode(
                                id = "${slug}_ep$epNum",
                                videoId = video.id,
                                sourceId = id,
                                url = "$baseUrl/watch/$slug/$epNum",
                                number = epNum,
                                title = "الحلقة $epNum"
                            )
                        } ?: return@mapNotNull null

                    val epUrl = link.attr("href").let {
                        if (it.startsWith("http")) it else "$baseUrl$it"
                    }.ifBlank { return@mapNotNull null }

                    val epNum = Regex("""/(\d+)/?$""").find(epUrl)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""\d+""").find(link.text())?.value?.toIntOrNull()
                        ?: return@mapNotNull null

                    val slug = WATCH_SLUG_RE.find(epUrl)?.groupValues?.get(1) ?: ""

                    Episode(
                        id = if (slug.isNotBlank()) "${slug}_ep$epNum" else epNum.toString(),
                        videoId = video.id,
                        sourceId = id,
                        url = epUrl,
                        number = epNum,
                        title = "الحلقة $epNum"
                    )
                }

                if (episodes.isNotEmpty()) return episodes.sortedBy { it.number }
            }

            // Fallback: blkom uses /watch/{slug}/{number} — generate from episode count
            // Derived from confirmed URL pattern in omni-recon report
            val slug = WATCH_SLUG_RE.find(video.url)?.groupValues?.get(1)
                ?: video.url.trimEnd('/').substringAfterLast("/")

            val totalEps = video.episodeCount ?: run {
                // Try to find max episode number from any /watch/ links on the page
                doc.select("a[href*='/watch/$slug/']")
                    .mapNotNull { Regex("""/(\d+)/?$""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
                    .maxOrNull()
            } ?: 0

            if (totalEps > 0 && slug.isNotBlank()) {
                android.util.Log.d("BlkomSource", "Generating $totalEps episode URLs for slug=$slug")
                (1..totalEps).map { epNum ->
                    Episode(
                        id = "${slug}_ep$epNum",
                        videoId = video.id,
                        sourceId = id,
                        url = "$baseUrl/watch/$slug/$epNum",
                        number = epNum,
                        title = "الحلقة $epNum"
                    )
                }
            } else {
                android.util.Log.w("BlkomSource", "Could not determine episode list for ${video.url}")
                emptyList()
            }

        } catch (e: Exception) {
            android.util.Log.e("BlkomSource", "getEpisodes failed: ${e.message}")
            emptyList()
        }
    }

    override suspend fun loadLinks(
        episode: Episode,
        subtitleCallback: (com.omnistream.pluginapi.model.Subtitle) -> Unit,
        callback: (VideoLink) -> Unit
    ): Boolean {
        val links = mutableListOf<VideoLink>()
        android.util.Log.e("BlkomSource", "loadLinks: episode.url=${episode.url}")

        try {
            val doc = httpClient.getDocument(episode.url)

            // Dump page structure for diagnosis
            android.util.Log.e("BlkomSource", "HTML snippet: ${doc.body()?.html()?.take(800)}")
            android.util.Log.e("BlkomSource", "All iframes: ${doc.select("iframe").map { it.attributes() }}")
            android.util.Log.e("BlkomSource", "Player divs: ${doc.select("div[id*=player], div[class*=player], div[class*=embed]").map { "${it.tagName()}#${it.id()}.${it.className()} data-video=${it.attr("data-video")} data-src=${it.attr("data-src")}" }}")
            android.util.Log.e("BlkomSource", "bkvideo scripts: ${doc.select("script[src*=bkvideo], script[src*=embed]").map { it.attr("src") }}")
            android.util.Log.e("BlkomSource", "Inline scripts with bkvideo: ${doc.select("script:not([src])").filter { it.html().contains("bkvideo", ignoreCase = true) }.map { it.html().take(200) }}")

            // Confirmed by omni-recon: iframe[src] on episode page
            // Iframe src: bkvideo.online/embedvideo/{id}?s={token}
            val iframe = doc.selectFirst("iframe[src]")
                ?: doc.selectFirst("div.player-embed iframe, div#player iframe")

            val src = iframe?.attr("src")?.ifBlank { iframe.attr("data-src") } ?: ""
            if (src.isBlank()) {
                android.util.Log.w("BlkomSource", "No iframe found on ${episode.url}")
                return false
            }

            val embedUrl = when {
                src.startsWith("//")   -> "https:$src"
                src.startsWith("http") -> src
                else                   -> "$baseUrl$src"
            }

            android.util.Log.d("BlkomSource", "Fetching embed: $embedUrl")

            // GET the embed page — bkvideo.online checks Referer
            val embedHtml = httpClient.get(
                embedUrl,
                headers = mapOf(
                    "Referer" to episode.url,
                    "Origin"  to baseUrl
                )
            )

            // Try to find signed CDN URL (mp4 with token param)
            // Pattern: cdn.bkvideo.online/videos/.../480p.mp4?expires=...&token=...
            val mp4Regex = Regex("""["'](https?://[^"']*\.mp4[^"']*)["']""")
            val m3u8Regex = Regex("""["'](https?://[^"']*\.m3u8[^"']*)["']""")

            val m3u8 = m3u8Regex.findAll(embedHtml).map { it.groupValues[1] }.firstOrNull()
            if (m3u8 != null) {
                links.add(VideoLink(
                    url = m3u8,
                    quality = "Auto",
                    extractorName = "Blkom",
                    isM3u8 = true,
                    referer = embedUrl
                ))
            }

            val mp4 = mp4Regex.findAll(embedHtml).map { it.groupValues[1] }.firstOrNull()
            if (mp4 != null) {
                // Extract quality from URL (e.g. /480p.mp4)
                val quality = Regex("""/(1080|720|480|360)p\.mp4""").find(mp4)
                    ?.groupValues?.get(1)?.let { "${it}p" } ?: "480p"
                links.add(VideoLink(
                    url = mp4,
                    quality = quality,
                    extractorName = "Blkom",
                    isM3u8 = false,
                    referer = embedUrl
                ))
            }

            if (links.isEmpty()) {
                // bkvideo signs URLs dynamically — plain HTTP may miss them.
                // WebView extraction needed if this path produces no links.
                android.util.Log.w(
                    "BlkomSource",
                    "No stream found in embed HTML — bkvideo may require JS execution. " +
                    "Consider WebView extraction for this source."
                )
            }

        } catch (e: Exception) {
            android.util.Log.e("BlkomSource", "getLinks failed: ${e.message}", e)
        }

        val unique = links.distinctBy { it.url }
        unique.forEach { callback(it) }
        android.util.Log.d("BlkomSource", "Found ${unique.size} links for ${episode.url}")
        return unique.isNotEmpty()
    }

    /**
     * Parse an anime card element.
     *
     * Homepage (.recent-episodes) cards link to episode pages:
     *   href = /watch/{slug}/{episode-number}
     * We derive the series URL as /anime/{slug} so getDetails/getEpisodes work correctly.
     */
    private fun parseAnimeCard(element: Element): Video? {
        val link = element.selectFirst("a") ?: return null
        val href = link.attr("href").ifBlank { return null }

        // Derive series URL from episode watch URL
        val watchMatch = WATCH_SLUG_RE.find(href)
        val (seriesUrl, slug) = if (watchMatch != null) {
            val s = watchMatch.groupValues[1]
            "$baseUrl/anime/$s" to s
        } else {
            val full = if (href.startsWith("http")) href else "$baseUrl$href"
            full to href.trimEnd('/').substringAfterLast("/")
        }

        if (slug.isBlank()) return null

        // Title — strip the Arabic episode suffix (الحلقة : N)
        val rawTitle = element.selectFirst(
            "div.data h3, div.data h2, h2.entry-title, span.tt, .dynamic-name, h3, h2"
        )?.text()?.trim()
            ?: link.attr("title").ifBlank { null }
            ?: element.text().lines().firstOrNull { it.isNotBlank() }
            ?: return null

        val title = rawTitle
            .replace(Regex("""[\n\r].*""", RegexOption.DOT_MATCHES_ALL), "")  // first line only
            .replace(Regex("""\s*الحلقة\s*:?\s*\d+.*"""), "")                 // strip ep suffix
            .trim()
            .ifBlank { return null }

        val poster = element.selectFirst("img")?.let { img ->
            // blkom uses class="lazy" with data-original="/uploads/..." (relative path)
            val src = img.attr("data-original")
                .ifBlank { img.attr("data-src") }
                .ifBlank { img.attr("src") }
                .let { if (it.startsWith("data:") || it.isBlank()) "" else it }
            when {
                src.startsWith("http") -> src
                src.startsWith("//")   -> "https:$src"
                src.startsWith("/")    -> "$baseUrl$src"
                else                   -> null
            }
        }

        val typeText = element.selectFirst("span.type, .btl, span.label")?.text()?.uppercase() ?: ""
        val type = if (typeText.contains("MOVIE") || typeText.contains("فيلم")) VideoType.MOVIE
                   else VideoType.ANIME

        val img = element.selectFirst("img")
        android.util.Log.e("BlkomSource", "parseAnimeCard: slug=$slug title=$title poster=$poster imgAttrs=${img?.attributes()?.joinToString { "${it.key}=${it.value.take(60)}" }}")
        return Video(
            id = slug,
            sourceId = this.id,
            title = title,
            url = seriesUrl,
            type = type,
            posterUrl = poster
        )
    }

    override suspend fun ping(): Boolean = try {
        httpClient.ping(baseUrl) > 0
    } catch (e: Exception) {
        false
    }
}
