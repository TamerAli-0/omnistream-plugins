package com.omnistream.source.manga

import com.omnistream.pluginapi.net.SourceHttpClient
import com.omnistream.pluginapi.model.Chapter
import com.omnistream.pluginapi.model.Manga
import com.omnistream.pluginapi.model.MangaStatus
import com.omnistream.pluginapi.model.Page
import com.omnistream.pluginapi.source.MangaSource
import com.omnistream.pluginapi.model.MainPageSection
import com.omnistream.pluginapi.model.PagedResult

/**
 * MangaKakalot/MangaNato source for manga reading.
 * Very stable HTML structure, rarely changes.
 */
class MangaKakalotSource(
    private val httpClient: SourceHttpClient
) : MangaSource {

    override val id = "mangakakalot"
    override val name = "MangaKakalot"
    override val baseUrl = "https://www.mangakakalot.gg"
    override val lang = "en"
    override val isNsfw = false

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
        val url = "$baseUrl/manga-list/hot-manga?page=$page"
        return parseListPage(url)
    }

    private suspend fun getLatestInternal(page: Int): List<Manga> {
        val url = "$baseUrl/manga-list/latest-manga?page=$page"
        return parseListPage(url)
    }

    override suspend fun search(query: String, page: Int): PagedResult<Manga> {
        val searchQuery = query.replace(" ", "_").lowercase()
        val url = "$baseUrl/search/story/$searchQuery?page=$page"
        val items = parseSearchPage(url)
        return PagedResult(items, hasMore = items.size >= 20)
    }

    private suspend fun parseListPage(url: String): List<Manga> {
        return try {
            val doc = httpClient.getDocument(url)
            android.util.Log.d("MangaKakalot", "Parsing list page: $url, body length=${doc.body().text().length}")

            // Try multiple selectors for chapmanganato.to layout variations
            val itemSelectors = listOf(
                "div.content-genres-item",
                "div.truyen-list .list-truyen-item-wrap",
                "div.manga-list-4-list li",
                "div.list-story-item",
                "div.story_item"
            )

            for (itemSelector in itemSelectors) {
                val elements = doc.select(itemSelector)
                if (elements.isEmpty()) continue

                val results = elements.mapNotNull { element ->
                    try {
                        val linkEl = element.selectFirst("a.genres-item-img, a.item-img, a.manga-name-chapter, a") ?: return@mapNotNull null
                        val href = linkEl.attr("href").ifBlank { return@mapNotNull null }
                        val mangaId = href.substringAfterLast("/").ifBlank { return@mapNotNull null }

                        val title = (linkEl.attr("title").ifBlank { null }
                            ?: element.selectFirst("h3 a, h2 a, .list-story-item-wrap-title, a.item-title")?.text()
                            ?: linkEl.text()).trim().ifBlank { return@mapNotNull null }

                        val coverUrl = element.selectFirst("img")?.let { img ->
                            img.attr("src").ifBlank { img.attr("data-src") }
                        }

                        Manga(
                            id = mangaId,
                            sourceId = id,
                            title = title,
                            url = href,
                            coverUrl = coverUrl
                        )
                    } catch (e: Exception) {
                        null
                    }
                }

                if (results.isNotEmpty()) {
                    android.util.Log.d("MangaKakalot", "Found ${results.size} manga with selector '$itemSelector'")
                    return results
                }
            }

            android.util.Log.w("MangaKakalot", "No manga found with any selector for $url")
            emptyList()
        } catch (e: Exception) {
            android.util.Log.e("MangaKakalot", "Failed to parse list", e)
            emptyList()
        }
    }

    private suspend fun parseSearchPage(url: String): List<Manga> {
        return try {
            val doc = httpClient.getDocument(url)

            doc.select("div.search-story-item, div.story_item").mapNotNull { element ->
                try {
                    val linkEl = element.selectFirst("a.item-img, a.story_item_img") ?: return@mapNotNull null
                    val href = linkEl.attr("href")
                    val mangaId = href.substringAfterLast("/")

                    val title = linkEl.attr("title").ifBlank {
                        element.selectFirst("h3 a")?.text()
                    } ?: return@mapNotNull null

                    val coverUrl = element.selectFirst("img")?.attr("src")

                    Manga(
                        id = mangaId,
                        sourceId = id,
                        title = title.trim(),
                        url = href,
                        coverUrl = coverUrl
                    )
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MangaKakalot", "Search failed", e)
            emptyList()
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        return try {
            val url = manga.url.ifBlank { "$baseUrl/manga-${manga.id}" }
            val doc = httpClient.getDocument(url)
            android.util.Log.d("MangaKakalot", "Getting details for: $url")

            val infoPanel = doc.selectFirst("div.panel-story-info, div.manga-info-top")

            val title = doc.selectFirst("h1, div.story-info-right h1")?.text()?.trim() ?: manga.title

            val coverUrl = doc.selectFirst("span.info-image img, div.manga-info-pic img")?.attr("src")
                ?: manga.coverUrl

            val description = doc.selectFirst("div.panel-story-info-description, div#noidungm")
                ?.text()?.replace("Description :", "")?.trim()

            // Parse info table
            val infoRows = doc.select("table.variations-tableInfo tr, li.story-info-right-extent")
            var author: String? = null
            var status: MangaStatus = MangaStatus.UNKNOWN
            val genres = mutableListOf<String>()

            infoRows.forEach { row ->
                val label = row.selectFirst("td.table-label, .stre-label")?.text()?.lowercase() ?: ""
                val value = row.selectFirst("td.table-value, .stre-value")

                when {
                    label.contains("author") -> author = value?.text()?.trim()
                    label.contains("status") -> {
                        val statusText = value?.text()?.lowercase() ?: ""
                        status = when {
                            statusText.contains("ongoing") -> MangaStatus.ONGOING
                            statusText.contains("completed") -> MangaStatus.COMPLETED
                            else -> MangaStatus.UNKNOWN
                        }
                    }
                    label.contains("genre") -> {
                        value?.select("a")?.forEach { genres.add(it.text().trim()) }
                    }
                }
            }

            manga.copy(
                title = title,
                coverUrl = coverUrl,
                description = description,
                author = author,
                status = status,
                genres = genres
            )
        } catch (e: Exception) {
            android.util.Log.e("MangaKakalot", "Failed to get details", e)
            manga
        }
    }

    override suspend fun getChapters(manga: Manga): List<Chapter> {
        return try {
            val url = manga.url.ifBlank { "$baseUrl/manga-${manga.id}" }
            val doc = httpClient.getDocument(url)
            android.util.Log.d("MangaKakalot", "Getting chapters for: $url")

            doc.select("ul.row-content-chapter li, div.chapter-list div.row").mapNotNull { element ->
                try {
                    val linkEl = element.selectFirst("a") ?: return@mapNotNull null
                    val href = linkEl.attr("href")
                    val chapterId = href.substringAfterLast("/")

                    val chapterText = linkEl.text().trim()
                    val chapterNumber = extractChapterNumber(chapterText) ?: return@mapNotNull null

                    val title = if (chapterText.contains(":")) {
                        chapterText.substringAfter(":").trim()
                    } else null

                    // Parse date
                    val dateText = element.selectFirst("span.chapter-time, span.chapter_time")?.text()
                    val uploadDate = parseDateText(dateText)

                    Chapter(
                        id = chapterId,
                        mangaId = manga.id,
                        sourceId = id,
                        url = href,
                        title = title,
                        number = chapterNumber,
                        uploadDate = uploadDate
                    )
                } catch (e: Exception) {
                    null
                }
            }.sortedByDescending { it.number }.also {
                android.util.Log.d("MangaKakalot", "Found ${it.size} chapters")
            }
        } catch (e: Exception) {
            android.util.Log.e("MangaKakalot", "Failed to get chapters", e)
            emptyList()
        }
    }

    override suspend fun getPages(chapter: Chapter): List<Page> {
        return try {
            val doc = httpClient.getDocument(chapter.url)
            android.util.Log.d("MangaKakalot", "Getting pages for: ${chapter.url}")

            doc.select("div.container-chapter-reader img, div.vung-doc img").mapIndexedNotNull { index, img ->
                val src = img.attr("src").ifBlank { img.attr("data-src") }
                if (src.isNotBlank() && (src.contains(".jpg") || src.contains(".png") || src.contains(".webp"))) {
                    Page(
                        index = index,
                        imageUrl = src,
                        referer = baseUrl
                    )
                } else null
            }.also {
                android.util.Log.d("MangaKakalot", "Found ${it.size} pages")
            }
        } catch (e: Exception) {
            android.util.Log.e("MangaKakalot", "Failed to get pages", e)
            emptyList()
        }
    }

    private fun extractChapterNumber(text: String): Float? {
        val patterns = listOf(
            Regex("""[Cc]hapter\s*(\d+(?:\.\d+)?)"""),
            Regex("""[Cc]h\.?\s*(\d+(?:\.\d+)?)"""),
            Regex("""(\d+(?:\.\d+)?)""")
        )
        for (pattern in patterns) {
            pattern.find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.let { return it }
        }
        return null
    }

    private fun parseDateText(dateText: String?): Long? {
        if (dateText.isNullOrBlank()) return null
        val now = System.currentTimeMillis()
        val text = dateText.lowercase()

        return try {
            when {
                text.contains("ago") -> {
                    val num = Regex("""(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 1
                    when {
                        text.contains("min") -> now - num * 60 * 1000
                        text.contains("hour") -> now - num * 60 * 60 * 1000
                        text.contains("day") -> now - num * 24 * 60 * 60 * 1000
                        else -> now
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            null
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
