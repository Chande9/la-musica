package com.metrolist.music.qobuz.spotiflac

import com.metrolist.music.qobuz.QobuzAudioProvider

/**
 * Public-tree stub. The SpotiFLAC proxy (and its LAN endpoint) lives only in
 * the private tree; the public repo ships inert resolvers in both editions.
 */
object SpotiflacResolver {
    suspend fun resolve(
        mediaId: String,
        title: String,
        artist: String,
        album: String?,
        isrc: String?,
        endpoint: String,
        hires: Boolean,
    ): QobuzAudioProvider.Resolved? = null
}
