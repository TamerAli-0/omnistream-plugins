package com.omnistream.plugin.builtin

import com.omnistream.pluginapi.PluginApi
import com.omnistream.pluginapi.plugin.OmniPlugin

class MangaDexPlugin : OmniPlugin() {
    override val pluginName = "MangaDexSource"
    override val pluginVersion = "1.0.0"

    override fun load() {
        registerSource(com.omnistream.source.manga.MangaDexSource(PluginApi.http))
    }
}
