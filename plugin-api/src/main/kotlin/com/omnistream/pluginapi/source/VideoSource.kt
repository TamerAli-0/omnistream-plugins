package com.omnistream.pluginapi.source

import com.omnistream.pluginapi.model.Episode
import com.omnistream.pluginapi.model.FilterList
import com.omnistream.pluginapi.model.MainPageSection
import com.omnistream.pluginapi.model.PagedResult
import com.omnistream.pluginapi.model.Subtitle
import com.omnistream.pluginapi.model.Video
import com.omnistream.pluginapi.model.VideoLink
import com.omnistream.pluginapi.model.VideoType

/**
 * Interface for video sources (anime, movies, TV shows).
 *
 * CloudStream-inspired API v2:
 * - Home page = declarative mainPageSections list + lazy getSection() fetch
 * - Link loading = callback-based loadLinks() streaming results as they arrive
 * - Capability flags = UI knows what each source supports before calling
 *
 * Implementing classes live in :app (built-in sources) or in .omni plugin files.
 * They MUST NOT depend on android.* directly from their constructor — Context is
 * injected by PluginManager if needed.
 */
interface VideoSource {

    /** Unique stable identifier for this source (snake_case, never changes). */
    val id: String

    /** Display name shown in source lists and Browse tab. */
    val name: String

    /** Base URL of the site (used for ping and relative URL resolution). */
    val baseUrl: String

    /** ISO 639-1 language code: "en", "ar", "ja", etc. */
    val lang: String

    /** Content types this source provides. */
    val supportedTypes: Set<VideoType>

    // ---- Capability Flags ----
    // All default false. Source overrides only what it actually supports.
    // UI reads these to enable/disable features per source.

    /** True if this source implements a working search(). */
    val hasSearch: Boolean get() = false

    /** True if this source's mainPageSections includes a "Latest" section. */
    val hasLatest: Boolean get() = false

    /** True if getFilters() returns a non-empty FilterList. */
    val hasFilters: Boolean get() = false

    /** True if this source requires a WebView for any of its operations. */
    val usesWebView: Boolean get() = false

    /** True if users in some regions may need a VPN to access this source. */
    val vpnRecommended: Boolean get() = false

    // ---- Home Page API ----

    /**
     * Declares the sections this source shows on its home page.
     *
     * This list is STATIC — declared at compile time, never changes at runtime.
     * The app reads this list to decide which sections to render and calls
     * getSection() lazily for each one.
     *
     * Order determines display order in the home page.
     *
     * Example:
     *   override val mainPageSections = listOf(
     *     MainPageSection("Recent Episodes", "recent"),
     *     MainPageSection("Ongoing Anime", "ongoing-anime/"),
     *     MainPageSection("Popular", "popular/")
     *   )
     */
    val mainPageSections: List<MainPageSection>

    /**
     * Fetch one page of content for a home section.
     *
     * @param section One entry from mainPageSections — use section.data to build the URL
     * @param page    1-indexed page number
     * @return Paged result with items and whether more pages exist
     */
    suspend fun getSection(section: MainPageSection, page: Int): PagedResult<Video>

    // ---- Search API ----

    /**
     * Search for videos matching a query.
     * Only called if hasSearch = true.
     *
     * @param query Search query string (user-entered, not URL-encoded — source must encode)
     * @param page  1-indexed page number
     * @return Paged result
     */
    suspend fun search(query: String, page: Int): PagedResult<Video>

    // ---- Detail & Episodes ----

    /** Get full details (description, genres, cast, etc.) for a video. */
    suspend fun getDetails(video: Video): Video

    /** Get episode list for a video. For movies, returns a single episode. */
    suspend fun getEpisodes(video: Video): List<Episode>

    // ---- Link Loading ----

    /**
     * Load playable video links for an episode using a callback pattern.
     *
     * Links are emitted one at a time as each server/extractor resolves.
     * The player can start with the first link while others continue loading.
     *
     * @param episode           The episode to load links for
     * @param subtitleCallback  Called for each subtitle track discovered
     * @param callback          Called for each VideoLink found (may be called multiple times)
     * @return true if at least one link was found; false if no links could be extracted
     *
     * Thread safety: If this method launches parallel extractors internally,
     * each call to callback must be synchronized or serialized (e.g., collect
     * into a Channel and emit sequentially). The ViewModel uses a synchronized list.
     */
    suspend fun loadLinks(
        episode: Episode,
        subtitleCallback: (Subtitle) -> Unit,
        callback: (VideoLink) -> Unit
    ): Boolean

    // ---- Filters ----

    /**
     * Returns the filter options supported by this source's search.
     * Default: empty list (source does not support filters).
     */
    fun getFilters(): FilterList = FilterList()

    // ---- Health ----

    /**
     * Quick connectivity test.
     * Default: tries getSection on the first mainPageSection.
     * Override for a cheaper ping (e.g., just HEAD request to baseUrl).
     */
    suspend fun ping(): Boolean = try {
        mainPageSections.firstOrNull()?.let { getSection(it, 1).items.isNotEmpty() } ?: false
    } catch (e: Exception) {
        false
    }
}
