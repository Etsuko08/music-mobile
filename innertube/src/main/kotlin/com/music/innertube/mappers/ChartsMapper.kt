package com.music.innertube.mappers

import com.music.innertube.InnerTube
import com.music.innertube.models.AlbumItem
import com.music.innertube.models.Artist
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.MusicResponsiveListItemRenderer
import com.music.innertube.models.MusicTwoRowItemRenderer
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SectionListRenderer
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import com.music.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.music.innertube.models.body.BrowseBody
import com.music.innertube.models.getContinuation
import com.music.innertube.models.response.BrowseResponse
import com.music.innertube.pages.ChartsPage
import io.ktor.client.call.body
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json

internal object ChartsMapper {
    private const val CHARTS_BROWSE_ID = "FEmusic_charts"
    private const val CHARTS_PARAMS = "ggMGCgQIgAQ%3D"

    suspend fun getChartsPage(innerTube: InnerTube, continuation: String?): Result<ChartsPage> = runCatching {
        if (continuation != null) {
            val response = innerTube.browse(
                client = WEB_REMIX,
                browseId = CHARTS_BROWSE_ID,
                params = CHARTS_PARAMS,
                continuation = continuation,
            ).body<BrowseResponse>()
            return@runCatching parseChartsResponse(response)
        }

        val currentCountry = innerTube.locale.gl
        val (localResult, usResult, globalResult) = coroutineScope {
            val localDeferred = async {
                runCatching {
                    innerTube.browse(
                        client = WEB_REMIX,
                        browseId = CHARTS_BROWSE_ID,
                        params = CHARTS_PARAMS,
                    ).body<BrowseResponse>()
                }
            }
            val usDeferred = if (!currentCountry.equals("US", ignoreCase = true)) {
                async {
                    runCatching {
                        innerTube.browse(
                            client = WEB_REMIX,
                            browseId = CHARTS_BROWSE_ID,
                            params = CHARTS_PARAMS,
                            formData = BrowseBody.FormData(selectedValues = listOf("US")),
                        ).body<BrowseResponse>()
                    }
                }
            } else {
                null
            }
            val globalDeferred = async {
                runCatching {
                    innerTube.browse(
                        client = WEB_REMIX,
                        browseId = CHARTS_BROWSE_ID,
                        params = CHARTS_PARAMS,
                        formData = BrowseBody.FormData(selectedValues = listOf("ZZ")),
                    ).body<BrowseResponse>()
                }
            }
            Triple(localDeferred.await(), usDeferred?.await(), globalDeferred.await())
        }

        val localPage = localResult.map { parseChartsResponse(it) }.getOrNull()
        val usPage = usResult?.map { parseChartsResponse(it) }?.getOrNull()
        val globalPage = globalResult.map { parseChartsResponse(it) }?.getOrNull()

        if (localPage == null && usPage == null && globalPage == null) {
            localResult.getOrThrow()
        }

        val localPlaylists = extractVideoChartPlaylists(localPage)
        val usPlaylists = extractVideoChartPlaylists(usPage)
        val globalPlaylists = extractVideoChartPlaylists(globalPage)

        val localTrendingPlaylist = localPlaylists.firstOrNull {
            it.title.contains("Trending", ignoreCase = true)
        } ?: localPlaylists.firstOrNull {
            it.title.contains("Daily", ignoreCase = true)
        } ?: localPlaylists.firstOrNull()

        val localTopPlaylist = localPlaylists.firstOrNull {
            it.title.contains("Top", ignoreCase = true) && !it.title.contains("Trending", ignoreCase = true)
        } ?: localPlaylists.firstOrNull { it.id != localTrendingPlaylist?.id }

        val usTrendingPlaylist = usPlaylists.firstOrNull {
            it.title.contains("Trending", ignoreCase = true)
        } ?: usPlaylists.firstOrNull()

        val usTopPlaylist = usPlaylists.firstOrNull {
            it.title.contains("Top", ignoreCase = true) && !it.title.contains("Trending", ignoreCase = true)
        } ?: usPlaylists.firstOrNull { it.id != usTrendingPlaylist?.id }

        val globalTopPlaylist = globalPlaylists.firstOrNull {
            it.id != localTopPlaylist?.id && it.id != usTopPlaylist?.id &&
                (it.title.contains("Top", ignoreCase = true) || it.title.contains("Daily", ignoreCase = true))
        } ?: globalPlaylists.firstOrNull { it.id != localTrendingPlaylist?.id }

        val playlistIdsToFetch = listOfNotNull(
            localTrendingPlaylist?.id,
            usTrendingPlaylist?.id,
            localTopPlaylist?.id,
            usTopPlaylist?.id,
            globalTopPlaylist?.id,
        ).distinct().take(5)

        val playlistSongs = coroutineScope {
            playlistIdsToFetch.associateWith { playlistId ->
                async {
                    PlaylistMapper.playlist(innerTube, playlistId).getOrNull()?.songs.orEmpty()
                }
            }.mapValues { (_, deferred) -> deferred.await() }
        }

        val localDirectTop = localPage?.sections.orEmpty()
            .filter { it.chartType == ChartsPage.ChartType.TOP }
            .flatMap { it.items.filterIsInstance<SongItem>() }
        val localDirectTrending = localPage?.sections.orEmpty()
            .filter { it.chartType == ChartsPage.ChartType.TRENDING }
            .flatMap { it.items.filterIsInstance<SongItem>() }

        val topSongs = interleaveDistinctSongs(
            listOfNotNull(
                localDirectTop,
                usTopPlaylist?.id?.let { playlistSongs[it] },
                localTopPlaylist?.id?.let { playlistSongs[it] },
                globalTopPlaylist?.id?.let { playlistSongs[it] },
            ),
            limit = 40,
        )

        val trendingSongs = interleaveDistinctSongs(
            listOfNotNull(
                localDirectTrending,
                localTrendingPlaylist?.id?.let { playlistSongs[it] },
                usTrendingPlaylist?.id?.let { playlistSongs[it] },
                globalTopPlaylist?.id?.let { playlistSongs[it] },
            ),
            limit = 40,
        )

        val sections = mutableListOf<ChartsPage.ChartSection>()
        if (topSongs.isNotEmpty()) {
            sections.add(
                ChartsPage.ChartSection(
                    title = "Top charts",
                    items = topSongs,
                    chartType = ChartsPage.ChartType.TOP,
                )
            )
        }
        if (trendingSongs.isNotEmpty()) {
            sections.add(
                ChartsPage.ChartSection(
                    title = "Trending",
                    items = trendingSongs,
                    chartType = ChartsPage.ChartType.TRENDING,
                )
            )
        }

        val baseSections = (localPage ?: usPage ?: globalPage)?.sections.orEmpty()
        baseSections.forEach { section ->
            val duplicateTitle = sections.any { it.title.equals(section.title, ignoreCase = true) }
            if (!duplicateTitle) {
                sections.add(section)
            }
        }

        ChartsPage(
            sections = sections,
            continuation = localPage?.continuation,
        )
    }

