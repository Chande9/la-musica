/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * ListenBrainz scrobbling client.
 * API docs: https://listenbrainz.readthedocs.io/en/latest/submission_api.html
 */
package com.metrolist.listenbrainz

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LbValidationResponse(
    val code: Int = 0,
    val message: String = "",
    val valid: Boolean = false,
    @kotlinx.serialization.SerialName("user_name") val userName: String? = null,
)

object ListenBrainz {
    const val DEFAULT_SCROBBLE_MIN_SONG_DURATION = 30
    const val DEFAULT_SCROBBLE_DELAY_PERCENT = 0.5f
    const val DEFAULT_SCROBBLE_DELAY_SECONDS = 10

    private const val BASE_URL = "https://api.listenbrainz.org/1"

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(json)
            }
            defaultRequest { url(BASE_URL) }
            expectSuccess = false
        }
    }

    /**
     * Validate a user token.
     * GET /1/validate-token
     */
    suspend fun validateToken(token: String): Result<LbValidationResponse> = runCatching {
        val resp = client.get("$BASE_URL/validate-token") {
            header(HttpHeaders.Authorization, "Token $token")
        }
        val body: LbValidationResponse = resp.body()
        require(body.valid) { "ListenBrainz token invalid: ${body.message}" }
        body
    }

    /**
     * Submit "playing_now" or a finished "single" listen.
     * POST /1/submit-listens
     */
    suspend fun submitListen(
        artist: String,
        track: String,
        album: String? = null,
        duration: Int? = null,
        timestamp: Long? = null,
        listeningType: String,
    ): Result<Unit> = runCatching {
        val trackMetadata = buildMap<String, Any> {
            put("track_name", track)
            put("artist_name", artist)
            album?.takeIf { it.isNotBlank() }?.let { put("release_name", it) }
            duration?.takeIf { it > 0 }?.let { put("duration", it * 1000) }
        }
        val payload = buildMap<String, Any> {
            put("listen_type", listeningType)
            put(
                "payload",
                listOf(
                    buildMap<String, Any> {
                        put("track_metadata", trackMetadata)
                        timestamp?.let { put("listened_at", it) }
                    }
                )
            )
        }

        val resp = client.post("$BASE_URL/submit-listens") {
            header(HttpHeaders.Authorization, "Token $token")
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        if (!resp.status.isSuccess()) {
            val err = resp.bodyAsText().take(200)
            throw RuntimeException("ListenBrainz submit failed (${resp.status.value}): $err")
        }
    }

    suspend fun updateNowPlaying(
        artist: String,
        track: String,
        album: String? = null,
        duration: Int? = null,
    ): Result<Unit> = submitListen(
        artist = artist,
        track = track,
        album = album,
        duration = duration,
        listeningType = "playing_now",
    )

    suspend fun scrobble(
        artist: String,
        track: String,
        timestamp: Long,
        album: String? = null,
        duration: Int? = null,
    ): Result<Unit> = submitListen(
        artist = artist,
        track = track,
        album = album,
        duration = duration,
        timestamp = timestamp,
        listeningType = "single",
    )
}