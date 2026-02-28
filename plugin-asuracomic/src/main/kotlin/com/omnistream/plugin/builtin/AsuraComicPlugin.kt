package com.omnistream.plugin.builtin

import com.omnistream.pluginapi.PluginApi
import com.omnistream.pluginapi.plugin.OmniPlugin

class AsuraComicPlugin : OmniPlugin() {
    override val pluginName = "AsuraComicSource"
    override val pluginVersion = "1.0.0"

    override fun load() {
        registerSource(com.omnistream.source.manga.AsuraComicSource(PluginApi.http))
    }
}
