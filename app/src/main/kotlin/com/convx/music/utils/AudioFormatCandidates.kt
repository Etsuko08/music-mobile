package com.convx.music.utils

import com.convx.music.constants.AudioQuality
import com.music.innertube.models.response.PlayerResponse.StreamingData.Format

internal fun rankedAudioFormats(
    formats: List<Format>,
    quality: AudioQuality,
    metered: Boolean,
    preferAac: Boolean,
): List<Format> {
    // Default to high quality unless user explicitly requests LOW quality
    val highQuality = quality != AudioQuality.LOW
    return formats.filter {
        it.isAudio && (!preferAac || it.mimeType.contains("mp4", true) || it.mimeType.contains("aac", true))
    }.sortedWith(
        compareByDescending<Format> { it.isOriginal }.thenByDescending {
            it.bitrate.toLong() * (if (highQuality) 1 else -1) +
                if (it.mimeType.startsWith("audio/webm")) 10_240 else 0
        },
    )
}
