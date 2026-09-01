package com.webmediacapture.detector

import com.webmediacapture.model.DetectionSource
import com.webmediacapture.model.MediaCandidate
import com.webmediacapture.model.MediaType
import com.webmediacapture.model.ObservedRequest
import com.webmediacapture.network.NetworkProbe
import com.webmediacapture.util.SafeLog

class DashDetector(private val probe: NetworkProbe) {
    suspend fun inspect(request: ObservedRequest, source: DetectionSource = request.source): MediaCandidate? {
        val text = runCatching { probe.fetchText(request.url, request.requestContext) }
            .onFailure { SafeLog.w("DASH", "Manifest parse failed ${request.url}: ${it.message}") }
            .getOrNull() ?: return candidate(request, MediaType.DASH, source, 75)
        val parsed = runCatching { DashParser.parse(text, request.url) }
            .onFailure { SafeLog.w("DASH", "Invalid MPD ${request.url}: ${it.message}") }
            .getOrNull()
        if (parsed == null) return candidate(request, MediaType.DASH, source, 75)
        if (parsed.drmProtected) {
            SafeLog.d("DASH", "DRM MPD detected")
            return candidate(request, MediaType.DRM_PROTECTED, source, 95)
        }
        val variants = parsed.variants()
        val best = variants.maxWithOrNull(compareBy<com.webmediacapture.model.MediaVariant> { it.height ?: 0 }.thenBy { it.bitrate ?: 0 })
        SafeLog.d("DASH", "MPD detected representations=${parsed.representations.size}")
        return candidate(request, MediaType.DASH, source, 95).copy(
            variants = variants,
            width = best?.width ?: request.width,
            height = best?.height ?: request.height,
            bitrate = best?.bitrate,
            codecs = best?.codecs,
        )
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
        )
}
