/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.utils

import com.music.innertube.models.AlbumItem
import com.music.innertube.models.PlaylistItem
import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem

val DEVOTIONAL_FILTER_REGEX = Regex(
    "(?i)\\b(anand\\s*sahib|durga|gayatri|mantra|aarti|arti|chalisa|bhajan|bhakt|bhakti|kirtan|shabad|katha|stotram|stuti|jagjanani|aigiri\\s*nandini|mahishasura|mahishasuramardini|shakti\\s*mix|namita\\s*agrawal|shahnaz\\s*akhtar|suresh\\s*wadkar|pavagadh|durge\\s*mai|chhoti\\s*naav|bhole|shri\\s*ram|shriram|ram\\s*bhajan|krishna|radha|shiv|mahadev|har\\s*har\\s*mahadev|waheguru|satnam|guru\\s*nanak|sanskrit|dhun|pravachan|satsang|jagran|rehras|ardas|paath|vedic|shloka|sloka|amritwani|devotional|baba|sai\\s*baba|hanuman|ganesh|mata\\s*ke\\s*bhetein|japji|sukhamani|jai\\s*shri|bhagwat|geeta|updesh|parama\\s*danga|rame\\s*rame|naadham\\s*en\\s*jeevane)\\b"
)

fun isDevotionalText(text: String?): Boolean {
    if (text.isNullOrBlank()) return false
    return DEVOTIONAL_FILTER_REGEX.containsMatchIn(text)
}

fun isDevotionalItem(item: YTItem): Boolean {
    if (isDevotionalText(item.title)) return true
    when (item) {
        is SongItem -> {
            if (item.artists.any { isDevotionalText(it.name) }) return true
            val album = item.album
            if (album != null && isDevotionalText(album.name)) return true
        }
        is AlbumItem -> {
            if (item.artists?.any { isDevotionalText(it.name) } == true) return true
        }
        is PlaylistItem -> {
            val author = item.author
            if (author != null && isDevotionalText(author.name)) return true
        }
        else -> {}
    }
    return false
}
