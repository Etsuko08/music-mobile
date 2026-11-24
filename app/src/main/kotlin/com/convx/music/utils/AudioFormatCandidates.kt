package com.convx.music.utils

import com.convx.music.constants.AudioQuality
import com.music.innertube.models.response.PlayerResponse.StreamingData.Format

/**
 * True when Android can decode this format on the SoC (AAC in an MP4 container),
 * as opposed to Opus/Vorbis in WebM, which Media3 decodes in software on virtually
 * every phone. Software Opus decoding is the first thing to starve on a weak
 * MediaTek device once the canvas/video decoder and the UI are also busy, and a
 * starved audio thread is heard as "the song stops after a few seconds".
 */
private fun Format.isHardwareAudio(): Boolean =
    mimeType.contains("mp4", true) || mimeType.contains("aac", true)

/**
 * Ranks the audio formats returned by YouTube.
 *
 * The original/dub track always wins. Within a quality tier the hardware-decodable
 * AAC stream is preferred: it is weighted as if it had a third more bitrate, so a
 * normal 128 kbps AAC beats a 160 kbps Opus (the same audio quality, but decoded on
 * the SoC instead of the CPU), while a low-bitrate AAC (48/64 kbps) still loses to
 * a high-bitrate Opus. [preferAac] keeps the old hard filter for the recovery path,
 * where only AAC can be retried.
 */
internal fun rankedAudioFormats(
    formats: List<Format>,
    quality: AudioQuality,
    metered: Boolean,
    preferAac: Boolean,
): List<Format> {
    val highQuality = quality == AudioQuality.HIGH || (quality == AudioQuality.AUTO && !metered)
    return formats.filter {
        it.isAudio && (!preferAac || it.isHardwareAudio())
    }.sortedWith(
        compareByDescending<Format> { it.isOriginal }.thenByDescending {
            val weightedBitrate =
                it.bitrate.toLong() * (if (it.isHardwareAudio()) HARDWARE_AUDIO_WEIGHT_PERCENT else 100) / 100
            weightedBitrate * (if (highQuality) 1 else -1)
        },
    )
}

/** 135% keeps comparable AAC above Opus, without promoting a 48 kbps AAC over it. */
private const val HARDWARE_AUDIO_WEIGHT_PERCENT = 135L
