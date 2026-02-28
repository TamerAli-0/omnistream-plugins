package com.omnistream.pluginapi.model

/** Helper type used by source implementations to build home page content lists. */
data class HomeSection(
    val name: String,
    val items: List<Video>,
    val hasMore: Boolean = false,
    val moreUrl: String? = null
)
