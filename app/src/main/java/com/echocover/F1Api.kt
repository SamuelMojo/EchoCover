package com.echocover

import android.graphics.Color
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class F1Driver(
    val pos: Int,
    val num: String,
    val tla: String,
    val color: Int,
    val gap: String,        // race: gap to leader; otherwise best lap time
    val interval: String,   // race: interval to the car ahead; otherwise gap to fastest
    val inPit: Boolean,
    val out: Boolean,       // retired, stopped or knocked out
    val x: Float?,          // map position (0..1), null when not on track
    val y: Float?,
    val tyre: String? = null,   // S, M, H, I or W; null if unknown or the server is older
    val tyreLaps: Int? = null,  // laps on this set, including earlier sessions
    val tyreNew: Boolean = true
)

data class F1State(
    val live: Boolean,
    val sessionKey: Long = 0,
    val event: String = "",
    val session: String = "",
    val race: Boolean = false,
    val part: Int? = null,          // qualifying: 1, 2 or 3
    val status: String = "",
    val lap: Int? = null,
    val totalLaps: Int? = null,
    val remainingSec: Int? = null,
    val trackStatus: String = "1",
    val trackId: String? = null,
    val drivers: List<F1Driver> = emptyList(),
    val next: F1Next? = null        // between sessions: the next one on the calendar
)

/** The next session on the calendar, for the home screen countdown. */
data class F1Next(val event: String, val session: String, val startMs: Long)

/** Circuit outline, normalised to a w x h box (the longer side is 1). Points are x,y pairs. */
class F1Track(val id: String, val w: Float, val h: Float, val points: FloatArray)

/** Talks to the f1server (see f1server/README.md), which does the FastF1 work. */
class F1Api {

    val enabled: Boolean
        get() = Config.F1_SERVER.isNotBlank()

    /** Current session summary, or null if the server can't be reached. */
    fun state(): F1State? {
        val root = get("/f1") ?: return null
        return try {
            if (!root.optBoolean("live")) return F1State(live = false, next = parseNext(root))
            val list = root.getJSONArray("drivers")
            val drivers = (0 until list.length()).map { i ->
                val d = list.getJSONObject(i)
                F1Driver(
                    pos = d.optInt("pos"),
                    num = d.optString("num"),
                    tla = d.optString("tla"),
                    color = parseColor(d.optString("color")),
                    gap = d.optString("gap"),
                    interval = d.optString("interval"),
                    inPit = d.optBoolean("inPit"),
                    out = d.optBoolean("out"),
                    x = if (d.isNull("x")) null else d.getDouble("x").toFloat(),
                    y = if (d.isNull("y")) null else d.getDouble("y").toFloat(),
                    tyre = d.optString("tyre").takeIf { it.isNotEmpty() },
                    tyreLaps = d.optIntOrNull("tyreLaps"),
                    tyreNew = d.optBoolean("tyreNew", true)
                )
            }
            F1State(
                live = true,
                sessionKey = root.optLong("sessionKey"),
                event = root.optString("event"),
                session = root.optString("session"),
                race = root.optBoolean("race"),
                part = root.optIntOrNull("part"),
                status = root.optString("status"),
                lap = root.optIntOrNull("lap"),
                totalLaps = root.optIntOrNull("totalLaps"),
                remainingSec = root.optIntOrNull("remaining"),
                trackStatus = root.optString("trackStatus", "1"),
                trackId = if (root.isNull("trackId")) null else root.optString("trackId"),
                drivers = drivers
            )
        } catch (e: Exception) {
            null
        }
    }

    fun track(): F1Track? {
        val root = get("/f1/track") ?: return null
        return try {
            val pts = root.getJSONArray("points")
            val flat = FloatArray(pts.length() * 2)
            for (i in 0 until pts.length()) {
                val p = pts.getJSONArray(i)
                flat[i * 2] = p.getDouble(0).toFloat()
                flat[i * 2 + 1] = p.getDouble(1).toFloat()
            }
            F1Track(
                root.getString("id"),
                root.getDouble("w").toFloat(),
                root.getDouble("h").toFloat(),
                flat
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseNext(root: JSONObject): F1Next? {
        val next = root.optJSONObject("next") ?: return null
        val start = next.optLong("start")
        if (start <= 0) return null
        return F1Next(next.optString("event"), next.optString("session"), start * 1000)
    }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun parseColor(hex: String): Int =
        try {
            Color.parseColor("#$hex")
        } catch (e: Exception) {
            Color.GRAY
        }

    private fun get(path: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(Config.F1_SERVER.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 5_000
                if (Config.F1_TOKEN.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${Config.F1_TOKEN}")
                }
            }
            if (conn.responseCode != 200) return null
            JSONObject(conn.inputStream.bufferedReader().readText())
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
