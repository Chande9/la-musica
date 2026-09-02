/**
 * Personal edition stub — the standard edition ships without ARCOD.
 * Same package/class name so shared code compiles in both flavors.
 */
package com.metrolist.music.qobuz.arcod

object ArcodSessionManager {
    fun init(context: android.content.Context) = Unit
    fun setRefreshToken(token: String) = Unit
    fun isConfigured(): Boolean = false
    suspend fun validAccessToken(): String? = null
}