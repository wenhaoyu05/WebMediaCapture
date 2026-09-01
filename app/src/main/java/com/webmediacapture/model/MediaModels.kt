package com.webmediacapture.model

import java.util.UUID

enum class MediaType { DIRECT, HLS, DASH, AUDIO, UNKNOWN, DRM_PROTECTED }

enum class DetectionSource { WEBVIEW_NETWORK, HTTP_PROBE, YT_DLP, DOM }

enum class MediaRole { MAIN, AD, OVERLAY, UNKNOWN }

data class RequestContext(
    val headers: Map<String, String> = emptyMap(),
) {
    fun value(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value

    fun downloadHeaders(): Map<String, String> = headers.filterKeys {
        it.equals("Cookie", true) || it.equals("User-Agent", true) ||
            it.equals("Referer", true) || it.equals("Origin", true) ||
            it.equals("Accept", true) || it.equals("Accept-Language", true) ||
            it.equals("Authorization", true)
    }
}

data class MediaVariant(
    val url: String,
    val formatId: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
    val codecs: String? = null,
    val audioGroup: String? = null,
)

data class MediaCandidate(
    val id: String = UUID.randomUUID().toString(),
    val pageSessionId: String,
    val pageUrl: String,
    val mediaUrl: String,
    val title: String? = null,
    val type: MediaType,
    val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
    val estimatedSize: Long? = null,
    val codecs: String? = null,
    val requestContext: RequestContext = RequestContext(),
    val source: DetectionSource,
    val variants: List<MediaVariant> = emptyList(),
    val selectedFormatId: String? = null,
    val detectedAt: Long = System.currentTimeMillis(),
    val confidence: Int = 50,
    val role: MediaRole = MediaRole.UNKNOWN,
    val durationSec: Double? = null,
)

data class ObservedRequest(
    val url: String,
    val method: String,
    val mimeType: String? = null,
    val requestContext: RequestContext,
    val pageUrl: String,
    val pageSessionId: String,
    val source: DetectionSource = DetectionSource.WEBVIEW_NETWORK,
    val timestamp: Long = System.currentTimeMillis(),
    val role: MediaRole = MediaRole.UNKNOWN,
    val width: Int? = null,
    val height: Int? = null,
    val durationSec: Double? = null,
    val title: String? = null,
)