    internal fun parseChartsResponse(response: BrowseResponse): ChartsPage {
        val sections = mutableListOf<ChartsPage.ChartSection>()
        val rawContents =
            response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
                ?.tabRenderer?.content?.sectionListRenderer?.contents
                ?: response.continuationContents?.sectionListContinuation?.contents

        rawContents?.forEach { content: SectionListRenderer.Content ->
            content.musicCarouselShelfRenderer?.let { renderer ->
                val title = renderer.header?.musicCarouselShelfBasicHeaderRenderer?.title?.runs?.firstOrNull()?.text
                    ?: return@let

                val items = renderer.contents.mapNotNull { item ->
                    when {
                        item.musicResponsiveListItemRenderer != null ->
                            convertToChartItem(item.musicResponsiveListItemRenderer)
                        item.musicTwoRowItemRenderer != null ->
                            convertMusicTwoRowItem(item.musicTwoRowItemRenderer)
                        else -> null
                    }
                }

                if (items.isNotEmpty()) {
                    sections.add(
                        ChartsPage.ChartSection(
                            title = title,
                            items = items,
                            chartType = determineChartType(title),
                        )
                    )
                }
            }

            content.gridRenderer?.let { renderer ->
                val title = renderer.header?.gridHeaderRenderer?.title?.runs?.firstOrNull()?.text
                    ?: return@let

                val items = renderer.items.mapNotNull { item ->
                    item.musicTwoRowItemRenderer?.let { twoRow ->
                        convertMusicTwoRowItem(twoRow)
                    }
                }

                if (items.isNotEmpty()) {
                    sections.add(
                        ChartsPage.ChartSection(
                            title = title,
                            items = items,
                            chartType = ChartsPage.ChartType.NEW_RELEASES,
                        )
                    )
                }
            }
        }

        val continuationToken = response.continuationContents?.sectionListContinuation?.continuations?.getContinuation()
            ?: response.contents?.singleColumnBrowseResultsRenderer?.tabs?.firstOrNull()
                ?.tabRenderer?.content?.sectionListRenderer?.continuations?.getContinuation()

        return ChartsPage(
            sections = sections,
            continuation = continuationToken,
        )
    }

