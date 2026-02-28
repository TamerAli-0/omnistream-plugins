package com.omnistream.plugin.builtin

import com.omnistream.pluginapi.PluginApi
import com.omnistream.pluginapi.plugin.OmniPlugin

class NineAnimeTvPlugin : OmniPlugin() {
    override val pluginName = "NineAnimeTvSource"
    override val pluginVersion = "1.0.0"

    override fun load() {
        registerSource(com.omnistream.source.anime.NineAnimeTvSource(PluginApi.http))
    }
}
