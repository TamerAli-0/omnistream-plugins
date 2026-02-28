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
 * ManhuaPlus source for manhua/manhwa reading.
 * Site: https://manhuaplus.com
 */
class ManhuaPlusSource(
    private val httpClient: SourceHttpClient
) : MangaSource {

    override val id = "manhuaplus"
    override val name = "ManhuaPlus"
    override val baseUrl = "https://manhuaplus.com"
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
            "popular" -> PagedResult(getPopularInternal(page), hasMore = true)
            "latest" -> PagedResult(getLatestInternal(page), hasMore = true)
            else -> PagedResult(emptyList())
        }
    }

    private suspend fun getPopularInternal(page: Int): List<Manga> {
        return try {
            val url = "$baseUrl/manga/?m_orderby=trending&page=$page"
            val doc = httpClient.getDocument(url)
            android.util.Log.d("ManhuaPlus", "Loading popular page $page")

            parseMangaList(doc)
        } catch (e: Exception) {
            android.util.Log.e("ManhuaPlus", "Failed to get popular", e)
            emptyList()
        }
    }

    private suspend fun getLatestInternal(page: Int): List<Manga> {
        return try {
            val url = "$baseUrl/manga/?m_orderby=latest&page=$page"
            val doc = httpClient.getDocument(url)
            android.util.Log.d("ManhuaPlus", "Loading latest page $page")

            parseMangaList(doc)
        } catch (e: Exception) {
            android.util.Log.e("ManhuaPlus", "Failed to get latest", e)
            emptyList()
        }
    }

    override suspend fun search(query: String, page: Int): PagedResult<Manga> {
        return try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/?s=$encodedQuery&post_type=wp-manga&paged=$page"
            val doc = httpClient.getDocument(url)
            android.util.Log.d("ManhuaPlus", "Searching: $query")
            val items = parseSearchResults(doc)
            PagedResult(items, hasMore = items.size >= 20)
        } catch (e: Exception) {
            android.util.Log.e("ManhuaPlus", "Search failed", e)
            PagedResult(emptyList())
        }
    }

    private fun parseMangaList(doc: org.jsoup.nodes.Document): List<Manga> {
        val mangaList = mutableListOf<Manga>()

        // Madara theme uses .page-item-detail for each manga card
        doc.select(".page-item-detail").forEach { el ->
            try {
                val titleEl = el.selectFirst(".post-title a, h3 a, h5 a") ?: return@forEach
                val href = titleEl.attr("href").trim()
                if (href.isBlank()) return@forEach

                val title = titleEl.text().trim()
                if (title.isBlank()) return@forEach

                val img = el.selectFirst("img")
                val cover = img?.attr("data-src")?.trim()?.ifBlank { img.attr("src").trim() }
                    ?: img?.attr("src")?.trim()

                val mangaId = Regex("""manga/([^/]+)""").find(href)?.groupValues?.get(1)
                    ?: return@forEach

                mangaList.add(Manga(
                    id = mangaId,
                    sourceId = id,
                    title = title,
                    url = href,
                    coverUrl = cover,
                    status = MangaStatus.ONGOING
                ))
            } catch (e: Exception) {
                // Skip invalid entries
            }
        }

        android.util.Log.d("ManhuaPlus", "Parsed ${mangaList.size} manga")
        return mangaList
    }

    private fun parseSearchResults(doc: org.jsoup.nodes.Document): List<Manga> {
        val mangaList = mutableListOf<Manga>()

        doc.select(".c-tabs-item__content, .row.c-tabs-item__content").forEach { el ->
            try {
                val link = el.selectFirst(".post-title a, h3 a") ?: return@forEach
                val href = link.attr("href")
                if (href.isBlank()) return@forEach

                val title = link.text().trim()
                if (title.isBlank()) return@forEach

                val img = el.selectFirst("img")
                val cover = img?.attr("data-src")?.ifBlank { img.attr("src") }
                    ?: img?.attr("src")

                val mangaId = Regex("""manga/([^/]+)""").find(href)?.groupValues?.get(1)
                    ?: return@forEach

                mangaList.add(Manga(
                    id = mangaId,
                    sourceId = id,
                    title = title,
                    url = href,
                    coverUrl = cover,
                    status = MangaStatus.UNKNOWN
                ))
            } catch (e: Exception) {
                // Skip invalid entries
            }
        }

        android.util.Log.d("ManhuaPlus", "Search found ${mangaList.size} manga")
        return mangaList
    }

    override suspend fun getDetails(manga: Manga): Manga {
        return try {
            val url = if (manga.url.startsWith("http")) manga.url else "$baseUrl/manga/${manga.id}/"
            val doc = httpClient.getDocument(url)
            android.util.Log.d("ManhuaPlus", "Getting details for: ${manga.title}")

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

            val statusText = doc.selectFirst(".post-status .summary-content, .post-content_item:contains(Status) .summary-content")
                ?.text()?.lowercase() ?: ""
            val status = when {
                statusText.contains("ongoing") -> MangaStatus.ONGOING
                statusText.contains("completed") -> MangaStatus.COMPLETED
                statusText.contains("hiatus") -> MangaStatus.HIATUS
                else -> manga.status
            }

            val author = doc.selectFirst(".author-content a, .post-content_item:contains(Author) .summary-content")
                ?.text()?.trim()

            manga.copy(
                title = title,
                coverUrl = cover,
                description = description,
                genres = genres,
                status = status,
                author = author
            )
        } catch (e: Exception) {
            android.util.Log.e("ManhuaPlus", "Failed to get details", e)
            manga
        }
    }

    override suspend fun getChapters(manga: Manga): List<Chapter> {
        return try {
            val url = if (manga.url.startsWith("http")) manga.url else "$baseUrl/manga/${manga.id}/"
            val doc = httpClient.getDocument(url, referer = baseUrl)
            android.util.Log.d("ManhuaPlus", "Getting chapters for: ${manga.title}")

            // Madara theme: chapters are loaded via AJAX, not in static HTML
            // Get the manga post ID from the hidden holder element
            val postId = doc.selectFirst("#manga-chapters-holder")?.attr("data-id")

            val chaptersDoc = if (postId != null) {
                android.util.Log.d("ManhuaPlus", "Fetching chapters via AJAX for postId=$postId")
                val ajaxHtml = httpClient.post(
                    url = "$baseUrl/wp-admin/admin-ajax.php",
                    data = mapOf("action" to "manga_get_chapters", "manga" to postId),
                    headers = SourceHttpClient.AJAX_HEADERS,
                    referer = url
                )
                Jsoup.parse(ajaxHtml, baseUrl)
            } else {
                android.util.Log.d("ManhuaPlus", "No AJAX holder found, parsing static HTML")
                doc
            }

            val chapters = mutableListOf<Chapter>()

            chaptersDoc.select("li.wp-manga-chapter a, .wp-manga-chapter a").forEach { el ->
                val href = el.attr("href").trim()
                val title = el.text().trim()

                if (href.isNotBlank() && title.isNotBlank()) {
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
                android.util.Log.d("ManhuaPlus", "Found ${it.size} chapters")
            }
        } catch (e: Exception) {
            android.util.Log.e("ManhuaPlus", "Failed to get chapters", e)
            emptyList()
        }
    }

    override suspend fun getPages(chapter: Chapter): List<Page> {
        return try {
            val url = if (chapter.url.startsWith("http")) chapter.url else "$baseUrl/${chapter.id}/"
            // Pass chapter URL as referer — CDN (cdn.manhuaplus.com) requires it
            val doc = httpClient.getDocument(url, referer = baseUrl)
            android.util.Log.d("ManhuaPlus", "Getting pages for: ${chapter.title}")

            val pages = mutableListOf<Page>()

            // Primary: #readerarea (confirmed by network analysis)
            // Fallback: other madara selectors
            doc.select("#readerarea img, .reading-content img, .page-break img, .wp-manga-chapter-img")
                .forEach { img ->
                    val src = (img.attr("data-src").trim().ifBlank { img.attr("src").trim() })

                    if (src.isNotBlank() &&
                        !src.contains("logo", ignoreCase = true) &&
                        !src.contains("icon", ignoreCase = true) &&
                        !src.contains("loading", ignoreCase = true) &&
                        pages.none { it.imageUrl == src }) {

                        pages.add(Page(
                            index = pages.size,
                            imageUrl = src,
                            // Referer must be the chapter URL — cdn.manhuaplus.com validates it
                            referer = url
                        ))
                    }
                }

            pages.also {
                android.util.Log.d("ManhuaPlus", "Found ${it.size} pages")
            }
        } catch (e: Exception) {
            android.util.Log.e("ManhuaPlus", "Failed to get pages", e)
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
