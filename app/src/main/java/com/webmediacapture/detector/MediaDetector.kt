package com.webmediacapture.detector

import com.webmediacapture.model.DetectionSource
import com.webmediacapture.model.MediaCandidate
import com.webmediacapture.model.MediaRole
import com.webmediacapture.model.MediaType
import com.webmediacapture.model.ObservedRequest
import com.webmediacapture.network.NetworkProbe
import com.webmediacapture.util.SafeLog
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.LinkedHashMap

class MediaDetector(probe: NetworkProbe) {
    private val probeSlots = Semaphore(MAX_PROBE_CONCURRENCY)
    private val headerProbe = HeaderProbeDetector(probe)
    private val hlsDetector = HlsDetector(probe)
    private val dashDetector = DashDetector(probe)
    private val recentlySeen = object : LinkedHashMap<String, Long>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > 512
    }

    suspend fun detect(request: ObservedRequest): MediaCandidate? {
        if (request.url.startsWith("blob:", true)) {
            SafeLog.d("MEDIA", "Ignored blob URL")
            return null
        }
        if (request.role == MediaRole.AD || request.role == MediaRole.OVERLAY ||
            AdMediaFilter.isAdUrl(request.url) || AdMediaFilter.isAdReferer(request.requestContext.value("Referer")) ||
            PreviewMediaFilter.isPreviewUrl(request.url)
        ) {
            SafeLog.d("MEDIA", "Ignored ad or overlay media")
            return null
        }
        if (MediaClassifier.isSegment(request.url, request.mimeType)) {
            val playlist = HlsPlaylistLocator.fromSegmentUrl(request.url) ?: return null
            if (!markSeen("${request.pageSessionId}|$playlist")) return null
            // #region agent log
            com.webmediacapture.util.AgentDebugLog.emit(
                "K2",
                "MediaDetector.kt:detect",
                "seg-playlist",
                mapOf("url" to com.webmediacapture.util.AgentDebugLog.safeUrl(playlist)),
            )
            // #endregion
            return probeSlots.withPermit {
                inspectKnown(request.copy(url = playlist), MediaType.HLS, request.source)
            }
        }
        if (!markSeen("${request.pageSessionId}|${request.url}") && request.durationSec == null) return null
        val urlType = MediaClassifier.fromUrl(request.url)
        if (UrlPatternDetector.isHlsGateway(request.url)) {
            // #region agent log
            com.webmediacapture.util.AgentDebugLog.emit(
                "L1",
                "MediaDetector.kt:detect",
                "dl-detect",
                mapOf(
                    "type" to (urlType?.name ?: "null"),
                    "method" to request.method,
                    "mime" to (request.mimeType ?: ""),
                ),
            )
            // #endregion
        }
        if (urlType != null) {
            SafeLog.d("MEDIA", "Possible ${urlType.name} candidate from URL")
            return if (urlType == MediaType.HLS || urlType == MediaType.DASH) {
                probeSlots.withPermit { inspectKnown(request, urlType, request.source) }
            } else inspectKnown(request, urlType, request.source)
        }
        if (UrlPatternDetector.isHlsGateway(request.url)) {
            return probeSlots.withPermit { inspectKnown(request, MediaType.HLS, request.source) }
        }
        val mimeType = MediaClassifier.fromMime(request.mimeType)
        if (mimeType != null) {
            SafeLog.d("MEDIA", "Possible ${mimeType.name} candidate from Content-Type")
            return if (mimeType == MediaType.HLS || mimeType == MediaType.DASH) {
                probeSlots.withPermit { inspectKnown(request, mimeType, request.source) }
            } else inspectKnown(request, mimeType, request.source)
        }
        val accept = request.requestContext.value("Accept")
        if (!MediaClassifier.needsProbe(request.url, request.mimeType, accept)) return null
        return probeSlots.withPermit {
            headerProbe.classify(request)?.let { probed ->
                inspectKnown(request.copy(url = probed.finalUrl, mimeType = probed.mimeType), probed.type, DetectionSource.HTTP_PROBE)
                    ?.copy(estimatedSize = probed.contentLength, source = DetectionSource.HTTP_PROBE)
            }
        }
    }

    private suspend fun inspectKnown(request: ObservedRequest, type: MediaType, source: DetectionSource): MediaCandidate? {
        val result = when (type) {
            MediaType.HLS -> hlsDetector.inspect(request, source)
            MediaType.DASH -> dashDetector.inspect(request, source)
            else -> MediaCandidate(
                pageSessionId = request.pageSessionId,
                pageUrl = request.pageUrl,
                mediaUrl = request.url,
                title = request.title,
                type = type,
                mimeType = request.mimeType,
                width = request.width,
                height = request.height,
                durationSec = request.durationSec,
                requestContext = request.requestContext,
                source = source,
                confidence = if (source == DetectionSource.HTTP_PROBE) 90 else 75,
                role = request.role,
                thumbnailUrl = request.thumbnailUrl,
            ).also { SafeLog.d("MEDIA", "Candidate created type=${type.name}") }
        }
        return result
    }

    @Synchronized
    private fun markSeen(key: String): Boolean {
        val now = System.currentTimeMillis()
        val previous = recentlySeen[key]
        if (previous != null && now - previous < 5 * 60_000) return false
        recentlySeen[key] = now
        return true
    }

    companion object {
        const val MAX_PROBE_CONCURRENCY = 4
    }
}