    private fun extractVideoChartPlaylists(page: ChartsPage?): List<PlaylistItem> {
        if (page == null) return emptyList()
        val videoChartSections = page.sections.filter {
            it.title.contains("Video", ignoreCase = true) ||
                it.title.contains("Trending", ignoreCase = true) ||
                it.title.contains("Top", ignoreCase = true)
        }
        val prioritized = videoChartSections.flatMap { it.items.filterIsInstance<PlaylistItem>() }
        return if (prioritized.isNotEmpty()) {
            prioritized
        } else {
            page.sections.flatMap { it.items.filterIsInstance<PlaylistItem>() }
        }
    }

    internal fun interleaveDistinctSongs(
        sources: List<List<SongItem>>,
        limit: Int = 40,
    ): List<SongItem> {
        val nonEmpty = sources.filter { it.isNotEmpty() }
        if (nonEmpty.isEmpty()) return emptyList()
        val seenIds = HashSet<String>()
        val result = ArrayList<SongItem>(limit)
        val maxLen = nonEmpty.maxOf { it.size }
        for (idx in 0 until maxLen) {
            for (source in nonEmpty) {
                val song = source.getOrNull(idx) ?: continue
                if (seenIds.add(song.id)) {
                    result.add(song)
                    if (result.size >= limit) return result
                }
            }
        }
        return result
    }

    private fun determineChartType(title: String): ChartsPage.ChartType {
        return when {
            title.contains("Trending", ignoreCase = true) -> ChartsPage.ChartType.TRENDING
            title.contains("Top artists", ignoreCase = true) -> ChartsPage.ChartType.GENRE
            title.contains("Top", ignoreCase = true) -> ChartsPage.ChartType.TOP
            else -> ChartsPage.ChartType.GENRE
        }
    }

