/**
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.convx.music.playback.queues

import androidx.media3.common.MediaItem
import com.music.innertube.YouTube
import com.music.innertube.models.WatchEndpoint
import com.convx.music.db.entities.AlbumWithSongs
import com.convx.music.extensions.toMediaItem
import com.convx.music.models.MediaMetadata
import com.convx.music.models.toMediaMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

class LocalAlbumRadio(
    private val albumWithSongs: AlbumWithSongs,
    private val startIndex: Int = 0,
    @Volatile private var playlistId: String? = null,
    @Volatile private var continuation: String? = null,
    @Volatile private var firstTimeLoaded: Boolean = false,
    private var restoredStatus: Queue.Status? = null,
) : Queue {
    override val preloadItem: MediaMetadata?
        get() = albumWithSongs.songs.getOrNull(startIndex)?.toMediaMetadata()

    private val endpoint: WatchEndpoint?
        get() = playlistId?.let { WatchEndpoint(playlistId = it) }

    override suspend fun getInitialStatus(): Queue.Status {
        restoredStatus?.let {
            restoredStatus = null
            return it
        }

        return withContext(IO) {
            Queue.Status(
                title = albumWithSongs.album.title,
                items = albumWithSongs.songs.map { it.toMediaItem() },
                mediaItemIndex = startIndex
            )
        }
    }

    override fun hasNextPage(): Boolean = !firstTimeLoaded || continuation != null

    fun persistenceData() = QueueState(
        albumId = albumWithSongs.album.id,
        startIndex = startIndex,
        playlistId = playlistId,
        continuation = continuation,
        firstTimeLoaded = firstTimeLoaded,
    )

    override suspend fun nextPage(): List<MediaItem> = withContext(IO) {
        try {
            if (!firstTimeLoaded) {
                val targetPlaylistId = playlistId ?: YouTube.album(albumWithSongs.album.id).getOrNull()?.album?.playlistId
                playlistId = targetPlaylistId
                if (targetPlaylistId != null) {
                    val ep = WatchEndpoint(playlistId = targetPlaylistId)
                    val nextResult = YouTube.next(ep, continuation).getOrNull()
                    if (nextResult != null) {
                        continuation = nextResult.continuation
                        firstTimeLoaded = true
                        val remoteItems = nextResult.items.map { it.toMediaItem() }
                        return@withContext if (remoteItems.size > albumWithSongs.songs.size) {
                            remoteItems.subList(
                                albumWithSongs.songs.size,
                                remoteItems.size
                            )
                        } else emptyList()
                    }
                }
                firstTimeLoaded = true
                return@withContext emptyList()
            }
            val ep = endpoint
            if (ep != null && continuation != null) {
                val nextResult = YouTube.next(ep, continuation).getOrNull()
                if (nextResult != null) {
                    continuation = nextResult.continuation
                    return@withContext nextResult.items.map { it.toMediaItem() }
                }
            }
            emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }

    data class QueueState(
        val albumId: String,
        val startIndex: Int,
        val playlistId: String?,
        val continuation: String?,
        val firstTimeLoaded: Boolean,
    )
}
