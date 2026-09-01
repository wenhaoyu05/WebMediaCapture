package com.webmediacapture.detector

import com.webmediacapture.model.MediaType
import java.net.URI

object UrlPatternDetector {
    private val directExtensions = setOf("mp4", "m4v", "webm", "mov", "mkv")
    private val segmentExtensions = setOf("ts", "m4s")
    private val staticExtensions = setOf(
        "js", "css", "png", "jpg", "jpeg", "gif", "webp", "svg", "ico", "bmp",
        "woff", "woff2", "ttf", "otf", "eot", "html", "htm", "map",
    )
    private val mediaPathHints = listOf(
        "m3u8", "mpd", "manifest", "playlist", "videoplayback", "googlevideo", "stream",
    )

    fun classify(url: String): MediaType? {
        if (url.startsWith("blob:", true)) return null
        val uri = runCatching { URI(url) }.getOrNull()
        val path = uri?.path.orEmpty().lowercase()
        val extension = path.substringAfterLast('.', "")
        return when {
            extension in staticExtensions -> null
            extension in directExtensions -> MediaType.DIRECT
            extension == "m3u8" || (extension == "txt" && isTxtHls(path)) ||
                queryHints(url, "m3u8", "hls", "hls4") || isHlsGateway(url) -> MediaType.HLS
            extension == "mpd" || queryHints(url, "mpd", "dash") -> MediaType.DASH
            extension in segmentExtensions -> null
            queryHints(url, "mp4", "webm", "video") -> MediaType.DIRECT
            else -> null
        }
    }

    fun isSegment(url: String): Boolean {
        val path = runCatching { URI(url).path.lowercase() }.getOrDefault("")
        return path.substringAfterLast('.', "") in segmentExtensions
    }

    private fun isTxtHls(path: String): Boolean =
        path.endsWith("master.txt") || "/hls" in path

    fun isHlsGateway(url: String): Boolean {
        val lower = url.lowercase()
        return "/dl" in lower && ("hls4" in lower || Regex("[?&]hls(=|&|$)").containsMatchIn(lower))
    }

    fun isStaticAsset(url: String): Boolean {
        val path = runCatching { URI(url).path.lowercase() }.getOrDefault("")
        val extension = path.substringAfterLast('.', "")
        return extension in staticExtensions
    }

    fun hasMediaHint(url: String, acceptHeader: String? = null): Boolean {
        if (classify(url) != null) return true
        val accept = acceptHeader.orEmpty().lowercase()
        if (accept.contains("video/") || accept.contains("audio/") ||
            accept.contains("mpegurl") || accept.contains("dash+xml")
        ) {
            return true
        }
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val path = uri.path.orEmpty().lowercase()
        val query = uri.rawQuery.orEmpty().lowercase()
        return mediaPathHints.any { it in path || it in query }
    }

    internal fun queryHints(url: String, vararg hints: String): Boolean {
        val query = runCatching { URI(url).rawQuery }.getOrNull()?.lowercase() ?: return false
        return hints.any { hint ->
            Regex("(?:^|[&=_%.-])${Regex.escape(hint)}(?:$|[&=_%.-])").containsMatchIn(query)
        }
    }
}
