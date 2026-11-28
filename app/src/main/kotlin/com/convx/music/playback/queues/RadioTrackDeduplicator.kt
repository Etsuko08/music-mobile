/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.playback.queues

import com.music.innertube.models.SongItem
import java.util.Locale

internal class RadioTrackDeduplicator(
    private val seedId: String? = null,
    seedTitle: String? = null,
) {
    private val seenIds = HashSet<String>()
    private val seenKeys = HashSet<String>()

    init {
        if (!seedTitle.isNullOrBlank()) {
            seenKeys.addAll(titleKeys(seedTitle))
        }
    }

    fun filterInitial(
        items: List<SongItem>,
        currentIndex: Int,
    ): Pair<List<SongItem>, Int> {
        if (items.isEmpty()) return items to 0

        val safeSeedIndex =
            items.indexOfFirst { seedId != null && it.id == seedId }
                .takeIf { it >= 0 }
                ?: currentIndex.coerceIn(0, items.lastIndex)

        val seedItem = items[safeSeedIndex]
        seenIds.add(seedItem.id)
        seenKeys.addAll(titleKeys(seedItem.title))

        val kept = ArrayList<SongItem>(items.size)
        val skippedDuplicates = ArrayList<SongItem>()

        items.forEachIndexed { index, item ->
            if (index == safeSeedIndex) {
                kept.add(item)
                return@forEachIndexed
            }
            if (!seenIds.add(item.id)) return@forEachIndexed
            val keys = titleKeys(item.title)
            if (keys.isNotEmpty() && keys.any { it in seenKeys }) {
                skippedDuplicates.add(item)
            } else {
                kept.add(item)
                seenKeys.addAll(keys)
            }
        }

        if (kept.size <= 1 && skippedDuplicates.isNotEmpty()) {
            kept.addAll(skippedDuplicates.take(5))
        }

        val resolvedIndex = kept.indexOfFirst { it.id == seedItem.id }.coerceAtLeast(0)
        return kept to resolvedIndex
    }

    fun filterNextPage(items: List<SongItem>): List<SongItem> {
        if (items.isEmpty()) return emptyList()
        val kept = ArrayList<SongItem>(items.size)
        val skippedDuplicates = ArrayList<SongItem>()

        items.forEach { item ->
            if (!seenIds.add(item.id)) return@forEach
            val keys = titleKeys(item.title)
            if (keys.isNotEmpty() && keys.any { it in seenKeys }) {
                skippedDuplicates.add(item)
            } else {
                kept.add(item)
                seenKeys.addAll(keys)
            }
        }

        return if (kept.isNotEmpty()) kept else skippedDuplicates.take(5)
    }

    companion object {
        private val PAREN_VARIANT_REGEX = Regex(
            """\(([^)]*\b(?:super\s+slowed|slowed|reverb|sped\s*up|nightcore|hardstyle|hardtekk|remix|mix|edit|mashup|cover|live|acoustic|instrumental|karaoke|audio|video|lyric|lyrics|official|explicit|clean|version|ver\.?|feat\.?|ft\.?|with|extended|radio|club|lofi|lo-fi|8d|bass\s*boosted|tiktok)\b[^)]*)\)|\[([^]]*\b(?:super\s+slowed|slowed|reverb|sped\s*up|nightcore|hardstyle|hardtekk|remix|mix|edit|mashup|cover|live|acoustic|instrumental|karaoke|audio|video|lyric|lyrics|official|explicit|clean|version|ver\.?|feat\.?|ft\.?|with|extended|radio|club|lofi|lo-fi|8d|bass\s*boosted|tiktok)\b[^]]*)]""",
            RegexOption.IGNORE_CASE,
        )
        private val TRAILING_PARENS_REGEX = Regex("""(\s*(\([^)]*\)|\[[^]]*]))+$""")
        private val VARIANT_WORDS_REGEX = Regex(
            """\b(?:super\s+slowed|slowed|reverb|sped\s*up|nightcore|hardstyle|hardtekk|phonk\s+remix|remix|mashup|karaoke|instrumental|official\s+(?:music\s+)?(?:video|audio)|lyric\s+video|lyrics|bass\s+boosted|8d\s+audio|8d)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val DASH_SPLIT_REGEX = Regex("""\s+[-–—]\s+""")
        private val NON_WORD_REGEX = Regex("""[^\p{L}\p{N}]+""")
        private val GENERIC_DASH_SUFFIX = Regex("""^(?:pt|part|vol|volume|disc|cd)\s*\d+$|^\d+$""")

        internal fun titleKeys(rawTitle: String): Set<String> {
            val cleaned = cleanCoreText(rawTitle)
            if (cleaned.isEmpty()) return emptySet()
            val keys = LinkedHashSet<String>(2)
            normalizeWordKey(cleaned).takeIf { it.length >= 2 }?.let { keys.add(it) }

            val dashParts = DASH_SPLIT_REGEX.split(cleaned)
            if (dashParts.size >= 2) {
                val tail = dashParts.last().trim()
                val tailKey = normalizeWordKey(tail)
                if (tailKey.length >= 3 && !GENERIC_DASH_SUFFIX.matches(tailKey)) {
                    keys.add(tailKey)
                }
            }
            return keys
        }

        private fun cleanCoreText(raw: String): String {
            var s = raw.lowercase(Locale.ROOT).trim()
            s = PAREN_VARIANT_REGEX.replace(s, " ")
            val afterTrailing = TRAILING_PARENS_REGEX.replace(s, " ").trim()
            if (afterTrailing.isNotBlank()) {
                s = afterTrailing
            }
            s = VARIANT_WORDS_REGEX.replace(s, " ")
            return s.trim()
        }

        private fun normalizeWordKey(text: String): String =
            NON_WORD_REGEX.replace(text, " ").trim().replace(Regex("""\s+"""), " ")
    }
}
