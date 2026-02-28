package com.omnistream.source.manga

import com.omnistream.pluginapi.net.SourceHttpClient
import com.omnistream.pluginapi.model.Chapter
import com.omnistream.pluginapi.model.Manga
import com.omnistream.pluginapi.model.MangaStatus
import com.omnistream.pluginapi.model.Page
import com.omnistream.pluginapi.source.MangaSource
import com.omnistream.pluginapi.model.MainPageSection
import com.omnistream.pluginapi.model.PagedResult
import org.json.JSONObject
import org.jsoup.Jsoup

/**
 * MangaGeko source (mgeko.cc)
 *
 * Recon notes (omni-recon confirmed):
 *  - Listing API: GET /browse-comics/data/?page={n}&ordering=-views → JSON {"results_html":"<article...>"}
 *  - Latest: same endpoint with ordering=-last_upload
 *  - Search: JS overlay (myFunction) — endpoint unknown; falls back to client-side title filter
 *  - Detail: GET /manga/{slug}/ → h1.novel-title, figure.cover img.lazy[data-src], p.description, a.property-item
 *  - Chapters: GET /manga/{slug}/all-chapters/ → ul.chapter-list li a[href]
 *    href format: /reader/en/{slug}-chapter-{n}-eng-li/  (newest-first in DOM)
 *  - Reader: GET /reader/en/{chapter-slug}/ → #chapter-reader img[src] (plain src, no lazy loading)
 *  - CDN: imgsrv4.com/mg1/fastcdn/ — HTTP 200 direct, no Referer required (curl-confirmed)
 *  - No Cloudflare protection detected on any endpoint
 */
