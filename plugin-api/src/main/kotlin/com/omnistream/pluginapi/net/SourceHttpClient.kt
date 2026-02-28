package com.omnistream.pluginapi.net

import org.jsoup.nodes.Document

/**
 * HTTP client interface exposed to plugin authors via plugin-api.
 *
 * The host app (OmniStream) provides a concrete implementation backed by OkHttp
 * with CF bypass, cookie sharing, and anti-detection headers. Plugins reference
 * this interface only — they never bundle their own HTTP implementation.
 *
 * Access via [PluginApi.http] inside OmniPlugin.load().
 */
interface SourceHttpClient {

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        val AJAX_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "application/json, text/plain, */*",
            "Accept-Language" to "en-US,en;q=0.9",
            "X-Requested-With" to "XMLHttpRequest"
        )
    }

    /** GET request returning response body as String. */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null
    ): String

    /** GET request returning a parsed Jsoup Document. */
    suspend fun getDocument(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null
    ): Document

    /** POST request with form data, returning response body as String. */
    suspend fun post(
        url: String,
        data: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null
    ): String

    /** POST with XHR headers (X-Requested-With, no browser-nav headers). */
    suspend fun postXhr(
        url: String,
        data: Map<String, String>,
        referer: String? = null
    ): String

    /** Ping [url] and return latency in milliseconds, or -1 on failure. */
    suspend fun ping(url: String): Long
}
