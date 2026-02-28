package com.omnistream.pluginapi.source

import com.omnistream.pluginapi.model.VideoLink

/**
 * Interface for video link extractors.
 * Handles extracting actual video URLs from embed pages.
 *
 * Extractors are used internally by VideoSource implementations.
 * Common hosts: VidCloud, StreamTape, MixDrop, DoodStream, etc.
 *
 * Moved from com.omnistream.source.model to com.omnistream.pluginapi.source.
 */
interface Extractor {

    /** Extractor name for display and debugging. */
    val name: String

    /** List of domains this extractor can handle. */
    val domains: List<String>

    /**
     * Check if this extractor can handle the given URL.
     * Default: checks if url contains any domain (case-insensitive).
     */
    fun canHandle(url: String): Boolean {
        return domains.any { domain ->
            url.contains(domain, ignoreCase = true)
        }
    }

    /**
     * Extract video links from an embed URL.
     *
     * @param url     The embed/player page URL
     * @param referer Optional referer header (often required for iframe sources)
     * @return List of video links with different qualities
     */
    suspend fun extract(url: String, referer: String? = null): List<VideoLink>
}

/**
 * Result of an extraction attempt.
 */
sealed class ExtractionResult {
    data class Success(val links: List<VideoLink>) : ExtractionResult()
    data class Error(val message: String) : ExtractionResult()
    data object NotSupported : ExtractionResult()
}
