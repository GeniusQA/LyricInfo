package com.lidesheng.lyricinfo.providers.spotify

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * Client for the Spotify "color/community lyrics" backend used by SpotifyPlus
 * (akashrchandran-style lyrics pipeline). The endpoint is a Cloudflare Worker
 * that performs the Spotify-side auth itself, so the client only needs the
 * Spotify track id — no SP_DC cookie and no self-hosted server are required.
 *
 * Default public endpoint (community-run, free, no self-hosting):
 *   https://spotifyplus-api.devon-shoutz.workers.dev/api/lyrics/{trackId}
 *
 * Response shape:
 *   { "Type": "Line" | "Syllable" | "Static",
 *     "Content": [ { "StartTime": <seconds>, "Text": "...", "Type": "Vocal" }, ... ],
 *     "Lines": [ ... ] }   // only for Type == "Static"
 *
 * We build standard LRC from each item's StartTime (seconds) + Text.
 *
 * Configuration: [DEFAULT_BASE_URL] is pre-filled with the public community
 * worker so it works out of the box with no self-hosting. If that endpoint ever
 * goes down or you prefer your own instance, override it at runtime without
 * rebuilding via the system property `lyricinfo.spotify.baseurl`
 * (e.g. `adb shell su -c setprop lyricinfo.spotify.baseurl "https://your/api/lyrics"`),
 * or just edit the constant below.
 */
internal object SpotifyLyricsApi {

    private const val TAG = "LyricInfo"

    // 默认公共 community worker：开箱即用、无需自托管。
    // 若该端点失效，可用系统属性覆盖（见类注释），或把此行改成你自己的实例地址。
    private const val DEFAULT_BASE_URL = "https://spotifyplus-api.devon-shoutz.workers.dev/api/lyrics"

    private fun baseUrl(): String {
        val prop = runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, "lyricinfo.spotify.baseurl", DEFAULT_BASE_URL) as String
        }.getOrNull().orEmpty()
        return prop.ifBlank { DEFAULT_BASE_URL }.trimEnd('/')
    }

    fun fetchLyrics(trackId: String): String? {
        val base = baseUrl()
        if (base.isEmpty()) {
            Log.e(
                TAG,
                "[Spotify] BASE_URL 未配置：把实例地址写入 SpotifyLyricsApi.DEFAULT_BASE_URL，" +
                    "或用系统属性 lyricinfo.spotify.baseurl 设置。" +
                    "可用公共端点(无需自托管): https://spotifyplus-api.devon-shoutz.workers.dev/api/lyrics"
            )
            return null
        }
        val url = "$base/$trackId"
        val raw = get(url) ?: return null
        return parse(raw)
    }

    private fun parse(raw: String): String? {
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val type = obj.optString("Type", "").takeIf { it.isNotBlank() } ?: return null

        val field = when (type) {
            "Static" -> "Lines"
            "Line", "Syllable" -> "Content"
            else -> {
                Log.w(TAG, "[Spotify] 不支持的歌词类型: $type")
                return null
            }
        }
        val arr = obj.optJSONArray(field) ?: return null
        if (arr.length() == 0) return null

        val out = StringBuilder()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            // 兼容嵌套：有时 Text 在子对象里
            val text = (item.optString("Text", "").takeIf { it.isNotBlank() }
                ?: item.optJSONObject("Text")?.optString("Text", ""))?.trimEnd('\n')
                .orEmpty()
            if (text.isBlank()) continue
            val startTime = item.optDouble("StartTime", -1.0)
            if (startTime >= 0) {
                out.append('[').append(secToLrc(startTime)).append(']')
            }
            out.append(text).append('\n')
        }
        val result = out.toString().trimEnd()
        if (result.isBlank()) return null
        Log.i(TAG, "[Spotify] Lyric loaded via color-lyrics backend (${arr.length()} lines, type=$type)")
        return result
    }

    private fun secToLrc(sec: Double): String {
        val totalCs = (sec * 100.0).toLong()
        val cs = (totalCs % 100).toInt()
        val totalSec = totalCs / 100
        val s = (totalSec % 60).toInt()
        val m = (totalSec / 60).toInt()
        return String.format("%02d:%02d.%02d", m, s, cs)
    }

    private fun get(url: String): String? {
        val connection = runCatching {
            URI.create(url).toURL().openConnection() as HttpURLConnection
        }.getOrNull() ?: return null
        return try {
            connection.apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "LyricInfo/1.0 (Spotify provider)")
                setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "[Spotify] lyrics backend GET -> HTTP $code")
                return null
            }
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "[Spotify] lyrics backend GET failed: ${e.message}")
            null
        } finally {
            connection.disconnect()
        }
    }
}
