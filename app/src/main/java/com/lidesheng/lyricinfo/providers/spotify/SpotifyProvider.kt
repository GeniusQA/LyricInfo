package com.lidesheng.lyricinfo.providers.spotify

import android.media.MediaMetadata
import android.os.Bundle
import android.util.Log
import com.lidesheng.lyricinfo.core.BaseLyricProvider
import com.lidesheng.lyricinfo.core.BaseLyricProvider.TrackMetadata
import com.lidesheng.lyricinfo.core.LyricResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Spotify provider (方案 B: spotify-lyrics-api).
 *
 * Spotify does not publish lyrics through its MediaSession. Instead of
 * reverse-engineering Spotify's internal lyrics API, this provider resolves the
 * current track's Spotify id from the playing MediaMetadata and asks a
 * self-hosted [SpotifyLyricsApi] (akashrchandran/spotify-lyrics-api, Musixmatch
 * backed) for the lyric.
 *
 * Requirements:
 *  1. Deploy your own spotify-lyrics-api instance with a Spotify `SP_DC` cookie
 *     and point [SpotifyLyricsApi.DEFAULT_BASE_URL] at it.
 *  2. Spotify's MediaMetadata must expose the track id (typically
 *     `spotify:track:<id>` or the bare 22-char id in METADATA_KEY_MEDIA_ID).
 *     If it does not, the id must be obtained by hooking Spotify internals —
 *     see [extractSpotifyTrackId] and the diagnostic log.
 *
 * Injection reuses the shared [BaseLyricProvider] path: the lyric is fetched off
 * the UI thread by [fetchLyric], and once available [onLyricAvailable]
 * re-injects it into the retained MediaSession and re-submits the
 * [MediaMetadata].
 */
class SpotifyProvider : BaseLyricProvider() {

    companion object {
        private const val TAG = "LyricInfo"
    }

    override val packageName = "com.spotify.music"

    private data class FrameworkTarget(
        val session: Any,
        val metadata: MediaMetadata,
        val identity: String,
        val mediaId: String
    )

    private val frameworkTarget = AtomicReference<FrameworkTarget?>(null)
    private val trackCache = ConcurrentHashMap<String, TrackMetadata>()
    private val refreshIdentity = ThreadLocal<String?>()

    override fun resolveTrackMetadata(bundle: Bundle): TrackMetadata? {
        val songName = bundle.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = bundle.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val album = bundle.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        if (songName.isBlank() || artist.isBlank()) return null

        val rawMediaId = bundle.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty()
        val spotifyTrackId = extractSpotifyTrackId(rawMediaId)
        // 诊断：确认 Spotify 是否通过 metadata 暴露 trackid。若为空说明需要从内部拿。
        Log.d(TAG, "[Spotify] raw mediaId='$rawMediaId' -> trackId='$spotifyTrackId'")

        val identity = spotifyTrackId ?: "$songName|$artist".hashCode().toString()
        val track = TrackMetadata(
            songName = songName,
            artist = artist,
            album = album,
            songId = identity,
            cacheKey = identity
        )
        trackCache[identity] = track
        return track
    }

    override fun fetchLyric(mediaId: String, title: String?, artist: String?): LyricResult? {
        val trackId = normalizeTrackId(mediaId) ?: run {
            Log.w(
                TAG,
                "[Spotify] 无法取得有效 Spotify trackid，跳过取词: mediaId='$mediaId' " +
                    "(确认 Spotify 是否在 metadata 暴露 trackid，或需 hook 内部获取)"
            )
            return null
        }
        val lyric = SpotifyLyricsApi.fetchLyrics(trackId) ?: return null
        return LyricResult(lyric = lyric, translation = null)
    }

    override fun onMediaSessionMetadataObserved(session: Any, metadata: MediaMetadata) {
        if (isRefreshReentry()) return
        frameworkTarget.set(null)
    }

    override fun onMediaSessionTrackResolved(
        session: Any,
        metadata: MediaMetadata,
        track: TrackMetadata
    ) {
        if (isRefreshReentry()) return
        frameworkTarget.set(
            FrameworkTarget(
                session = session,
                metadata = metadata,
                identity = track.cacheKey,
                mediaId = readMetadataMediaId(metadata)
            )
        )
    }