    private fun convertToChartItem(renderer: MusicResponsiveListItemRenderer): YTItem? {
        return try {
            when {
                renderer.isArtist -> {
                    val browseId = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null
                    val title = renderer.flexColumns.firstOrNull()
                        ?.musicResponsiveListItemFlexColumnRenderer
                        ?.text?.runs?.firstOrNull()?.text?.takeIf { it.isNotBlank() } ?: return null
                    val menuItems = renderer.menu?.menuRenderer?.items
                    ArtistItem(
                        id = browseId,
                        title = title,
                        thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl(),
                        channelId = browseId,
                        shuffleEndpoint = menuItems?.find {
                            it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                        }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                        radioEndpoint = menuItems?.find {
                            it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                        }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                    )
                }
                renderer.flexColumns.size >= 2 && (renderer.playlistItemData?.videoId != null || (renderer.isSong && renderer.videoId != null)) -> {
                    val videoId = renderer.playlistItemData?.videoId ?: renderer.videoId ?: return null
                    val firstColumn = renderer.flexColumns.getOrNull(0)
                        ?.musicResponsiveListItemFlexColumnRenderer
                        ?.text ?: return null

                    val secondColumn = renderer.flexColumns.getOrNull(1)
                        ?.musicResponsiveListItemFlexColumnRenderer
                        ?.text ?: return null

                    val titleRun = firstColumn.runs?.firstOrNull() ?: return null
                    val title = titleRun.text.takeIf { it.isNotBlank() } ?: return null

                    val artists = secondColumn.runs?.mapNotNull { run ->
                        val text = run.text.trim()
                        if (text.isBlank() || text in ARTIST_SEPARATORS) return@mapNotNull null
                        if (run.navigationEndpoint == null && secondColumn.runs.any { it.navigationEndpoint != null }) {
                            return@mapNotNull null
                        }
                        Artist(
                            name = run.text,
                            id = run.navigationEndpoint?.browseEndpoint?.browseId,
                        )
                    } ?: emptyList()

                    val thirdColumn = renderer.flexColumns.getOrNull(2)
                        ?.musicResponsiveListItemFlexColumnRenderer
                        ?.text

                    SongItem(
                        id = videoId,
                        title = title,
                        artists = artists,
                        thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        musicVideoType = renderer.musicVideoType,
                        explicit = renderer.badges?.any {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } == true,
                        endpoint = renderer.navigationEndpoint?.watchEndpoint
                            ?: renderer.overlay?.musicItemThumbnailOverlayRenderer?.content?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchEndpoint,
                        chartPosition = thirdColumn?.runs?.firstOrNull()?.text?.toIntOrNull(),
                        chartChange = thirdColumn?.runs?.getOrNull(1)?.text,
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            println("Error converting chart item: ${e.message}\n${Json.encodeToString(renderer)}")
            null
        }
    }

    private fun convertMusicTwoRowItem(renderer: MusicTwoRowItemRenderer): YTItem? {
        return try {
            when {
                renderer.isSong -> {
                    val subtitle = renderer.subtitle?.runs ?: return null
                    SongItem(
                        id = renderer.navigationEndpoint.watchEndpoint?.videoId ?: return null,
                        title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                        artists = subtitle.mapNotNull {
                            it.navigationEndpoint?.browseEndpoint?.browseId?.let { id ->
                                Artist(name = it.text, id = id)
                            }
                        },
                        thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        musicVideoType = renderer.musicVideoType,
                        explicit = renderer.subtitleBadges?.any {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } == true,
                        endpoint = renderer.navigationEndpoint.watchEndpoint,
                    )
                }
                renderer.isAlbum -> {
                    AlbumItem(
                        browseId = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null,
                        playlistId = renderer.thumbnailOverlay?.musicItemThumbnailOverlayRenderer?.content
                            ?.musicPlayButtonRenderer?.playNavigationEndpoint
                            ?.watchPlaylistEndpoint?.playlistId ?: return null,
                        title = renderer.title.runs?.firstOrNull()?.text ?: return null,
                        artists = renderer.subtitle?.runs?.filterIndexed { idx, _ -> idx % 2 == 1 }?.mapNotNull {
                            it.navigationEndpoint?.browseEndpoint?.browseId?.let { id ->
                                Artist(name = it.text, id = id)
                            }
                        },
                        year = renderer.subtitle?.runs?.lastOrNull()?.text?.toIntOrNull(),
                        thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: return null,
                        explicit = renderer.subtitleBadges?.any {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } == true,
                    )
                }
                renderer.isPlaylist -> {
                    val browseId = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null
                    val title = renderer.title.runs?.firstOrNull()?.text?.takeIf { it.isNotBlank() } ?: return null
                    val menuItems = renderer.menu?.menuRenderer?.items
                    val authorRun = renderer.subtitle?.runs?.lastOrNull()
                    PlaylistItem(
                        id = browseId.removePrefix("VL"),
                        title = title,
                        author = authorRun?.let {
                            Artist(
                                name = it.text,
                                id = it.navigationEndpoint?.browseEndpoint?.browseId,
                            )
                        },
                        songCountText = null,
                        thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl(),
                        playEndpoint = renderer.thumbnailOverlay?.musicItemThumbnailOverlayRenderer?.content
                            ?.musicPlayButtonRenderer?.playNavigationEndpoint?.watchPlaylistEndpoint,
                        shuffleEndpoint = menuItems?.find {
                            it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                        }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                        radioEndpoint = menuItems?.find {
                            it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                        }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                    )
                }
                renderer.isArtist -> {
                    val browseId = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null
                    val title = renderer.title.runs?.firstOrNull()?.text?.takeIf { it.isNotBlank() } ?: return null
                    val menuItems = renderer.menu?.menuRenderer?.items
                    ArtistItem(
                        id = browseId,
                        title = title,
                        thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl(),
                        channelId = browseId,
                        shuffleEndpoint = menuItems?.find {
                            it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE"
                        }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                        radioEndpoint = menuItems?.find {
                            it.menuNavigationItemRenderer?.icon?.iconType == "MIX"
                        }?.menuNavigationItemRenderer?.navigationEndpoint?.watchPlaylistEndpoint,
                    )
                }
                else -> null
            }
        } catch (e: Exception) {
            println("Error converting two row item: ${e.message}\n${Json.encodeToString(renderer)}")
            null
        }
    }

    private val ARTIST_SEPARATORS = setOf("•", "·", ",", "&", "and")
}