class MgekoSource(
    private val httpClient: SourceHttpClient
) : MangaSource {

    override val id = "mgeko"
    override val name = "MangaGeko"
    override val baseUrl = "https://www.mgeko.cc"
    override val lang = "en"

    override val hasSearch: Boolean = true
    override val hasLatest: Boolean = true

    override val mainPageSections: List<MainPageSection> = listOf(
        MainPageSection("Popular", "popular"),
        MainPageSection("Latest", "latest")
    )

    override suspend fun getSection(section: MainPageSection, page: Int): PagedResult<Manga> {
        return when (section.data) {
            "popular" -> PagedResult(getPopularInternal(page), hasMore = true)
            "latest" -> PagedResult(getLatestInternal(page), hasMore = true)
            else -> PagedResult(emptyList())
        }
    }

    private suspend fun getPopularInternal(page: Int): List<Manga> {
        return try {
            val json = httpClient.get(
                "$baseUrl/browse-comics/data/?page=$page&ordering=-views",
                headers = SourceHttpClient.AJAX_HEADERS
            )
            android.util.Log.d("Mgeko", "Loading popular page $page")
            parseBrowseJson(json)
        } catch (e: Exception) {
            android.util.Log.e("Mgeko", "Failed to get popular", e)
            emptyList()
        }
    }

    private suspend fun getLatestInternal(page: Int): List<Manga> {
        return try {
            val json = httpClient.get(
                "$baseUrl/browse-comics/data/?page=$page&ordering=-last_upload",
                headers = SourceHttpClient.AJAX_HEADERS
            )
            android.util.Log.d("Mgeko", "Loading latest page $page")
            parseBrowseJson(json)
        } catch (e: Exception) {
            android.util.Log.e("Mgeko", "Failed to get latest", e)
            emptyList()
        }
    }

    override suspend fun search(query: String, page: Int): PagedResult<Manga> {
        // TODO: mgeko uses a JS overlay (myFunction()) for search — real API endpoint not yet confirmed.
        // For now, fetch the popular listing and filter client-side by title.
        return try {
            val json = httpClient.get(
                "$baseUrl/browse-comics/data/?page=$page&ordering=-views",
                headers = SourceHttpClient.AJAX_HEADERS
            )
            val items = parseBrowseJson(json).filter { it.title.contains(query, ignoreCase = true) }
            PagedResult(items, hasMore = items.size >= 20)
        } catch (e: Exception) {
            android.util.Log.e("Mgeko", "Search failed", e)
            PagedResult(emptyList())
        }
    }

    /**
     * Parses the JSON listing response: {"results_html": "<article class=\"comic-card\">..."}
     */
    private fun parseBrowseJson(json: String): List<Manga> {
        val html = try {
            JSONObject(json).getString("results_html")
        } catch (e: Exception) {
            android.util.Log.e("Mgeko", "Failed to parse listing JSON", e)
            return emptyList()
        }

        val doc = Jsoup.parse(html, baseUrl)
        val mangaList = mutableListOf<Manga>()

        doc.select("article.comic-card").forEach { el ->
            try {
                val titleEl = el.selectFirst(".comic-card__title a") ?: return@forEach
                val href = titleEl.attr("href").trim()
                if (href.isBlank()) return@forEach

                val title = titleEl.text().trim()
                if (title.isBlank()) return@forEach

                val slug = Regex("""/manga/([^/]+)/""").find(href)?.groupValues?.get(1)
                    ?: return@forEach

                val cover = el.selectFirst(".comic-card__cover img")?.attr("src")

                mangaList.add(Manga(
                    id = slug,
                    sourceId = id,
                    title = title,
                    url = "$baseUrl/manga/$slug/",
                    coverUrl = cover,
                    status = MangaStatus.UNKNOWN
                ))
            } catch (e: Exception) {
                // Skip invalid entries
            }
        }

        android.util.Log.d("Mgeko", "Parsed ${mangaList.size} manga")
        return mangaList
    }

    override suspend fun getDetails(manga: Manga): Manga {
        return try {
            val url = if (manga.url.startsWith("http")) manga.url else "$baseUrl/manga/${manga.id}/"
            val doc = httpClient.getDocument(url)
            android.util.Log.d("Mgeko", "Getting details for: ${manga.title}")

            val title = doc.selectFirst("h1.novel-title")?.text()?.trim() ?: manga.title

            val cover = doc.selectFirst("figure.cover img.lazy")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            } ?: manga.coverUrl

            val description = doc.selectFirst("p.description")?.text()?.trim()

            val genres = doc.select("a.property-item")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }

            // Status row: look for a detail item containing "Status" label
            val statusText = doc.select(".novel-detail-item").firstOrNull { item ->
                item.selectFirst(".novel-detail-name")?.text()?.contains("Status", ignoreCase = true) == true
            }?.selectFirst(".novel-detail-body")?.text()?.lowercase() ?: ""

            val status = when {
                statusText.contains("ongoing") -> MangaStatus.ONGOING
                statusText.contains("completed") -> MangaStatus.COMPLETED
                statusText.contains("hiatus") -> MangaStatus.HIATUS
                else -> manga.status
            }

            val author = doc.select(".novel-detail-item").firstOrNull { item ->
                item.selectFirst(".novel-detail-name")?.text()?.contains("Author", ignoreCase = true) == true
            }?.selectFirst(".novel-detail-body a")?.text()?.trim()

            manga.copy(
                title = title,
                coverUrl = cover,
                description = description,
                genres = genres,
                status = status,
                author = author
            )
        } catch (e: Exception) {
            android.util.Log.e("Mgeko", "Failed to get details", e)
            manga
        }
    }

    override suspend fun getChapters(manga: Manga): List<Chapter> {
        return try {
            val chaptersUrl = "$baseUrl/manga/${manga.id}/all-chapters/"
            val doc = httpClient.getDocument(chaptersUrl)
            android.util.Log.d("Mgeko", "Getting chapters for: ${manga.title}")

            val chapters = mutableListOf<Chapter>()

            // href format: /reader/en/{slug}-chapter-{n}-eng-li/
            doc.select("ul.chapter-list li a").forEach { el ->
                val href = el.attr("href").trim()
                if (href.isBlank()) return@forEach

                val numMatch = Regex("""chapter[- ](\d+(?:\.\d+)?)[- ]""", RegexOption.IGNORE_CASE)
                    .find(href)
                val chapterNum = numMatch?.groupValues?.get(1)?.toFloatOrNull()
                    ?: (chapters.size + 1).toFloat()

                val title = el.text().trim().ifBlank { "Chapter $chapterNum" }
                val chapterId = href.trim('/')

                if (chapters.none { it.number == chapterNum }) {
                    chapters.add(Chapter(
                        id = chapterId,
                        mangaId = manga.id,
                        sourceId = id,
                        title = title,
                        number = chapterNum,
                        url = if (href.startsWith("http")) href else "$baseUrl$href"
                    ))
                }
            }

            // DOM order is newest-first; sort ascending for sequential reading
            chapters.sortedBy { it.number }.also {
                android.util.Log.d("Mgeko", "Found ${it.size} chapters")
            }
        } catch (e: Exception) {
            android.util.Log.e("Mgeko", "Failed to get chapters", e)
            emptyList()
        }
    }

    override suspend fun getPages(chapter: Chapter): List<Page> {
        return try {
            val url = if (chapter.url.startsWith("http")) chapter.url else "$baseUrl/${chapter.id}/"
            android.util.Log.e("Mgeko", "Fetching pages URL: $url (id=${chapter.id})")
            val doc = httpClient.getDocument(url)
            android.util.Log.d("Mgeko", "Getting pages for: ${chapter.title}")

            val pages = mutableListOf<Page>()

            // #chapter-reader img uses plain src (no lazy loading confirmed by recon)
            // Only accept imgsrv4.com CDN images — filters out banners and ads
            doc.select("#chapter-reader img").forEach { img ->
                val src = img.attr("src").trim()
                if (src.isNotBlank() &&
                    src.contains("imgsrv4.com/mg1/fastcdn/") &&
                    pages.none { it.imageUrl == src }
                ) {
                    pages.add(Page(
                        index = pages.size,
                        imageUrl = src
                        // No Referer needed — imgsrv4.com CDN confirmed open via curl
                    ))
                }
            }

            pages.also {
                android.util.Log.d("Mgeko", "Found ${it.size} pages")
            }
        } catch (e: Exception) {
            android.util.Log.e("Mgeko", "Failed to get pages", e)
            emptyList()
        }
    }

    override suspend fun ping(): Boolean {
        return try {
            httpClient.ping(baseUrl) > 0
        } catch (e: Exception) {
            false
        }
    }
}