    override fun onLyricAvailable(
        track: TrackMetadata,
        result: LyricResult
    ) {
        refreshFrameworkSession(track.cacheKey)
    }

    override fun onDestroy() {
        frameworkTarget.set(null)
        trackCache.clear()
        refreshIdentity.remove()
        super.onDestroy()
    }

    private fun refreshFrameworkSession(identity: String) {
        val target = frameworkTarget.get() ?: return
        if (target.identity != identity) return
        try {
            val bundleField = target.metadata.javaClass.getDeclaredField("mBundle").apply {
                isAccessible = true
            }
            val bundle = bundleField.get(target.metadata) as Bundle
            if (!matchesRefreshTarget(bundle, target.mediaId)) return
            if (!writeCachedLyric(bundle, identity)) return

            val setMetadataMethod = target.session.javaClass.getDeclaredMethod(
                "setMetadata",
                MediaMetadata::class.java
            ).apply { isAccessible = true }
            withRefreshIdentity(identity) {
                setMetadataMethod.invoke(target.session, target.metadata)
            }
            Log.i(TAG, "[Spotify] ✓ Refreshed MediaSession: $identity")
        } catch (e: Exception) {
            Log.e(TAG, "[Spotify] ✗ Refresh MediaSession", e)
        }
    }

    private fun writeCachedLyric(bundle: Bundle, identity: String): Boolean {
        val track = trackCache[identity] ?: return false
        val result = lyricCache[identity] ?: return false
        return putLyricInfo(bundle, track, result, " (asyncSession)")
    }

    private fun matchesRefreshTarget(bundle: Bundle, targetMediaId: String): Boolean {
        val currentMediaId = readMediaId(bundle)
        if (targetMediaId.isNotBlank() &&
            currentMediaId.isNotBlank() &&
            targetMediaId != currentMediaId
        ) {
            Log.d(
                TAG,
                "[Spotify] Skip stale refresh: metadata mediaId changed " +
                    "$targetMediaId -> $currentMediaId"
            )
            return false
        }
        return true
    }

    private fun withRefreshIdentity(identity: String, action: () -> Unit) {
        refreshIdentity.set(identity)
        try {
            action()
        } finally {
            refreshIdentity.remove()
        }
    }

    private fun isRefreshReentry(): Boolean = refreshIdentity.get() != null

    /**
     * Extract a bare Spotify track id from whatever Spotify puts in
     * METADATA_KEY_MEDIA_ID. Accepts `spotify:track:<id>` (anywhere in the
     * string) or a standalone 22-char base62 id. Returns null when the id cannot
     * be recognised — in that case the id must be obtained from Spotify internals.
     */
    private fun extractSpotifyTrackId(raw: String): String? {
        if (raw.isBlank()) return null
        val candidate = if (raw.contains("spotify:track:")) {
            raw.substringAfter("spotify:track:")
        } else {
            raw
        }.trim()
        return if (isSpotifyTrackId(candidate)) candidate else null
    }

    private fun isSpotifyTrackId(value: String): Boolean {
        if (value.length != 22) return false
        return value.all { it.isLetterOrDigit() }
    }

    private fun normalizeTrackId(mediaId: String): String? {
        return if (isSpotifyTrackId(mediaId)) mediaId else null
    }

    private fun readMetadataMediaId(metadata: MediaMetadata): String {
        return runCatching {
            val bundleField = metadata.javaClass.getDeclaredField("mBundle").apply {
                isAccessible = true
            }
            readMediaId(bundleField.get(metadata) as Bundle)
        }.getOrDefault("")
    }

    private fun readMediaId(bundle: Bundle): String {
        val stringId = bundle.getCharSequence(MediaMetadata.METADATA_KEY_MEDIA_ID)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (stringId.isNotBlank()) return stringId

        return runCatching { bundle.getLong(MediaMetadata.METADATA_KEY_MEDIA_ID) }
            .getOrDefault(0L)
            .takeIf { it > 0L }
            ?.toString()
            .orEmpty()
    }
}
