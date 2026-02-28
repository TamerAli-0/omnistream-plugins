package com.omnistream.pluginapi.plugin

import com.omnistream.pluginapi.source.MangaSource
import com.omnistream.pluginapi.source.VideoSource

/**
 * Abstract base class for all OmniStream plugins.
 *
 * PluginManager (in :app) instantiates subclasses of this class and calls load().
 * load() is where plugin authors register their sources via registerSource().
 *
 * IMPORTANT: This class lives in the pure Kotlin plugin-api module.
 * It CANNOT import android.content.Context or any android.* class.
 * Context injection (for plugins that need it, e.g., WebView-based sources)
 * is handled by PluginManager in :app via type checking after instantiation.
 *
 * Pattern: PluginManager calls plugin.load(); if the plugin needs Android context,
 * PluginManager (which lives in :app and has Context) calls loadWithContext(context)
 * via reflection or Kotlin type check — that override lives in :app, not here.
 */
abstract class OmniPlugin {

    /** Plugin display name shown in the Plugin Manager UI. */
    abstract val pluginName: String

    /** Plugin version string (semver recommended: "1.0.0"). */
    abstract val pluginVersion: String

    // Populated by registerSource() during load()
    val videoSources = mutableListOf<VideoSource>()
    val mangaSources = mutableListOf<MangaSource>()

    /**
     * Called by PluginManager after the plugin class is instantiated.
     * Override to register sources and initialize any resources.
     *
     * Call registerSource() here for each source this plugin provides.
     * Do not throw from load() unless the plugin cannot function at all.
     */
    @Throws(Throwable::class)
    open fun load() {}

    /**
     * Called by PluginManager before the plugin is removed from SourceManager.
     * Override to release resources (close connections, cancel coroutines, etc.).
     */
    open fun unload() {}

    /**
     * Register a VideoSource (anime, movie, TV) from this plugin.
     * Call this inside load().
     */
    fun registerSource(source: VideoSource) {
        videoSources.add(source)
    }

    /**
     * Register a MangaSource (manga, manhwa, manhua) from this plugin.
     * Call this inside load().
     */
    fun registerSource(source: MangaSource) {
        mangaSources.add(source)
    }
}
