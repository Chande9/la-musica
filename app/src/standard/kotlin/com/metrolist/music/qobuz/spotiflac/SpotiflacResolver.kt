package com.metrolist.music.qobuz.spotiflac

import com.metrolist.music.qobuz.QobuzAudioProvider

/**
 * Standard edition stub. The SpotiFLAC proxy does not exist in this build;
 * the grey-zone cascade is disabled and this resolver is never expected to run.
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
