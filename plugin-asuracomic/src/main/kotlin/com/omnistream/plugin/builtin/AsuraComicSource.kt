package com.omnistream.source.manga

import com.omnistream.pluginapi.net.SourceHttpClient
import com.omnistream.pluginapi.model.Chapter
import com.omnistream.pluginapi.model.Manga
import com.omnistream.pluginapi.model.MangaStatus
import com.omnistream.pluginapi.model.Page
import com.omnistream.pluginapi.model.MangaContentType
import com.omnistream.pluginapi.source.MangaSource
import com.omnistream.pluginapi.model.MainPageSection
import com.omnistream.pluginapi.model.PagedResult
import org.jsoup.nodes.Element

/**
 * AsuraComic source for Korean manhwa.
 * Site: https://asuracomic.net
 *
 * Uses a combination of HTML scraping and internal API calls.
 */
class AsuraComicSource(
    private val httpClient: SourceHttpClient
) : MangaSource {

    override val id = "asuracomic"
    override val name = "Asura Scans"
    override val baseUrl = "https://asuracomic.net"
    override val lang = "en"
    override val isNsfw = false
    override val contentType = MangaContentType.MANHWA

    // Alternative domains in case main is down
    private val altDomains = listOf(
        "https://asuracomic.net",
        "https://asurascans.com",
        "https://asuratoon.com"
    )

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
        val url = "$baseUrl/series?page=$page&order=rating"
        return parseSeriesList(url)
    }

    private suspend fun getLatestInternal(page: Int): List<Manga> {
        val url = "$baseUrl/series?page=$page&order=update"
        return parseSeriesList(url)
    }

    override suspend fun search(query: String, page: Int): PagedResult<Manga> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/series?page=$page&name=$encodedQuery"
        val items = parseSeriesList(url)
        return PagedResult(items, hasMore = items.size >= 20)
    }

    private suspend fun parseSeriesList(url: String): List<Manga> {
        return try {
            val doc = httpClient.getDocument(url)

            // Try multiple selectors for different site versions
            val selectors = listOf(
                "div.grid a[href*='/series/']",
                "a[href*='/series/'][class]",
                "div.grid > a",
                "div.listupd a[href*='/manga/']",
                "div.bs a[href*='/manga/']",
                "article a[href*='/series/']",
                "a[href*='/series/']"
            )

            for (selector in selectors) {
                val elements = doc.select(selector)
                if (elements.isNotEmpty()) {
                    val mangas = elements.mapNotNull { element ->
                        try {
                            parseMangaCard(element)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (mangas.isNotEmpty()) return mangas
                }
            }

            android.util.Log.w("AsuraComicSource", "No manga found with any selector")
            emptyList()
        } catch (e: Exception) {
            android.util.Log.e("AsuraComicSource", "Failed to parse series list: $url", e)
            emptyList()
        }
    }

    private fun parseMangaCard(element: Element): Manga? {
        val href = element.attr("href")
        if (href.isBlank()) return null

        // hrefs can be absolute (https://...), rooted (/series/slug), or relative (series/slug)
        val normalizedHref = when {
            href.startsWith("http") -> href
            href.startsWith("/") -> href
            else -> "/$href"
        }
        val slug = normalizedHref.substringAfter("/series/").substringBefore("/").ifBlank { return null }

        val title = element.selectFirst("span.block, div.font-bold, span.title, h3, h2, span[class*='title'], div[class*='title']")?.text()?.trim()
            ?: element.attr("title").ifBlank { null }
            ?: element.selectFirst("span, div")?.text()?.trim()?.ifBlank { null }
            ?: return null

        val coverUrl = element.selectFirst("img")?.let { img ->
            img.attr("src").ifBlank { img.attr("data-src") }
        }

        val rating = element.selectFirst("span:contains(.)")?.text()
            ?.toFloatOrNull()

        // Build the full URL from the original href — don't reconstruct it
        val fullUrl = when {
            href.startsWith("http") -> href
            href.startsWith("/") -> "$baseUrl$href"
            else -> "$baseUrl/$href"
        }

        return Manga(
            id = slug,
            sourceId = id,
            title = title,
            url = fullUrl,
            coverUrl = coverUrl,
            rating = rating
        )
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val doc = httpClient.getDocument(manga.url)

        val title = doc.selectFirst("h1, span.text-xl")?.text()?.trim() ?: manga.title

        val coverUrl = doc.selectFirst("img[alt*='poster'], img[alt*='cover']")?.attr("src")
            ?: manga.coverUrl

        val description = doc.selectFirst("span.font-medium.text-sm, div[class*='description']")
            ?.text()?.trim()

        val author = doc.select("div:contains(Author) + div, span:contains(Author) ~ span")
            .firstOrNull()?.text()?.trim()

        val artist = doc.select("div:contains(Artist) + div, span:contains(Artist) ~ span")
            .firstOrNull()?.text()?.trim()

        val statusText = doc.select("div:contains(Status) + div, span:contains(Status) ~ span")
            .firstOrNull()?.text()?.trim()?.lowercase()

        val status = when {
            statusText?.contains("ongoing") == true -> MangaStatus.ONGOING
            statusText?.contains("completed") == true -> MangaStatus.COMPLETED
            statusText?.contains("hiatus") == true -> MangaStatus.HIATUS
            statusText?.contains("dropped") == true || statusText?.contains("cancelled") == true -> MangaStatus.CANCELLED
            else -> MangaStatus.UNKNOWN
        }

        val genres = doc.select("div:contains(Genre) ~ div a, button[class*='genre']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val rating = doc.selectFirst("span:contains(/10), div[class*='rating']")
            ?.text()
            ?.replace(Regex("[^0-9.]"), "")
            ?.toFloatOrNull()

        return manga.copy(
            title = title,
            coverUrl = coverUrl,
            description = description,
            author = author,
            artist = artist,
            status = status,
            genres = genres,
            rating = rating
        )
    }

    override suspend fun getChapters(manga: Manga): List<Chapter> {
        val doc = httpClient.getDocument(manga.url)

        return doc.select("a[href*='/chapter/'], div[class*='chapter'] a").mapNotNull { element ->
            try {
                parseChapter(element, manga.id)
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.number }
    }

    private fun parseChapter(element: Element, mangaId: String): Chapter? {
        val href = element.attr("href")
        if (!href.contains("/chapter/")) return null

        val chapterSlug = href.substringAfter("/chapter/").substringBefore("/")

        // Parse chapter number from text or URL
        val chapterText = element.text().trim()
        val chapterNumber = extractChapterNumber(chapterText)
            ?: extractChapterNumber(chapterSlug)
            ?: return null

        val title = if (chapterText.contains(":")) {
            chapterText.substringAfter(":").trim()
        } else null

        // Parse upload date if available
        val dateText = element.selectFirst("span[class*='text-xs'], time")?.text()
        val uploadDate = parseDateText(dateText)

        return Chapter(
            id = chapterSlug,
            mangaId = mangaId,
            sourceId = id,
            url = if (href.startsWith("http")) href else "$baseUrl$href",
            title = title,
            number = chapterNumber,
            uploadDate = uploadDate
        )
    }

    private fun extractChapterNumber(text: String): Float? {
        // Match patterns like "Chapter 123", "Ch. 123", "123", "123.5"
        val patterns = listOf(
            Regex("""(?:chapter|ch\.?)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
            Regex("""^(\d+(?:\.\d+)?)$"""),
            Regex("""(\d+(?:\.\d+)?)""")
        )

        for (pattern in patterns) {
            pattern.find(text)?.groupValues?.getOrNull(1)?.toFloatOrNull()?.let {
                return it
            }
        }
        return null
    }

    private fun parseDateText(dateText: String?): Long? {
        if (dateText.isNullOrBlank()) return null

        val now = System.currentTimeMillis()
        val text = dateText.lowercase()

        return when {
            text.contains("just now") || text.contains("second") -> now
            text.contains("minute") -> {
                val mins = Regex("""(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 1
                now - mins * 60 * 1000
            }
            text.contains("hour") -> {
                val hours = Regex("""(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 1
                now - hours * 60 * 60 * 1000
            }
            text.contains("day") -> {
                val days = Regex("""(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 1
                now - days * 24 * 60 * 60 * 1000
            }
            text.contains("week") -> {
                val weeks = Regex("""(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 1
                now - weeks * 7 * 24 * 60 * 60 * 1000
            }
            text.contains("month") -> {
                val months = Regex("""(\d+)""").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 1
                now - months * 30 * 24 * 60 * 60 * 1000
            }
            else -> null
        }
    }

    override suspend fun getPages(chapter: Chapter): List<Page> {
        val doc = httpClient.getDocument(chapter.url)

        // Look for image URLs in various patterns used by Asura
        val pages = mutableListOf<Page>()

        // Pattern 1: Direct img tags in reader
        doc.select("div[class*='reader'] img, div[class*='chapter'] img, img[class*='page']")
            .forEachIndexed { index, img ->
                val src = img.attr("src").ifBlank { img.attr("data-src") }
                if (src.isNotBlank() && isValidPageImage(src)) {
                    pages.add(Page(
                        index = index,
                        imageUrl = src,
                        referer = baseUrl
                    ))
                }
            }

        // Pattern 2: Look for image URLs in script tags (Next.js data)
        if (pages.isEmpty()) {
            val scriptContent = doc.select("script").map { it.data() }.joinToString()
            val imagePattern = Regex(""""url"\s*:\s*"(https?://[^"]+\.(jpg|jpeg|png|webp)[^"]*)"""", RegexOption.IGNORE_CASE)

            imagePattern.findAll(scriptContent)
                .map { it.groupValues[1] }
                .filter { isValidPageImage(it) }
                .distinct()
                .forEachIndexed { index, url ->
                    pages.add(Page(
                        index = index,
                        imageUrl = url.replace("\\/", "/"),
                        referer = baseUrl
                    ))
                }
        }

        return pages.sortedBy { it.index }
    }

    private fun isValidPageImage(url: String): Boolean {
        val invalidPatterns = listOf("icon", "logo", "avatar", "banner", "ad", "thumb", "cover")
        return invalidPatterns.none { url.lowercase().contains(it) }
    }

    override suspend fun ping(): Boolean {
        return try {
            val latency = httpClient.ping(baseUrl)
            latency > 0
        } catch (e: Exception) {
            false
        }
    }
}
