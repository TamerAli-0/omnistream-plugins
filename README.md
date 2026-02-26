# OmniStream Plugins

Official plugin repository for [OmniStream](https://github.com/TamerAli-0/OmniStream-Beta).

## Adding This Repository

In OmniStream → Settings → Plugin Manager → Repositories → Add:

```
https://raw.githubusercontent.com/TamerAli-0/omnistream-plugins/main/repo.json
```

## Available Plugins

| Plugin | Type | Language |
|--------|------|----------|
| MangaDex | Manga | EN |
| AsuraComic | Manga | EN |
| Manga Multi | Manga | EN |
| 9AnimeTV | Video | EN |
| GogoAnime | Video | EN |
| AnimeKai | Video | EN |
| Blkom | Video | AR |
| Goojara | Video | EN |
| FlickyStream | Video | EN |
| RiveStream | Video | EN |
| WatchFlix | Video | EN |

## Plugin Format

OmniStream plugins are `.omni` files — ZIP archives containing compiled Kotlin/Android DEX code.
Each plugin implements the `OmniPlugin` interface from the `plugin-api` module.

## Contributing

Plugin sources go in `plugin-sources/<pluginId>/`. Each source directory must contain:
- `build.gradle.kts` — Gradle build script (depends on `plugin-api`)
- `plugin.json` — Plugin manifest (pluginId, name, version, type, lang, entryClass)
- `src/main/kotlin/` — Plugin Kotlin source files

The GitHub Actions workflow in `.github/workflows/build-plugins.yml` automatically:
1. Compiles the Kotlin source to JAR
2. Converts to DEX using `d8`
3. Packages as `.omni` (ZIP containing `classes.dex` + `plugin.json`)
4. Updates `repo.json` with the new artifact URL
