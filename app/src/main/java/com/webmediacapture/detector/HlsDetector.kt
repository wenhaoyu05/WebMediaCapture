package com.webmediacapture.detector

import com.webmediacapture.model.DetectionSource
import com.webmediacapture.model.MediaCandidate
import com.webmediacapture.model.MediaType
import com.webmediacapture.model.ObservedRequest
import com.webmediacapture.network.NetworkProbe
import com.webmediacapture.util.SafeLog

class HlsDetector(private val probe: NetworkProbe) {
    suspend fun inspect(request: ObservedRequest, source: DetectionSource = request.source): MediaCandidate? =
        inspect(request, source, 0)

    private suspend fun inspect(request: ObservedRequest, source: DetectionSource, depth: Int): MediaCandidate? {
        val text = runCatching { probe.fetchText(request.url, request.requestContext) }
            .onFailure { SafeLog.w("HLS", "Manifest parse failed ${request.url}: ${it.message}") }
            .getOrNull()
        if (text == null) {
            // #region agent log
            com.webmediacapture.util.AgentDebugLog.emit(
                "K3",
                "HlsDetector.kt:inspect",
                "hls-miss",
                mapOf("url" to com.webmediacapture.util.AgentDebugLog.safeUrl(request.url)),
            )
            // #endregion
            return if (request.url.substringBefore('?').contains(".m3u8", ignoreCase = true)) {
                candidate(request, MediaType.HLS, source, 75)
            } else {
                null
            }
        }
        if (depth < 3) {
            val embedded = HlsPlaylistLocator.embeddedManifestUrls(text).filter { it != request.url }
            if (!text.trimStart().startsWith("#EXTM3U")) {
                // #region agent log
                com.webmediacapture.util.AgentDebugLog.emit(
                    "L2",
                    "HlsDetector.kt:inspect",
                    "dl-body",
                    mapOf(
                        "url" to com.webmediacapture.util.AgentDebugLog.safeUrl(request.url),
                        "len" to text.length,
                        "m3u8" to text.contains("m3u8", true),
                        "acek" to text.contains("acek", true),
                        "embeds" to embedded.size,
                        "head" to text.trimStart().take(80).replace(Regex("[^\\x20-\\x7E]"), "."),
                    ),
                )
                // #endregion
            }
            for (url in embedded) {
                // #region agent log
                com.webmediacapture.util.AgentDebugLog.emit(
                    "K3",
                    "HlsDetector.kt:inspect",
                    "follow",
                    mapOf(
                        "from" to com.webmediacapture.util.AgentDebugLog.safeUrl(request.url),
                        "to" to com.webmediacapture.util.AgentDebugLog.safeUrl(url),
                    ),
                )
                // #endregion
                inspect(request.copy(url = url), source, depth + 1)?.let { return it }
            }
            if (embedded.isNotEmpty()) return null
        }
        val parsed = runCatching { HlsParser.parse(text, request.url) }
            .onFailure { SafeLog.w("HLS", "Invalid playlist ${request.url}: ${it.message}") }
            .getOrNull() ?: return null
        if (parsed.drmProtected) {
            SafeLog.d("HLS", "DRM playlist detected")
            return candidate(request, MediaType.DRM_PROTECTED, source, 95)
        }
        val best = parsed.variants.maxWithOrNull(compareBy<com.webmediacapture.model.MediaVariant> { it.height ?: 0 }.thenBy { it.bitrate ?: 0 })
        if (parsed.isMaster) SafeLog.d("HLS", "Master playlist detected variants=${parsed.variants.size}")
        else SafeLog.d("HLS", "Media playlist detected")
        val media = fetchMedia(parsed, text, request)
        if (media != null && media.segments.isNotEmpty() && media.segments.none { HlsSegmentFilter.isMedia(it.url) }) {
            SafeLog.d("HLS", "Playlist segments look like ads; still listing for capture")
            // #region agent log
            com.webmediacapture.util.AgentDebugLog.emit(
                "J1",
                "HlsDetector.kt:inspect",
                "decoy",
                mapOf("url" to com.webmediacapture.util.AgentDebugLog.safeUrl(request.url), "master" to parsed.isMaster, "segs" to media.segments.size),
            )
            // #endregion
        }
        val durationSec = media?.takeIf { it.ended }?.durationSec?.takeIf { it > 0 } ?: request.durationSec
        val result = candidate(request, MediaType.HLS, source, 95).copy(
            variants = parsed.variants,
            width = best?.width ?: request.width,
            height = best?.height ?: request.height,
            bitrate = best?.bitrate,
            codecs = best?.codecs,
            durationSec = durationSec,
        )
        // #region agent log
        com.webmediacapture.util.AgentDebugLog.emit(
            "D",
            "HlsDetector.kt:inspect",
            "hls-duration",
            mapOf(
                "url" to com.webmediacapture.util.AgentDebugLog.safeUrl(request.url),
                "master" to parsed.isMaster,
                "dur" to (durationSec ?: -1.0),
                "h" to (result.height ?: 0),
            ),
        )
        // #endregion
        return result
    }

    private suspend fun fetchMedia(parsed: HlsParser.Result, text: String, request: ObservedRequest): HlsParser.MediaPlaylist? {
        return runCatching {
            val (body, url) = if (parsed.isMaster) {
                val best = parsed.variants.maxWithOrNull(
                    compareBy<com.webmediacapture.model.MediaVariant> { it.height ?: 0 }.thenBy { it.bitrate ?: 0 },
                ) ?: return null
                probe.fetchText(best.url, request.requestContext) to best.url
            } else {
                text to request.url
            }
            HlsParser.parseMedia(body, url)
        }.getOrNull()
    }

    private fun candidate(request: ObservedRequest, type: MediaType, source: DetectionSource, confidence: Int) =
        MediaCandidate(
            pageSessionId = request.pageSessionId,
            pageUrl = request.pageUrl,
            mediaUrl = request.url,
            title = request.title,
            type = type,
            mimeType = request.mimeType,
            requestContext = request.requestContext,
            source = source,
            confidence = confidence,
            role = request.role,
            width = request.width,
            height = request.height,
            durationSec = request.durationSec,
            thumbnailUrl = request.thumbnailUrl,
        )
}
