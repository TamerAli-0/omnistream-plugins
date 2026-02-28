package com.omnistream.plugin.builtin

import com.omnistream.pluginapi.PluginApi
import com.omnistream.pluginapi.plugin.OmniPlugin

class BlkomPlugin : OmniPlugin() {
    override val pluginName = "BlkomSource"
    override val pluginVersion = "1.0.0"

    override fun load() {
        registerSource(com.omnistream.source.anime.BlkomSource(PluginApi.http))
    }
}
