package com.metrolist.music.qobuz.amz

import com.metrolist.music.qobuz.QobuzAudioProvider

/**
 * Standard edition stub. amz.squid.wtf does not exist in this build; the
 * grey-zone cascade is disabled and this resolver is never expected to run.
 */
object AmzResolver {
    suspend fun resolve(
        mediaId: String,
        title: String,
        artist: String,
        album: String?,
        isrc: String?,
    ): QobuzAudioProvider.Resolved? = null
}
