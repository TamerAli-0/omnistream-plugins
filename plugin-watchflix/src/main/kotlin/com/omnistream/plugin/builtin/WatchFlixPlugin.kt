package com.omnistream.plugin.builtin

import com.omnistream.pluginapi.PluginApi
import com.omnistream.pluginapi.plugin.OmniPlugin

class WatchFlixPlugin : OmniPlugin() {
    override val pluginName = "WatchFlixSource"
    override val pluginVersion = "1.0.0"

    override fun load() {
        registerSource(com.omnistream.source.movie.WatchFlixSource(PluginApi.http))
    }
}
