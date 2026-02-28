package com.omnistream.pluginapi.model

/**
 * Wraps a page of results with a flag indicating whether more pages exist.
 *
 * Replaces bare List<T> returns from source methods.
 * The UI reads hasMore to decide whether to show a "Load More" button.
 *
 * @param T The type of items (Video, Manga, etc.)
 * @property items The items in this page
 * @property hasMore True if calling getSection(section, page+1) would return more items
 */
data class PagedResult<T>(
    val items: List<T>,
    val hasMore: Boolean = false
)
