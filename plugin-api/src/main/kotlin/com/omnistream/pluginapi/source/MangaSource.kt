package com.omnistream.pluginapi.source

import com.omnistream.pluginapi.model.Chapter
import com.omnistream.pluginapi.model.FilterList
import com.omnistream.pluginapi.model.MainPageSection
import com.omnistream.pluginapi.model.Manga
import com.omnistream.pluginapi.model.MangaContentType
import com.omnistream.pluginapi.model.Page
import com.omnistream.pluginapi.model.PagedResult

/**
 * Interface for manga/manhwa/manhua sources.
 *
 * Upgraded to CloudStream/Mihon-inspired API v2:
 * - getPopular() and getLatest() removed; replaced by getSection() with static mainPageSections
 * - search() returns PagedResult<Manga> for pagination support
 * - Capability flags for UI feature gating
 * - getFilters() for structured filter UI
 */
interface MangaSource {

    /** Unique stable identifier (snake_case). */
    val id: String

    /** Display name. */
    val name: String

    /** Base URL (used for relative URL resolution and ping). */
    val baseUrl: String

    /** ISO 639-1 language code. */
    val lang: String

    /** Whether source contains NSFW content. */
    val isNsfw: Boolean get() = false

    /** Primary content type of this source. */
    val contentType: MangaContentType get() = MangaContentType.MANGA

    // ---- Capability Flags ----

    val hasSearch: Boolean get() = false
    val hasLatest: Boolean get() = false
    val hasFilters: Boolean get() = false

    // ---- Home Page API ----

    /**
     * Static list of home sections for this source.
     * Default: Popular + Latest sections. Override to customize.
     *
     * The "data" field is passed to getSection() and used internally by the source
     * to route to the correct endpoint (e.g., "popular" or "latest").
     */
    val mainPageSections: List<MainPageSection>
        get() = listOf(
            MainPageSection("Popular", "popular"),
            MainPageSection("Latest", "latest")
        )

    /**
     * Fetch one page of a home section.
     *
     * Routing convention: check section.data to determine which endpoint to call.
     * Example:
     *   override suspend fun getSection(section: MainPageSection, page: Int): PagedResult<Manga> {
     *     return when (section.data) {
     *       "popular" -> fetchPopular(page)
     *       "latest" -> fetchLatest(page)
     *       else -> PagedResult(emptyList())
     *     }
     *   }
     */
    suspend fun getSection(section: MainPageSection, page: Int): PagedResult<Manga>

    // ---- Search API ----

    /**
     * Search for manga matching a query.
     * Only called if hasSearch = true.
     */
    suspend fun search(query: String, page: Int): PagedResult<Manga>

    // ---- Filters ----

    /** Returns filter options for search. Default: empty (no filters). */
    fun getFilters(): FilterList = FilterList()

    // ---- Detail, Chapters, Pages ----

    /** Get full details for a manga. */
    suspend fun getDetails(manga: Manga): Manga

    /** Get chapter list (newest first typically). */
    suspend fun getChapters(manga: Manga): List<Chapter>

    /** Get page images for a chapter. */
    suspend fun getPages(chapter: Chapter): List<Page>

    // ---- Health ----

    /**
     * Quick connectivity test.
     * Default: fetches page 1 of the first mainPageSection.
     */
    suspend fun ping(): Boolean = try {
        mainPageSections.firstOrNull()?.let { getSection(it, 1).items.isNotEmpty() } ?: false
    } catch (e: Exception) {
        false
    }
}

/**
 * Source metadata for display in source manager UI.
 * Kept in plugin-api so PluginManager (in :app) can access it without importing source impls.
 */
data class SourceMetadata(
    val id: String,
    val name: String,
    val lang: String,
    val isNsfw: Boolean,
    val iconUrl: String? = null,
    val description: String? = null
)
