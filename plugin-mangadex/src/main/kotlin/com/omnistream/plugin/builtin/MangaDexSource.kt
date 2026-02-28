package com.omnistream.source.manga

import com.omnistream.pluginapi.net.SourceHttpClient
import com.omnistream.pluginapi.model.Chapter
import com.omnistream.pluginapi.model.Manga
import com.omnistream.pluginapi.model.MangaStatus
import com.omnistream.pluginapi.model.Page
import com.omnistream.pluginapi.model.MangaContentType
import com.omnistream.pluginapi.source.MangaSource
import com.omnistream.pluginapi.model.MainPageSection
import com.omnistream.pluginapi.model.PagedResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * MangaDex source implementation.
 * Uses official MangaDex API v5.
 *
 * API Docs: https://api.mangadex.org/docs/
 */
class MangaDexSource(
    private val httpClient: SourceHttpClient
) : MangaSource {

    override val id = "mangadex"
    override val name = "MangaDex"
    override val baseUrl = "https://api.mangadex.org"
    override val lang = "en"
    override val isNsfw = false
    override val contentType = MangaContentType.ALL

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    companion object {
        private const val COVERS_URL = "https://uploads.mangadex.org/covers"
        private const val PAGE_SIZE = 20
    }

    override val hasSearch: Boolean = true
    override val hasLatest: Boolean = true

    override val mainPageSections: List<MainPageSection> = listOf(
        MainPageSection("Popular", "popular"),
        MainPageSection("Latest", "latest")
    )

    override suspend fun getSection(section: MainPageSection, page: Int): PagedResult<Manga> {
        return when (section.data) {
            "popular" -> PagedResult(getPopularInternal(page), hasMore = true)
            "latest" -> PagedResult(getLatestInternal(page), hasMore = true)
            else -> PagedResult(emptyList())
        }
    }

    private suspend fun getPopularInternal(page: Int): List<Manga> {
        val offset = (page - 1) * PAGE_SIZE
        val url = "$baseUrl/manga?limit=$PAGE_SIZE&offset=$offset&order[followedCount]=desc&includes[]=cover_art"

        return fetchMangaList(url)
    }

    private suspend fun getLatestInternal(page: Int): List<Manga> {
        val offset = (page - 1) * PAGE_SIZE
        val url = "$baseUrl/manga?limit=$PAGE_SIZE&offset=$offset&order[latestUploadedChapter]=desc&includes[]=cover_art"

        return fetchMangaList(url)
    }

    override suspend fun search(query: String, page: Int): PagedResult<Manga> {
        val offset = (page - 1) * PAGE_SIZE
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl/manga?limit=$PAGE_SIZE&offset=$offset&title=$encodedQuery&includes[]=cover_art"

        val items = fetchMangaList(url)
        return PagedResult(items, hasMore = items.size >= PAGE_SIZE)
    }

    private suspend fun fetchMangaList(url: String): List<Manga> {
        val response = httpClient.get(url, headers = mapOf("Accept" to "application/json"))
        android.util.Log.d("MangaDexSource", "Response length: ${response.length}")
        val apiResponse = json.decodeFromString<MangaDexResponse<MangaDexManga>>(response)

        return apiResponse.data.map { it.toManga() }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        val url = "$baseUrl/manga/${manga.id}?includes[]=cover_art&includes[]=author&includes[]=artist"
        val response = httpClient.get(url, headers = mapOf("Accept" to "application/json"))
        val apiResponse = json.decodeFromString<MangaDexSingleResponse<MangaDexManga>>(response)

        return apiResponse.data.toManga()
    }

    override suspend fun getChapters(manga: Manga): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        var offset = 0
        var hasMore = true

        while (hasMore) {
            val url = "$baseUrl/manga/${manga.id}/feed?limit=100&offset=$offset&translatedLanguage[]=$lang&order[chapter]=desc&includes[]=scanlation_group"
            val response = httpClient.get(url, headers = mapOf("Accept" to "application/json"))
            val apiResponse = json.decodeFromString<MangaDexResponse<MangaDexChapter>>(response)

            chapters.addAll(apiResponse.data.map { it.toChapter(manga.id) })

            offset += 100
            hasMore = apiResponse.data.size == 100
        }

        return chapters
    }

    override suspend fun getPages(chapter: Chapter): List<Page> {
        val url = "$baseUrl/at-home/server/${chapter.id}"
        android.util.Log.d("MangaDexSource", "Getting pages for chapter: ${chapter.id}")
        android.util.Log.d("MangaDexSource", "URL: $url")

        return try {
            val response = httpClient.get(url, headers = mapOf("Accept" to "application/json"))
            android.util.Log.d("MangaDexSource", "Response: ${response.take(500)}")

            val atHome = json.decodeFromString<MangaDexAtHome>(response)
            android.util.Log.d("MangaDexSource", "BaseUrl: ${atHome.baseUrl}, Hash: ${atHome.chapter.hash}")
            android.util.Log.d("MangaDexSource", "Page count: ${atHome.chapter.data.size}")

            val baseImageUrl = "${atHome.baseUrl}/data/${atHome.chapter.hash}"

            atHome.chapter.data.mapIndexed { index, filename ->
                Page(
                    index = index,
                    imageUrl = "$baseImageUrl/$filename",
                    referer = "https://mangadex.org/"
                )
            }.also {
                android.util.Log.d("MangaDexSource", "Created ${it.size} Page objects")
                if (it.isNotEmpty()) {
                    android.util.Log.d("MangaDexSource", "First page URL: ${it.first().imageUrl}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MangaDexSource", "Failed to get pages: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun ping(): Boolean {
        return try {
            val latency = httpClient.ping(baseUrl)
            latency > 0
        } catch (e: Exception) {
            false
        }
    }

    // Helper function to convert API manga to domain model
    private fun MangaDexManga.toManga(): Manga {
        val coverArt = relationships?.find { it.type == "cover_art" }
        val coverFilename = coverArt?.attributes?.fileName
        val coverUrl = coverFilename?.let { "$COVERS_URL/$id/$it.256.jpg" }

        val author = relationships?.find { it.type == "author" }?.attributes?.name
        val artist = relationships?.find { it.type == "artist" }?.attributes?.name

        return Manga(
            id = id,
            sourceId = this@MangaDexSource.id,
            title = attributes.title["en"]
                ?: attributes.title.values.firstOrNull()
                ?: "Unknown",
            url = "$baseUrl/manga/$id",
            coverUrl = coverUrl,
            description = attributes.description?.get("en")
                ?: attributes.description?.values?.firstOrNull(),
            author = author,
            artist = artist,
            status = when (attributes.status) {
                "ongoing" -> MangaStatus.ONGOING
                "completed" -> MangaStatus.COMPLETED
                "hiatus" -> MangaStatus.HIATUS
                "cancelled" -> MangaStatus.CANCELLED
                else -> MangaStatus.UNKNOWN
            },
            genres = attributes.tags
                ?.filter { it.attributes.group == "genre" }
                ?.mapNotNull { it.attributes.name["en"] }
                ?: emptyList(),
            tags = attributes.tags
                ?.filter { it.attributes.group != "genre" }
                ?.mapNotNull { it.attributes.name["en"] }
                ?: emptyList(),
            isNsfw = attributes.contentRating == "erotica" || attributes.contentRating == "pornographic",
            alternativeTitles = attributes.altTitles?.flatMap { it.values } ?: emptyList()
        )
    }

    private fun MangaDexChapter.toChapter(mangaId: String): Chapter {
        val scanlator = relationships?.find { it.type == "scanlation_group" }?.attributes?.name

        return Chapter(
            id = id,
            mangaId = mangaId,
            sourceId = this@MangaDexSource.id,
            url = "$baseUrl/chapter/$id",
            title = attributes.title,
            number = attributes.chapter?.toFloatOrNull() ?: 0f,
            volume = attributes.volume?.toIntOrNull(),
            scanlator = scanlator,
            uploadDate = attributes.publishAt?.let { parseIsoDate(it) },
            pageCount = attributes.pages
        )
    }

    private fun parseIsoDate(isoDate: String): Long? {
        return try {
            java.time.Instant.parse(isoDate).toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }
}

// API Response models
@Serializable
private data class MangaDexResponse<T>(
    val result: String,
    val data: List<T>,
    val limit: Int? = null,
    val offset: Int? = null,
    val total: Int? = null
)

@Serializable
private data class MangaDexSingleResponse<T>(
    val result: String,
    val data: T
)

@Serializable
private data class MangaDexManga(
    val id: String,
    val type: String,
    val attributes: MangaAttributes,
    val relationships: List<Relationship>? = null
)

@Serializable
private data class MangaAttributes(
    val title: Map<String, String>,
    val altTitles: List<Map<String, String>>? = null,
    val description: Map<String, String>? = null,
    val status: String? = null,
    val contentRating: String? = null,
    val tags: List<Tag>? = null
)

@Serializable
private data class Tag(
    val id: String,
    val attributes: TagAttributes
)

@Serializable
private data class TagAttributes(
    val name: Map<String, String>,
    val group: String? = null
)

@Serializable
private data class MangaDexChapter(
    val id: String,
    val type: String,
    val attributes: ChapterAttributes,
    val relationships: List<Relationship>? = null
)

@Serializable
private data class ChapterAttributes(
    val title: String? = null,
    val volume: String? = null,
    val chapter: String? = null,
    val pages: Int? = null,
    val publishAt: String? = null,
    val translatedLanguage: String? = null
)

@Serializable
private data class Relationship(
    val id: String,
    val type: String,
    val attributes: RelationshipAttributes? = null
)

@Serializable
private data class RelationshipAttributes(
    val name: String? = null,
    val fileName: String? = null
)

@Serializable
private data class MangaDexAtHome(
    val result: String,
    val baseUrl: String,
    val chapter: AtHomeChapter
)

@Serializable
private data class AtHomeChapter(
    val hash: String,
    val data: List<String>,
    val dataSaver: List<String>? = null
)
