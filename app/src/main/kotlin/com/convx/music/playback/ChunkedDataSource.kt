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
import timber.log.Timber

/** Uses bounded ranges to avoid googlevideo's sustained-request throttling. */
@UnstableApi
class ChunkedDataSource(private val upstream: DataSource) : DataSource {

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource() = ChunkedDataSource(upstream.createDataSource())
    }

    private var spec: DataSpec? = null
    private var chunked = false
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var pieceLeft = 0L
    private var opened = false
    private var consecutiveEofRetries = 0

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        chunked = dataSpec.uri.isGoogleVideoUrl()
        consecutiveEofRetries = 0
        if (!chunked) {
            opened = true
            return upstream.open(dataSpec)
        }
        position = dataSpec.position
        remaining = dataSpec.length
        val first = openPiece()
        val total = upstream.contentRangeLength()
        val available = when {
            total != null -> (total - dataSpec.position).coerceAtLeast(0L)
            first < CHUNK_BYTES -> first
            else -> C.LENGTH_UNSET.toLong()
        }
        if (available != C.LENGTH_UNSET.toLong()) {
            remaining = if (remaining == C.LENGTH_UNSET.toLong()) available else minOf(remaining, available)
        }
        return remaining
    }

    private fun openPiece(): Long {
        val wanted = if (remaining == C.LENGTH_UNSET.toLong()) CHUNK_BYTES else minOf(CHUNK_BYTES, remaining)
        val piece = requireNotNull(spec).buildUpon().setPosition(position).setLength(wanted).build()
        opened = true
        val length = upstream.open(piece)
        pieceLeft = if (length == C.LENGTH_UNSET.toLong()) wanted else length
        return pieceLeft
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!chunked) return upstream.read(buffer, offset, length)
        while (true) {
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            if (!opened) {
                val next = try {
                    openPiece()
                } catch (e: HttpDataSource.InvalidResponseCodeException) {
                    if (e.responseCode == 416) return C.RESULT_END_OF_INPUT
                    throw e
                } catch (e: Exception) {
                    if (consecutiveEofRetries++ < MAX_CHUNK_RETRIES) {
                        Timber.tag("ChunkedDataSource").w(e, "Retrying piece open at position $position (attempt $consecutiveEofRetries)")
                        try { upstream.close() } catch (_: Exception) {}
                        opened = false
                        continue
                    }
                    throw e
                }
                if (next == 0L) return C.RESULT_END_OF_INPUT
            }

            val toRead = minOf(length.toLong(), pieceLeft).toInt()
            val read = try {
                upstream.read(buffer, offset, toRead)
            } catch (e: Exception) {
                if (consecutiveEofRetries++ < MAX_CHUNK_RETRIES) {
                    Timber.tag("ChunkedDataSource").w(e, "Retrying piece read at position $position (attempt $consecutiveEofRetries)")
                    try { upstream.close() } catch (_: Exception) {}
                    opened = false
                    pieceLeft = 0
                    continue
                }
                throw e
            }

            if (read == C.RESULT_END_OF_INPUT) {
                if (pieceLeft > 0) {
                    // Premature connection termination before full chunk was transferred.
                    // Reconnect from the current advanced position instead of killing playback.
                    if (consecutiveEofRetries++ < MAX_CHUNK_RETRIES) {
                        Timber.tag("ChunkedDataSource").d("Premature EOF with $pieceLeft bytes left; reconnecting at position $position (attempt $consecutiveEofRetries)")
                        try { upstream.close() } catch (_: Exception) {}
                        opened = false
                        pieceLeft = 0
                        continue
                    }
                    return C.RESULT_END_OF_INPUT
                }
                try { upstream.close() } catch (_: Exception) {}
                opened = false
                continue
            }

            consecutiveEofRetries = 0
            position += read
            pieceLeft -= read
            if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
            if (pieceLeft == 0L) {
                try { upstream.close() } catch (_: Exception) {}
                opened = false
            }
            return read
        }
    }

    override fun getUri(): Uri? = upstream.uri ?: spec?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        try {
            if (opened) upstream.close()
        } finally {
            opened = false
            spec = null
            pieceLeft = 0
            consecutiveEofRetries = 0
            remaining = C.LENGTH_UNSET.toLong()
        }
    }

    private companion object {
        const val CHUNK_BYTES = 512L * 1024
        const val MAX_CHUNK_RETRIES = 5
    }
}

internal fun Uri.isGoogleVideoUrl(): Boolean =
    scheme == "https" && (host == "googlevideo.com" || host.orEmpty().endsWith(".googlevideo.com"))

@UnstableApi
internal fun DataSource.contentRangeLength(): Long? = responseHeaders.entries
    .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
    ?.value?.firstOrNull()?.substringAfterLast('/', "")?.trim()?.toLongOrNull()
