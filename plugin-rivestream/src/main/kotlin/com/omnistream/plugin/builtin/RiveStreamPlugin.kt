package com.omnistream.plugin.builtin

import com.omnistream.pluginapi.PluginApi
import com.omnistream.pluginapi.plugin.OmniPlugin

class RiveStreamPlugin : OmniPlugin() {
    override val pluginName = "RiveStreamSource"
    override val pluginVersion = "1.0.0"

    override fun load() {
        registerSource(com.omnistream.source.movie.RiveStreamSource(PluginApi.http))
    }
}
