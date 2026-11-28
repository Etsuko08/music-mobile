package com.music.innertube

import com.music.innertube.mappers.ChartsMapper
import com.music.innertube.models.ArtistItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.response.BrowseResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartsMapperTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `parseChartsResponse parses Video charts playlists and Top artists without playlistItemData`() {
        val response = json.decodeFromString<BrowseResponse>(
            """{
            "responseContext":{},
            "contents":{
                "singleColumnBrowseResultsRenderer":{
                    "tabs":[{
                        "tabRenderer":{
                            "content":{
                                "sectionListRenderer":{
                                    "contents":[
                                        {
                                            "musicCarouselShelfRenderer":{
                                                "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Video charts"}]}}},
                                                "contents":[
                                                    {
                                                        "musicTwoRowItemRenderer":{
                                                            "title":{"runs":[{"text":"Trending 20 India"}]},
                                                            "subtitle":{"runs":[{"text":"Chart • YouTube Music"}]},
                                                            "thumbnailRenderer":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.org/p.jpg"}]}}},
                                                            "navigationEndpoint":{"browseEndpoint":{"browseId":"VLPL_TREND_IN","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_PLAYLIST"}}}}
                                                        }
                                                    }
                                                ]
                                            }
                                        },
                                        {
                                            "musicCarouselShelfRenderer":{
                                                "header":{"musicCarouselShelfBasicHeaderRenderer":{"title":{"runs":[{"text":"Top artists"}]}}},
                                                "contents":[
                                                    {
                                                        "musicResponsiveListItemRenderer":{
                                                            "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist Name"}]}}}],
                                                            "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://example.org/a.jpg"}]}}},
                                                            "navigationEndpoint":{"browseEndpoint":{"browseId":"UC_ARTIST_1","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_ARTIST"}}}}
                                                        }
                                                    }
                                                ]
                                            }
                                        }
                                    ]
                                }
                            }
                        }
                    }]
                }
            }
        }"""
        )

        val page = ChartsMapper.parseChartsResponse(response)

        assertEquals(2, page.sections.size)
        val playlist = page.sections[0].items.single() as PlaylistItem
        assertEquals("PL_TREND_IN", playlist.id)
        assertEquals("Trending 20 India", playlist.title)

        val artist = page.sections[1].items.single() as ArtistItem
        assertEquals("UC_ARTIST_1", artist.id)
        assertEquals("Artist Name", artist.title)
    }

    @Test
    fun `interleaveDistinctSongs round-robins regional and global lists without duplicates`() {
        val regional = listOf(song("r1"), song("shared"), song("r2"))
        val us = listOf(song("u1"), song("shared"), song("u2"))
        val global = listOf(song("g1"), song("u1"))

        val mixed = ChartsMapper.interleaveDistinctSongs(listOf(regional, us, global), limit = 10)

        assertEquals(listOf("r1", "u1", "g1", "shared", "r2", "u2"), mixed.map { it.id })
    }

    private fun song(id: String) = SongItem(
        id = id,
        title = id,
        artists = emptyList(),
        thumbnail = "",
    )
}
