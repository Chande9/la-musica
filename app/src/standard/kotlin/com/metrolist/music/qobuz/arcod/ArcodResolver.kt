package com.metrolist.music.qobuz.arcod

import com.metrolist.music.qobuz.QobuzAudioProvider

/**
 * Standard edition stub. arcod.xyz does not exist in this build; the grey-zone
 * cascade is disabled and this resolver is never expected to run.
 */
object ArcodResolver {
    suspend fun resolve(
        mediaId: String,
        title: String,
        artist: String,
        album: String?,
        isrc: String?,
    ): QobuzAudioProvider.Resolved? = null
}