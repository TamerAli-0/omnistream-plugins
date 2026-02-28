package com.omnistream.source.manga

import com.omnistream.pluginapi.net.SourceHttpClient
import org.jsoup.Jsoup
import com.omnistream.pluginapi.model.Chapter
import com.omnistream.pluginapi.model.Manga
import com.omnistream.pluginapi.model.MangaStatus
import com.omnistream.pluginapi.model.Page
import com.omnistream.pluginapi.model.MangaContentType
import com.omnistream.pluginapi.source.MangaSource
import com.omnistream.pluginapi.model.MainPageSection
import com.omnistream.pluginapi.model.PagedResult

/**
 * ManhuaFast source — Madara WordPress theme.
 * Site: https://manhuafast.com
 * CF-protected: YES — cf_clearance handled by OmniHttpClient interceptor.
 * Chapters: AJAX via admin-ajax.php — NO nonce required, just action + manga (postId).
 * Chapter format: <select><option data-redirect="url"> dropdown.
 */
class ManhuaFastSource(
    private val httpClient: SourceHttpClient
) : MangaSource {

    override val id = "manhuafast"
    override val name = "ManhuaFast"
    override val baseUrl = "https://manhuafast.com"
    override val lang = "en"
    override val isNsfw = false
    override val contentType = MangaContentType.MANHUA

    override val hasSearch: Boolean = true
    override val hasLatest: Boolean = true

    override val mainPageSections: List<MainPageSection> = listOf(
        MainPageSection("Popular", "popular"),
        MainPageSection("Latest", "latest")
    )

    override suspend fun getSection(section: MainPageSection, page: Int): PagedResult<Manga> {
        return when (section.data) {
            "popular" -> PagedResult(getListInternal("trending", page), hasMore = true)
            "latest"  -> PagedResult(getListInternal("latest", page),   hasMore = true)
            else      -> PagedResult(emptyList())
        }
    }

    private suspend fun getListInternal(orderBy: String, page: Int): List<Manga> {
        return try {
            val url = "$baseUrl/manga/?m_orderby=$orderBy&page=$page"
            val doc = httpClient.getDocument(url)
            android.util.Log.e("ManhuaFast", "Loading $orderBy page $page")
            parseMangaList(doc)
        } catch (e: Exception) {
            android.util.Log.e("ManhuaFast", "Failed to load $orderBy page $page", e)
            emptyList()
        }
    }

    override suspend fun search(query: String, page: Int): PagedResult<Manga> {
        return try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/?s=$encodedQuery&post_type=wp-manga&paged=$page"
            val doc = httpClient.getDocument(url)
            android.util.Log.e("ManhuaFast", "Search: $query page $page")
            val items = parseSearchResults(doc)
            PagedResult(items, hasMore = items.size >= 20)
        } catch (e: Exception) {
            android.util.Log.e("ManhuaFast", "Search failed", e)
            PagedResult(emptyList())
        }
    }

    private fun parseMangaList(doc: org.jsoup.nodes.Document): List<Manga> {
        val list = mutableListOf<Manga>()
        doc.select(".page-item-detail").forEach { el ->
            try {
                val titleEl = el.selectFirst(".post-title a, h3 a, h5 a") ?: return@forEach
                val href = titleEl.attr("href").trim()
                if (href.isBlank()) return@forEach
                val title = titleEl.text().trim()
                if (title.isBlank()) return@forEach
                val img = el.selectFirst("img")
                val cover = img?.attr("data-src")?.ifBlank { img.attr("src") } ?: img?.attr("src")
                val mangaId = Regex("""manga/([^/]+)""").find(href)?.groupValues?.get(1) ?: return@forEach
                list.add(Manga(
                    id = mangaId,
                    sourceId = id,
                    title = title,
                    url = href,
                    coverUrl = cover,
                    status = MangaStatus.ONGOING
                ))
            } catch (_: Exception) {}
        }
        android.util.Log.e("ManhuaFast", "Parsed ${list.size} manga from listing")
        return list
    }

    private fun parseSearchResults(doc: org.jsoup.nodes.Document): List<Manga> {
        val list = mutableListOf<Manga>()
        doc.select(".c-tabs-item__content, .row.c-tabs-item__content").forEach { el ->
            try {
                val link = el.selectFirst(".post-title a, h3 a") ?: return@forEach
                val href = link.attr("href")
                if (href.isBlank()) return@forEach
                val title = link.text().trim()
                if (title.isBlank()) return@forEach
                val img = el.selectFirst("img")
                val cover = img?.attr("data-src")?.ifBlank { img.attr("src") } ?: img?.attr("src")
                val mangaId = Regex("""manga/([^/]+)""").find(href)?.groupValues?.get(1) ?: return@forEach
                list.add(Manga(
                    id = mangaId,
                    sourceId = id,
                    title = title,
                    url = href,
                    coverUrl = cover,
                    status = MangaStatus.UNKNOWN
                ))
            } catch (_: Exception) {}
        }
        android.util.Log.e("ManhuaFast", "Search found ${list.size} results")
        return list
    }

    override suspend fun getDetails(manga: Manga): Manga {
        return try {
            val url = if (manga.url.startsWith("http")) manga.url else "$baseUrl/manga/${manga.id}/"
            val doc = httpClient.getDocument(url)
            android.util.Log.e("ManhuaFast", "Getting details: ${manga.title}")

            val title = doc.selectFirst(".post-title h1, .post-title h3")?.text()?.trim()
                ?: doc.selectFirst("h1")?.text()?.trim()
                ?: manga.title

            val cover = doc.selectFirst(".summary_image img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            } ?: manga.coverUrl

            val description = doc.selectFirst(".summary__content, .description-summary")?.text()?.trim()

            val genres = doc.select(".genres-content a, .manga-tag a")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }

            val statusText = doc.selectFirst(
                ".post-status .summary-content, .post-content_item:contains(Status) .summary-content"
            )?.text()?.lowercase() ?: ""
            val status = when {
                statusText.contains("ongoing")   -> MangaStatus.ONGOING
                statusText.contains("completed") -> MangaStatus.COMPLETED
                statusText.contains("hiatus")    -> MangaStatus.HIATUS
                else                             -> manga.status
            }

            val author = doc.selectFirst(
                ".author-content a, .post-content_item:contains(Author) .summary-content"
            )?.text()?.trim()

            manga.copy(title = title, coverUrl = cover, description = description,
                genres = genres, status = status, author = author)
        } catch (e: Exception) {
            android.util.Log.e("ManhuaFast", "Failed to get details", e)
            manga
        }
    }

    override suspend fun getChapters(manga: Manga): List<Chapter> {
        return try {
            val url = if (manga.url.startsWith("http")) manga.url else "$baseUrl/manga/${manga.id}/"
            val doc = httpClient.getDocument(url, referer = baseUrl)
            android.util.Log.e("ManhuaFast", "Getting chapters: ${manga.title}")

            // Check if chapters are already in static HTML (no AJAX needed)
            val holderHtml = doc.selectFirst("#manga-chapters-holder")?.html() ?: ""
            val staticOptions = doc.select("option[data-redirect]")
            val staticLiChapters = doc.select("li.wp-manga-chapter a")
            android.util.Log.e("ManhuaFast", "Static: options=${staticOptions.size} li=${staticLiChapters.size} holderHtml=${holderHtml.take(200)}")
            if (staticOptions.isNotEmpty()) {
                val chapters = mutableListOf<Chapter>()
                staticOptions.forEach { el ->
                    val href = el.attr("data-redirect").trim()
                    val title = el.text().trim()
                    if (href.isBlank() || title.isBlank()) return@forEach
                    val match = Regex("""chapter[- ]?(\d+\.?\d*)""", RegexOption.IGNORE_CASE).find(title)
                        ?: Regex("""chapter[- ]?(\d+\.?\d*)""", RegexOption.IGNORE_CASE).find(href)
                    val chapterNum = match?.groupValues?.get(1)?.toFloatOrNull() ?: (chapters.size + 1).toFloat()
                    val chapterId = href.replace(baseUrl, "").trim('/')
                    if (chapters.none { it.number == chapterNum }) {
                        chapters.add(Chapter(id = chapterId, mangaId = manga.id, sourceId = id,
                            title = title, number = chapterNum, url = href))
                    }
                }
                android.util.Log.e("ManhuaFast", "Static chapters found: ${chapters.size}")
                if (chapters.isNotEmpty()) return chapters.sortedBy { it.number }
            }

            // Kotatsu: postReq=false → POST {mangaUrl}/ajax/chapters/ with empty body
            val ajaxUrl = "${url.trimEnd('/')}/ajax/chapters/"
            android.util.Log.e("ManhuaFast", "chapters ajax: $ajaxUrl")
            val html = httpClient.postXhr(
                url = ajaxUrl,
                data = emptyMap(),
                referer = url
            )
            android.util.Log.e("ManhuaFast", "AJAX response length=${html.length} preview=${html.take(200)}")
            val chaptersDoc = Jsoup.parse(html, baseUrl)

            val chapters = mutableListOf<Chapter>()

            // Filter to only this manga's chapters (ajax may return homepage with all manga's dropdowns)
            val mangaSlug = manga.id  // e.g. "martial-peak-3"

            chaptersDoc.select("option[data-redirect]").forEach { el ->
                val href = el.attr("data-redirect").trim()
                val title = el.text().trim()
                if (href.isBlank() || title.isBlank()) return@forEach
                // Only include chapters that belong to this manga
                if (!href.contains("/manga/$mangaSlug/")) return@forEach

                val match = Regex("""chapter[- ]?(\d+\.?\d*)""", RegexOption.IGNORE_CASE)
                    .find(title) ?: Regex("""chapter[- ]?(\d+\.?\d*)""", RegexOption.IGNORE_CASE).find(href)
                val chapterNum = match?.groupValues?.get(1)?.toFloatOrNull() ?: (chapters.size + 1).toFloat()
                val chapterId = href.replace(baseUrl, "").trim('/')

                if (chapters.none { it.number == chapterNum }) {
                    chapters.add(Chapter(
                        id = chapterId,
                        mangaId = manga.id,
                        sourceId = id,
                        title = title,
                        number = chapterNum,
                        url = href
                    ))
                }
            }

            // Fallback: standard Madara list format
            if (chapters.isEmpty()) {
                chaptersDoc.select("li.wp-manga-chapter a, .wp-manga-chapter a").forEach { el ->
                    val href = el.attr("href").trim()
                    val title = el.text().trim()
                    if (href.isBlank() || title.isBlank()) return@forEach
                    if (!href.contains("/manga/$mangaSlug/")) return@forEach

                    val match = Regex("""chapter[- ]?(\d+\.?\d*)""", RegexOption.IGNORE_CASE)
                        .find(title) ?: Regex("""chapter[- ]?(\d+\.?\d*)""", RegexOption.IGNORE_CASE).find(href)
                    val chapterNum = match?.groupValues?.get(1)?.toFloatOrNull() ?: (chapters.size + 1).toFloat()
                    val chapterId = href.replace(baseUrl, "").trim('/')

                    if (chapters.none { it.number == chapterNum }) {
                        chapters.add(Chapter(
                            id = chapterId,
                            mangaId = manga.id,
                            sourceId = id,
                            title = title,
                            number = chapterNum,
                            url = href
                        ))
                    }
                }
            }

            chapters.sortedBy { it.number }.also {
                android.util.Log.e("ManhuaFast", "Found ${it.size} chapters")
                it.take(3).forEach { ch ->
                    android.util.Log.e("ManhuaFast", "  ch#${ch.number} id=${ch.id} url=${ch.url}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ManhuaFast", "Failed to get chapters", e)
            emptyList()
        }
    }

    override suspend fun getPages(chapter: Chapter): List<Page> {
        return try {
            val url = if (chapter.url.startsWith("http")) chapter.url else "$baseUrl/${chapter.id}/"
            android.util.Log.e("ManhuaFast", "getPages: id=${chapter.id} url=${chapter.url} -> fetching=$url")
            // Referer = chapter URL — required by manhuafast's CDN
            val doc = httpClient.getDocument(url, referer = url)
            android.util.Log.e("ManhuaFast", "Getting pages: ${chapter.title} url=$url")

            val imgEls = doc.select("div.page-break img, #readerarea img, .reading-content img, .wp-manga-chapter-img")
            android.util.Log.e("ManhuaFast", "img elements found: ${imgEls.size}")
            val pages = mutableListOf<Page>()
            imgEls.forEach { img ->
                // Check all lazy-load attributes Madara sites use
                val src = listOf("data-src", "data-lazy-src", "data-original", "src")
                    .map { img.attr(it).trim() }
                    .firstOrNull { it.isNotBlank() && !it.contains("loading", ignoreCase = true) }
                    ?: return@forEach
                if (src.isBlank() ||
                    src.contains("logo", ignoreCase = true) ||
                    src.contains("icon", ignoreCase = true) ||
                    pages.any { it.imageUrl == src }
                ) return@forEach
                pages.add(Page(index = pages.size, imageUrl = src, referer = url))
            }

            android.util.Log.e("ManhuaFast", "Found ${pages.size} pages")
            pages
        } catch (e: Exception) {
            android.util.Log.e("ManhuaFast", "Failed to get pages", e)
            emptyList()
        }
    }

    private fun extractNonce(doc: org.jsoup.nodes.Document): String? {
        val mangaVarsRe = Regex(""""nonce"\s*:\s*"([^"]+)"""")
        doc.select("script").forEach { script ->
            mangaVarsRe.find(script.data())?.groupValues?.get(1)?.let { return it }
        }
        val varNonceRe = Regex("""nonce['"]\s*[:=]\s*['"]([a-zA-Z0-9_]+)['"]""", RegexOption.IGNORE_CASE)
        doc.select("script").forEach { script ->
            varNonceRe.find(script.data())?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    override suspend fun ping(): Boolean {
        return try { httpClient.ping(baseUrl) > 0 } catch (_: Exception) { false }
    }
}
