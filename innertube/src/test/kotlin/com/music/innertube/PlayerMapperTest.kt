package com.music.innertube

import com.music.innertube.mappers.PlayerMapper
import com.music.innertube.models.Tabs
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerMapperTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `extractTabEndpoints resolves Lyrics and Related when Comments occupies index 2`() {
        val tabs = listOf(
            decodeTab("""{"tabRenderer":{"title":"Up next","content":null,"endpoint":null}}"""),
            decodeTab(
                """{"tabRenderer":{"title":"Lyrics","content":null,"endpoint":{"browseEndpoint":{"browseId":"MPLYt_abc","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_TRACK_LYRICS"}}}}}}"""
            ),
            decodeTab("""{"tabRenderer":{"title":"Comments","content":null,"endpoint":null}}"""),
            decodeTab(
                """{"tabRenderer":{"title":"Related","content":null,"endpoint":{"browseEndpoint":{"browseId":"MPTRt_xyz","browseEndpointContextSupportedConfigs":{"browseEndpointContextMusicConfig":{"pageType":"MUSIC_PAGE_TYPE_TRACK_RELATED"}}}}}}"""
            ),
        )

        val (lyrics, related) = PlayerMapper.extractTabEndpoints(tabs)

        assertEquals("MPLYt_abc", lyrics?.browseId)
        assertEquals("MPTRt_xyz", related?.browseId)
    }

    @Test
    fun `extractTabEndpoints falls back to browseId prefix when pageType is absent`() {
        val tabs = listOf(
            decodeTab("""{"tabRenderer":{"title":"Queue","content":null,"endpoint":null}}"""),
            decodeTab("""{"tabRenderer":{"title":"Paroles","content":null,"endpoint":{"browseEndpoint":{"browseId":"MPLYt_fr"}}}}"""),
            decodeTab("""{"tabRenderer":{"title":"Commentaires","content":null,"endpoint":null}}"""),
            decodeTab("""{"tabRenderer":{"title":"Autres titres","content":null,"endpoint":{"browseEndpoint":{"browseId":"MPTRt_fr"}}}}"""),
        )

        val (lyrics, related) = PlayerMapper.extractTabEndpoints(tabs)

        assertEquals("MPLYt_fr", lyrics?.browseId)
        assertEquals("MPTRt_fr", related?.browseId)
    }

    @Test
    fun `extractTabEndpoints keeps legacy index fallback and handles missing endpoints`() {
        val legacyTabs = listOf(
            decodeTab("""{"tabRenderer":{"title":"Up next","content":null,"endpoint":null}}"""),
            decodeTab("""{"tabRenderer":{"title":"Custom1","content":null,"endpoint":{"browseEndpoint":{"browseId":"LEG_LYR"}}}}"""),
            decodeTab("""{"tabRenderer":{"title":"Custom2","content":null,"endpoint":{"browseEndpoint":{"browseId":"LEG_REL"}}}}"""),
        )

        val (lyrics, related) = PlayerMapper.extractTabEndpoints(legacyTabs)

        assertEquals("LEG_LYR", lyrics?.browseId)
        assertEquals("LEG_REL", related?.browseId)

        val empty = PlayerMapper.extractTabEndpoints(null)
        assertNull(empty.first)
        assertNull(empty.second)
    }

    private fun decodeTab(raw: String): Tabs.Tab = json.decodeFromString(raw)
}
