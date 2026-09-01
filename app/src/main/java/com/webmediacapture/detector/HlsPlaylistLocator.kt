package com.webmediacapture.detector

import java.net.URI

object HlsPlaylistLocator {
    fun fromSegmentUrl(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val path = uri.path ?: return null
        val lower = path.lowercase()
        if (!lower.endsWith(".ts") && !lower.endsWith(".m4s")) return null
        val dir = path.substringBeforeLast('/')
        if (dir.isBlank()) return null
        return URI(uri.scheme, uri.authority, "$dir/master.m3u8", null, null).toString()
    }

    fun embeddedManifestUrls(body: String): List<String> {
        if (body.trimStart().startsWith("#EXTM3U")) return emptyList()
        val unescaped = body.replace("\\/", "/")
        val found = LinkedHashSet<String>()
        fun add(raw: String) {
            val url = normalize(raw) ?: return
            if (looksLikeManifest(url)) found += url
        }
        Regex("https?://[^\\s\"'<>\\\\]+\\.m3u8[^\\s\"'<>\\\\]*", RegexOption.IGNORE_CASE)
            .findAll(unescaped)
            .forEach { add(it.value) }
        Regex("(?<![a-zA-Z0-9])//[^\\s\"'<>\\\\]+\\.m3u8[^\\s\"'<>\\\\]*", RegexOption.IGNORE_CASE)
            .findAll(unescaped)
            .forEach { add("https:${it.value}") }
        Regex(
            """"(?:file|url|src|source|hls|link|manifest|playlist)"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE,
        ).findAll(unescaped).forEach { add(it.groupValues[1]) }
        val trimmed = unescaped.trim().trim('"')
        if (trimmed.startsWith("http") || trimmed.startsWith("//")) add(trimmed)
        return found.toList().sortedBy { if (it.contains("playrecord", ignoreCase = true)) 1 else 0 }
    }

    private fun normalize(raw: String): String? {
        val value = raw.trim().trimEnd(',', ';', ')', ']').trim('"')
        if (value.isEmpty()) return null
        return when {
            value.startsWith("//") -> "https:$value"
            else -> value
        }
    }

    private fun looksLikeManifest(url: String): Boolean {
        val lower = url.lowercase()
        return ".m3u8" in lower || ".mpd" in lower || "/hls" in lower || "urlset" in lower
    }
}
