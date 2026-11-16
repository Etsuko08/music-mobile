/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException
import timber.log.Timber

/**
 * Uses bounded ranges to avoid googlevideo's sustained-request throttling.
 *
 * A chunk boundary must never be reported as end-of-input. ExoPlayer treats
 * [C.RESULT_END_OF_INPUT] as "the track finished" and will happily stop the
 * queue without ever calling [androidx.media3.common.Player.Listener.onPlayerError].
 * If a chunk cannot be opened or read, this source throws instead so the
 * service can refresh the URL and retry.
 */
@UnstableApi
class ChunkedDataSource(private val upstream: DataSource) : DataSource {

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource() = ChunkedDataSource(upstream.createDataSource())
    }

    private var spec: DataSpec? = null
    private var chunked = false
    private var position = 0L
    private var startPosition = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var pieceLeft = 0L
    private var opened = false
    private var consecutiveEofRetries = 0
    private var totalLength: Long? = null
    private var totalTrusted = false
    /** Set when the server returned fewer bytes than requested: that is the real end of the file. */
    private var atEndOfResource = false

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        resetState()
        spec = dataSpec
        chunked = dataSpec.uri.isGoogleVideoUrl()
        position = dataSpec.position
        startPosition = dataSpec.position
        remaining = dataSpec.length
        if (!chunked) {
            opened = true
            return upstream.open(dataSpec)
        }

        val first = try {
            openPiece()
        } catch (error: HttpDataSource.InvalidResponseCodeException) {
            // ExoPlayer re-opens at the position where the previous bounded
            // request ended, so the last open lands exactly at (or past) the
            // end of the file and the CDN answers 416. That is a normal end of
            // track, not a playback failure: report an empty resource.
            if (error.responseCode == 416 && position > 0L) {
                val trueTotal = probeTotalLength()
                if (trueTotal != null && position >= trueTotal) {
                    remaining = 0L
                    return 0L
                }
            }
            throw error
        }
        totalLength = upstream.contentRangeLength()
        // A range response normally reports the *full* resource length. Some
        // CDNs report only the length of the requested range. Capping the whole
        // stream to that value is what made songs stop at the first chunk.
        // Trust it only when it is clearly larger than the first piece, or when
        // the server returned a short piece (a real end of file).
        totalTrusted = totalLength?.let { total ->
            total > dataSpec.position + first || first < CHUNK_BYTES
        } == true
        if (totalTrusted) {
            val available = (requireNotNull(totalLength) - dataSpec.position).coerceAtLeast(0L)
            remaining = if (remaining == C.LENGTH_UNSET.toLong()) available else minOf(remaining, available)
        }
        return remaining
    }

    private fun openPiece(): Long {
        val wanted = if (remaining == C.LENGTH_UNSET.toLong()) CHUNK_BYTES else minOf(CHUNK_BYTES, remaining)
        if (wanted <= 0L) return 0L
        val piece = requireNotNull(spec).buildUpon()
            .setPosition(position)
            .setLength(wanted)
            .build()
        try {
            val length = upstream.open(piece)
            opened = true
            pieceLeft = when {
                length == C.LENGTH_UNSET.toLong() -> wanted
                length <= 0L -> throw IOException("Empty chunk at position $position")
                // A server that ignores Range must not make us read past the
                // bounded request or advance the position incorrectly.
                length > wanted -> wanted
                else -> {
                    // Fewer bytes than asked for means the resource ends here.
                    if (length < wanted) atEndOfResource = true
                    length
                }
            }
            return pieceLeft
        } catch (error: Exception) {
            closeUpstream()
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!chunked) return upstream.read(buffer, offset, length)

        while (true) {
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            // The last piece was short: everything after it is past the end.
            if (atEndOfResource && !opened) return C.RESULT_END_OF_INPUT

            if (!opened) {
                val next = try {
                    openPiece()
                } catch (error: HttpDataSource.InvalidResponseCodeException) {
                    if (error.responseCode == 416 && isConfirmedEndOfStream()) {
                        return C.RESULT_END_OF_INPUT
                    }
                    if (error.responseCode == 416 && position > startPosition) {
                        // Without a reliable total we cannot prove this is EOF.
                        // Ask the server for the real length before deciding, so
                        // a bogus range total cannot cut a song short.
                        val trueTotal = probeTotalLength()
                        if (trueTotal != null && position >= trueTotal) {
                            return C.RESULT_END_OF_INPUT
                        }
                    }
                    if (consecutiveEofRetries++ < MAX_CHUNK_RETRIES) {
                        Timber.tag(TAG).w(error, "Retrying chunk open at $position")
                        closeUpstream()
                        continue
                    }
                    throw error
                } catch (error: Exception) {
                    if (consecutiveEofRetries++ < MAX_CHUNK_RETRIES) {
                        Timber.tag(TAG).w(error, "Retrying chunk open at $position")
                        closeUpstream()
                        continue
                    }
                    throw error
                }
                if (next <= 0L) throw IOException("Chunk at position $position returned no data")
            }

            val toRead = minOf(length.toLong(), pieceLeft).toInt()
            val read = try {
                upstream.read(buffer, offset, toRead)
            } catch (error: Exception) {
                if (consecutiveEofRetries++ < MAX_CHUNK_RETRIES) {
                    Timber.tag(TAG).w(error, "Retrying chunk read at $position")
                    closeUpstream()
                    continue
                }
                throw error
            }

            if (read == C.RESULT_END_OF_INPUT) {
                if (pieceLeft > 0L) {
                    // Premature connection termination before the chunk was
                    // fully transferred. Reconnect from the advanced position.
                    if (consecutiveEofRetries++ < MAX_CHUNK_RETRIES) {
                        Timber.tag(TAG).w(
                            "Premature EOF with $pieceLeft bytes left at $position; reconnecting"
                        )
                        closeUpstream()
                        continue
                    }
                    throw IOException(
                        "Premature end of stream with $pieceLeft bytes left at position $position"
                    )
                }
                closeUpstream()
                continue
            }

            if (read <= 0) throw IOException("Invalid read result $read at position $position")

            consecutiveEofRetries = 0
            position += read
            pieceLeft -= read
            if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
            if (pieceLeft == 0L) closeUpstream()
            return read
        }
    }

    /**
     * A 416 is only a real end-of-input when we know the resource length and
     * have reached it. Anything else is a broken range response and must go
     * through normal error recovery.
     */
    private fun isConfirmedEndOfStream(): Boolean {
        val total = totalLength ?: return false
        return totalTrusted && position >= total
    }

    /**
     * Asks for the first byte of the resource to learn its real total length.
     * Used only when a 416 arrives and the earlier total was untrustworthy.
     */
    private fun probeTotalLength(): Long? = try {
        closeUpstream()
        val probe = requireNotNull(spec).buildUpon()
            .setPosition(0L)
            .setLength(1L)
            .build()
        upstream.open(probe)
        opened = true
        // A 206 probe reports the full length in Content-Range; a 200 probe
        // (CDN ignored Range) reports it in Content-Length. Accept either.
        val total = upstream.contentRangeLength() ?: upstream.contentLengthHeader()
        closeUpstream()
        total?.takeIf { it > 0L }
    } catch (error: Exception) {
        Timber.tag(TAG).d(error, "Could not verify resource length at $position")
        closeUpstream()
        null
    }

    override fun getUri(): Uri? = upstream.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        try {
            closeUpstream()
        } finally {
            resetState()
        }
    }

    private fun closeUpstream() {
        if (opened) {
            try {
                upstream.close()
            } catch (_: Exception) {
                // Closing a failed connection must not mask the real error.
            }
        }
        opened = false
        pieceLeft = 0L
    }

    private fun resetState() {
        opened = false
        spec = null
        pieceLeft = 0L
        consecutiveEofRetries = 0
        remaining = C.LENGTH_UNSET.toLong()
        totalLength = null
        totalTrusted = false
        atEndOfResource = false
        position = 0L
        startPosition = 0L
    }

    private companion object {
        const val TAG = "ChunkedDataSource"
        const val CHUNK_BYTES = 512L * 1024
        const val MAX_CHUNK_RETRIES = 3
    }
}

internal fun Uri.isGoogleVideoUrl(): Boolean =
    scheme == "https" && (host == "googlevideo.com" || host.orEmpty().endsWith(".googlevideo.com"))

@UnstableApi
internal fun DataSource.contentRangeLength(): Long? = responseHeaders.entries
    .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
    ?.value?.firstOrNull()?.substringAfterLast('/', "")?.trim()?.toLongOrNull()

@UnstableApi
internal fun DataSource.contentLengthHeader(): Long? = responseHeaders.entries
    .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
    ?.value?.firstOrNull()?.trim()?.toLongOrNull()
