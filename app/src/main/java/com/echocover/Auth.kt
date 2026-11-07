package com.echocover

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Spotify Authorization Code + PKCE flow. No client secret is needed or stored.
 * Tokens live in app-private SharedPreferences.
 */
class Auth(context: Context) {

    private val prefs = context.getSharedPreferences("spotify_auth", Context.MODE_PRIVATE)

    val isLoggedIn: Boolean
        get() = prefs.getString("refresh", null) != null

    fun buildAuthUrl(): String {
        val verifier = b64(ByteArray(48).also { SecureRandom().nextBytes(it) })
        val challenge = b64(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        prefs.edit().putString("verifier", verifier).apply()

        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", Config.CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", Config.REDIRECT_URI)
            .appendQueryParameter("scope", Config.SCOPES)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .build()
            .toString()
    }

    fun exchangeCode(code: String): Boolean {
        val verifier = prefs.getString("verifier", null) ?: return false
        return tokenRequest(
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to Config.REDIRECT_URI,
                "client_id" to Config.CLIENT_ID,
                "code_verifier" to verifier
            )
        )
    }

    /** Returns a valid access token, refreshing if needed. Null if unavailable. */
    @Synchronized
    fun accessToken(forceRefresh: Boolean = false): String? {
        val token = prefs.getString("access", null)
        val expires = prefs.getLong("expires", 0L)
        if (!forceRefresh && token != null && System.currentTimeMillis() < expires - 60_000) {
            return token
        }
        val refresh = prefs.getString("refresh", null) ?: return null
        val ok = tokenRequest(
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refresh,
                "client_id" to Config.CLIENT_ID
            )
        )
        return if (ok) prefs.getString("access", null) else null
    }

    fun logout() {
        prefs.edit().clear().apply()
    }

    private fun tokenRequest(params: Map<String, String>): Boolean {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            val body = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            if (code != 200) {
                // Revoked or invalid refresh token: force a fresh login.
                if (params["grant_type"] == "refresh_token" && (code == 400 || code == 401)) {
                    logout()
                }
                return false
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val editor = prefs.edit()
                .putString("access", json.getString("access_token"))
                .putLong("expires", System.currentTimeMillis() + json.getLong("expires_in") * 1000)
            if (json.has("refresh_token")) editor.putString("refresh", json.getString("refresh_token"))
            editor.apply()
            return true
        } catch (e: Exception) {
            return false // network problem: keep tokens, try again later
        } finally {
            conn?.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun b64(bytes: ByteArray) =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
