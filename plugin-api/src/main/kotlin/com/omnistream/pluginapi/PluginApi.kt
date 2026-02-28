package com.omnistream.pluginapi

import com.omnistream.pluginapi.net.SourceHttpClient

/**
 * Global access point for host-provided services, set by the app before loading any plugin.
 *
 * Usage inside OmniPlugin.load():
 * ```kotlin
 * override fun load() {
 *     registerSource(MySource(PluginApi.http))
 * }
 * ```
 */
object PluginApi {
    /**
     * The app's HTTP client — CF bypass, cookie sharing, anti-detection headers included.
     * Set by the host app at startup before any plugin is loaded.
     */
    lateinit var http: SourceHttpClient
}
