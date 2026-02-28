pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OmniStreamPlugins"

include(":plugin-api")

// Anime plugins
include(":plugin-gogoanime")
// plugin-animekai excluded: requires WebViewExtractor (app-internal)
include(":plugin-nineanime")
include(":plugin-blkom")

// Manga plugins
include(":plugin-mangadex")
include(":plugin-asuracomic")
include(":plugin-manga-multi")   // bundles MangaKakalot, ManhuaPlus, ManhuaUS, Mgeko, ManhuaFast

// Movie plugins
include(":plugin-goojara")
include(":plugin-rivestream")
include(":plugin-watchflix")
// plugin-flickystream excluded: requires CryptoUtils (app-internal)
