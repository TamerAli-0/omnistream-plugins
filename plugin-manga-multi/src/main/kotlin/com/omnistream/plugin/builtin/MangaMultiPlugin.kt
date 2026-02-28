package com.omnistream.plugin.builtin

import com.omnistream.pluginapi.PluginApi
import com.omnistream.pluginapi.plugin.OmniPlugin
import com.omnistream.source.manga.MangaKakalotSource
import com.omnistream.source.manga.ManhuaPlusSource
import com.omnistream.source.manga.ManhuaUSSource
import com.omnistream.source.manga.MgekoSource
import com.omnistream.source.manga.ManhuaFastSource

class MangaMultiPlugin : OmniPlugin() {
    override val pluginName = "Manga Multi"
    override val pluginVersion = "1.0.0"

    override fun load() {
        registerSource(MangaKakalotSource(PluginApi.http))
        registerSource(ManhuaPlusSource(PluginApi.http))
        registerSource(ManhuaUSSource(PluginApi.http))
        registerSource(MgekoSource(PluginApi.http))
        registerSource(ManhuaFastSource(PluginApi.http))
    }
}
