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
    "(?i)\\b(" +
    // Deities & Sacred Figures
    "shri\\s*ram|shriram|siya\\s*ram|jai\\s*shri\\s*ram|ayodhya|raghunandan|rameshwaram|" +
    "hanuman|bajrangbali|sankat\\s*mochan|" +
    "shiv|shiva|mahadev|bhole|bholenath|har\\s*har\\s*mahadev|om\\s*namah\\s*shivay|kailash|kedarnath|somnath|" +
    "krishna|radha|radhe|kanha|kanhaiya|govind|gopal|shyam|vrindavan|barsana|" +
    "durga|durge|sherawali|shera\\s*wali|vaishno\\s*devi|pahadawali|mata\\s*rani|jagjanani|amritwani|maa\\s*kali|kali\\s*mata|" +
    "chhathi\\s*maiya|chhath\\s*geet|chhath|chhati\\s*maiya|" +
    "ganesh|ganpati|vinayaka|bappa|kartikeya|" +
    "sai\\s*baba|shirdi|ramdev\\s*pir|khatu\\s*shyam|" +
    "waheguru|satnam|guru\\s*nanak|guru\\s*gobind|anand\\s*sahib|japji|sukhamani|rehras|ardas|" +
    "allah|khwaja|pir|dargah|azaan|qawwali|naat|" +
    // Forms & genres
    "bhajan|bhakt|bhakti|kirtan|shabad|katha|stotram|stuti|mantra|aarti|arti|chalisa|chalisas|" +
    "dhun|pravachan|satsang|jagran|chowki|paath|vedic|shloka|sloka|stotras|updesh|geeta|bhagwat|" +
    "devotional|spiritual|prayer|chant|chants|hymn|gospel|sanskrit|" +
    // Known devotional artists & specific phrases from screenshots
    "kanhiya\\s*mittal|kanhiya|hansraj\\s*raghuwanshi|raghuwanshi|lakhbir\\s*singh\\s*lakkha|lakkha|" +
    "anup\\s*jalota|anuradha\\s*paudwal|narendra\\s*chanchal|sadhana\\s*sargam|karina\\s*pandey|" +
    "namita\\s*agrawal|shahnaz\\s*akhtar|suresh\\s*wadkar|mithu\\s*marshal|pawan\\s*singh\\s*bhakti|khesari\\s*bhakti|" +
    "chhoti\\s*naav|choti\\s*naav|aao\\s*ayodhya|tere\\s*bhavan|maaya\\s*tere\\s*bhavan|adbhut\\s*maaya|" +
    "rame\\s*rame|pavagadh|naadham\\s*en\\s*jeevane|parama\\s*danga|aigiri\\s*nandini|mahishasura|mahishasuramardini|shakti\\s*mix" +
    ")\\b"
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
