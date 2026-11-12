package com.delgado.family

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Prefs {
    private fun p(ctx: Context): SharedPreferences = ctx.getSharedPreferences("delgado", Context.MODE_PRIVATE)

    fun key(ctx: Context): String = p(ctx).getString("key", "") ?: ""
    fun setKey(ctx: Context, v: String) = p(ctx).edit().putString("key", v).apply()

    /** "Dad" or "Mom" — whose phone this is. */
    fun owner(ctx: Context): String = p(ctx).getString("owner", "Dad") ?: "Dad"
    fun setOwner(ctx: Context, v: String) = p(ctx).edit().putString("owner", v).apply()

    /** Work arrive/leave tracking is on (only meant for Mom's phone). */
    fun workTracking(ctx: Context): Boolean = p(ctx).getBoolean("work", false)
    fun setWorkTracking(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("work", v).apply()

    fun coords(ctx: Context, place: String): Pair<Double, Double>? {
        val s = p(ctx).getString("loc-$place", null) ?: return null
        val parts = s.split(",")
        return if (parts.size == 2) parts[0].toDouble() to parts[1].toDouble() else null
    }
    fun setCoords(ctx: Context, place: String, lat: Double, lon: Double) =
        p(ctx).edit().putString("loc-$place", "$lat,$lon").apply()

    /** Last work event the app reported for a place ("arrive"/"leave") and when — stops duplicates. */
    fun lastWorkEvent(ctx: Context, place: String): String = p(ctx).getString("work-last-$place", "") ?: ""
    fun lastWorkTime(ctx: Context, place: String): Long = p(ctx).getLong("work-last-ts-$place", 0L)
    fun setLastWork(ctx: Context, place: String, event: String) =
        p(ctx).edit().putString("work-last-$place", event).putLong("work-last-ts-$place", System.currentTimeMillis()).apply()

    fun lastWidgetJson(ctx: Context): String? = p(ctx).getString("widget-json", null)
    fun setLastWidgetJson(ctx: Context, v: String) = p(ctx).edit().putString("widget-json", v).apply()
}

/** Talks to the family's Google Apps Script. Always call off the main thread. */
object Api {
    fun post(ctx: Context, body: JSONObject): JSONObject? {
        val key = Prefs.key(ctx)
        if (key.isEmpty()) return null
        body.put("key", key)
        return try {
            var conn = URL(Config.SCRIPT_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.setRequestProperty("Content-Type", "text/plain;charset=utf-8")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            var code = conn.responseCode
            var hops = 0
            // Apps Script answers a POST with a redirect to the result, which must be fetched with GET.
            while (code in 301..303 || code == 307 || code == 308) {
                val loc = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                conn = URL(loc).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = 15000
                conn.readTimeout = 25000
                code = conn.responseCode
                if (++hops > 5) break
            }
            if (code !in 200..299) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            JSONObject(text)
        } catch (e: Exception) {
            null
        }
    }
}
