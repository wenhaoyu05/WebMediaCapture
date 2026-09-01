package com.webmediacapture.download

import com.webmediacapture.model.RequestContext
import com.webmediacapture.network.HeaderManager
import com.webmediacapture.util.SafeLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.regex.Pattern

/**
 * Recovers the alternate (reachable) HLS sources for a VidHide/playrecord video.
 *
 * The embed page carries an obfuscated `links = {...}` object with up to three
 * sources: `hls4` (usually served from a CDN that is unreachable from this
 * client, e.g. tiktokcdn), `hls3` and `hls2` (rotating backup hosts that the
 * player falls back to). Only the backup hosts are reachable here, so when a
 * download fails against `hls4`, we resolve those alternates and retry.
 */
object PlayrecordFallbackResolver {

    private val LINKS: Pattern = Pattern.compile("\"hls([23])\":\"([^\"]+)\"")
    private val SHORT_LINKS: Pattern = Pattern.compile("links=\\{(.*?)\\}", Pattern.DOTALL)

    /**
     * Returns reachable candidate URLs (hls3 preferred, then hls2), absolute and
     * with their signed query strings intact, for the page identified by [context].
     * Empty when nothing usable can be found.
     */
    fun alternateSources(
        client: OkHttpClient,
        context: RequestContext,
        referer: String?,
    ): List<String> {
        val embedUrl = pickEmbedUrl(context, referer) ?: return emptyList()
        val fetchContext = cdnContext(context)
        SafeLog.w("HLS", "fallback resolving embed=$embedUrl referer=${fetchContext.value("Referer")}")
        val html = try {
            val builder = Request.Builder().url(embedUrl)
            HeaderManager.apply(builder, fetchContext)
            client.newCall(builder.build()).execute().use { r ->
                if (!r.isSuccessful) {
                    SafeLog.w("HLS", "fallback embed HTTP ${r.code} for $embedUrl")
                    return emptyList()
                }
                r.body?.string() ?: return emptyList()
            }
        } catch (e: Throwable) {
            SafeLog.w("HLS", "fallback embed fetch failed: ${e.message}")
            return emptyList()
        }

        val decoded = JsPackrDecoder.decode(html) ?: run {
            // Some pages inline `links={...}` without the packr wrapper.
            val m = SHORT_LINKS.matcher(html)
            if (m.find()) m.group(1) else null
        } ?: return emptyList()

        val ordered = mutableListOf<Pair<String, String>>() // (prefKey, url)
        val m = LINKS.matcher(decoded)
        while (m.find()) {
            val key = m.group(1) ?: continue
            val url = m.group(2) ?: continue
            ordered.add(key to url)
        }
        val result = ordered
            .sortedByDescending { it.first } // "3" before "2"
            .map { it.second }
            .filter { it.startsWith("https://", true) || it.startsWith("http://", true) }
            .distinct()
        SafeLog.w("HLS", "fallback decoded links -> ${result.size}: " + result.map { it.substringBefore("?") }.joinToString(" | "))
        return result
    }

    /**
     * Backup CDNs hotlink-check Referer against the embed host. Chromium media
     * GETs do not send Origin; replaying page cookies onto the CDN 403s.
     */
    fun cdnContext(context: RequestContext): RequestContext {
        val embed = pickEmbedUrl(context, null)
        val referer = when {
            embed == null -> PLAYRECORD_ORIGIN
            embed.substringBefore('?').contains(".m3u8", true) -> PLAYRECORD_ORIGIN
            else -> embed
        }
        val headers = context.headers.toMutableMap()
        headers.keys.filter {
            it.equals("Referer", true) || it.equals("Origin", true) || it.equals("Cookie", true)
        }.toList().forEach(headers::remove)
        headers["Referer"] = referer
        return RequestContext(headers)
    }

    private fun pickEmbedUrl(context: RequestContext, referer: String?): String? {
        val candidate = referer ?: context.value("Referer")
        if (candidate != null && (candidate.contains("playrecord.biz") || candidate.contains("vidhide"))) {
            return candidate
        }
        return null
    }

    private const val PLAYRECORD_HOST = "https://playrecord.biz"
    private const val PLAYRECORD_ORIGIN = "$PLAYRECORD_HOST/"
}
