package com.omnistream.plugin.builtin

import com.omnistream.pluginapi.PluginApi
import com.omnistream.pluginapi.plugin.OmniPlugin

class GogoAnimePlugin : OmniPlugin() {
    override val pluginName = "GogoAnimeSource"
    override val pluginVersion = "1.0.0"

    override fun load() {
        registerSource(com.omnistream.source.anime.GogoAnimeSource(PluginApi.http))
    }
}
