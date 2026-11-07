package com.echocover

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

sealed class Playback {
    data class Playing(
        val imageUrl: String?,
        val isPlaying: Boolean,
        val title: String? = null,
        val artist: String? = null
    ) : Playback()
    object Idle : Playback()
    object AuthRequired : Playback()
    data class Error(val retryAfterMs: Long) : Playback()
}

/** The most recent track in the account's listening history. */
sealed class LastPlayed {
    data class Track(val title: String, val artist: String?, val imageUrl: String?) : LastPlayed()
    object NeedsReconnect : LastPlayed()    // signed in before the history permission was added
    object Unavailable : LastPlayed()
}

class SpotifyApi(private val auth: Auth) {

    private class Response(val code: Int, val body: String, val retryAfterMs: Long)

    /** What is playing right now, on whichever device is active. */
    fun current(): Playback {
        val r = call("GET", "https://api.spotify.com/v1/me/player?additional_types=episode")
            ?: return unavailable()
        return when (r.code) {
            200 -> parse(r.body)
            204 -> Playback.Idle
            401 -> { auth.logout(); Playback.AuthRequired }
            429 -> Playback.Error(r.retryAfterMs)
            else -> Playback.Error(10_000)
        }
    }

    /** The last track played on any device, with its album art. */
    fun lastPlayed(): LastPlayed {
        val r = call("GET", "https://api.spotify.com/v1/me/player/recently-played?limit=1")
            ?: return LastPlayed.Unavailable
        return when (r.code) {
            200 -> try {
                val items = JSONObject(r.body).getJSONArray("items")
                if (items.length() == 0) return LastPlayed.Unavailable
                val track = items.getJSONObject(0).getJSONObject("track")
                LastPlayed.Track(track.getString("name"), artistOf(track), imageOf(track))
            } catch (e: Exception) {
                LastPlayed.Unavailable
            }
            401, 403 -> LastPlayed.NeedsReconnect
            else -> LastPlayed.Unavailable
        }
    }

    fun skipNext() = command("POST", "next")

    fun skipPrevious() = command("POST", "previous")

    fun play() = command("PUT", "play")

    fun pause() = command("PUT", "pause")

    private fun command(method: String, name: String) {
        call(method, "https://api.spotify.com/v1/me/player/$name")
    }

    private fun unavailable(): Playback =
        if (auth.isLoggedIn) Playback.Error(10_000) else Playback.AuthRequired

    private fun parse(body: String): Playback {
        return try {
            val root = JSONObject(body)
            val item = root.optJSONObject("item") ?: return Playback.Idle
            Playback.Playing(
                imageOf(item),
                root.optBoolean("is_playing", false),
                item.optString("name").ifBlank { null },
                artistOf(item)
            )
        } catch (e: Exception) {
            Playback.Error(10_000)
        }
    }

    // Tracks carry art on the album; podcast episodes carry it directly.
    private fun imageOf(item: JSONObject): String? {
        val images = item.optJSONObject("album")?.optJSONArray("images") ?: item.optJSONArray("images")
        return images?.takeIf { it.length() > 0 }?.getJSONObject(0)?.optString("url")
    }

    // Tracks list their artists; podcast episodes name their show instead.
    private fun artistOf(item: JSONObject): String? {
        val artist = item.optJSONArray("artists")?.let { a ->
            (0 until a.length()).joinToString(", ") { a.getJSONObject(it).optString("name") }
        } ?: item.optJSONObject("show")?.optString("name")
        return artist?.ifBlank { null }
    }

    private fun call(method: String, url: String): Response? {
        var token = auth.accessToken() ?: return null
        var result = request(method, url, token) ?: return null
        if (result.code == 401) {
            token = auth.accessToken(forceRefresh = true) ?: return null
            result = request(method, url, token) ?: return null
        }
        return result
    }

    private fun request(method: String, url: String, token: String): Response? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("Authorization", "Bearer $token")
                if (method == "POST" || method == "PUT") {
                    doOutput = true // sends Content-Length: 0, which Spotify requires
                    outputStream.close()
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText().orEmpty()
            val retry = (conn.getHeaderField("Retry-After")?.toLongOrNull() ?: 5L) * 1000
            Response(code, body, retry)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
