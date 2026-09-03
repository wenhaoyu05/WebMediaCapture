package com.webmediacapture.detector

import com.webmediacapture.model.MediaCandidate
import com.webmediacapture.model.MediaRole
import com.webmediacapture.model.MediaType
import com.webmediacapture.util.SafeLog
import java.net.URI

class CandidateDeduplicator {
    private val trackingKeys = setOf("utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "fbclid", "gclid")

    fun merge(existing: List<MediaCandidate>, incoming: MediaCandidate): List<MediaCandidate> {
        if (MediaClassifier.isSegment(incoming.mediaUrl, incoming.mimeType)) return existing
        val index = existing.indexOfFirst { sameMedia(it, incoming) }
        if (index < 0) return (existing + incoming).sortedWith(candidateOrder)
        val current = existing[index]
        val preferred = prefer(current, incoming)
        val merged = preferred.copy(
            variants = (current.variants + incoming.variants).distinctBy { fingerprint(it.url) },
            requestContext = bestRequestContext(current.requestContext, incoming.requestContext),
            estimatedSize = incoming.estimatedSize ?: current.estimatedSize,
            mimeType = incoming.mimeType ?: current.mimeType,
            width = preferred.width ?: current.width ?: incoming.width,
            height = preferred.height ?: current.height ?: incoming.height,
            bitrate = preferred.bitrate ?: current.bitrate ?: incoming.bitrate,
            codecs = preferred.codecs ?: current.codecs ?: incoming.codecs,
            durationSec = listOfNotNull(preferred.durationSec, current.durationSec, incoming.durationSec).maxOrNull(),
            title = com.webmediacapture.util.MediaTitles.prefer(current.title, incoming.title),
            thumbnailUrl = preferred.thumbnailUrl ?: current.thumbnailUrl ?: incoming.thumbnailUrl,
            role = when {
                current.role == MediaRole.MAIN || incoming.role == MediaRole.MAIN -> MediaRole.MAIN
                incoming.role != MediaRole.UNKNOWN -> incoming.role
                else -> current.role
            },
        )
        SafeLog.d("DEDUP", "Merged duplicate candidate")
        return existing.toMutableList().apply { this[index] = merged }.sortedWith(candidateOrder)
    }

    fun keepPrimary(existing: List<MediaCandidate>): List<MediaCandidate> =
        existing.filter {
            it.role != MediaRole.AD && it.role != MediaRole.OVERLAY &&
                !AdMediaFilter.isAdUrl(it.mediaUrl) && !PreviewMediaFilter.isPreviewUrl(it.mediaUrl)
        }.sortedWith(candidateOrder)

    internal fun fingerprint(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url.substringBefore('#')
        val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.filterNot {
            it.substringBefore('=').lowercase() in trackingKeys
        }.sorted().joinToString("&")
        return buildString {
            append(uri.scheme?.lowercase()).append("://").append(uri.host?.lowercase())
            if (uri.port != -1) append(':').append(uri.port)
            append(uri.path.replace(Regex("/+"), "/"))
            if (query.isNotEmpty()) append('?').append(query)
        }
    }

    private fun sameMedia(a: MediaCandidate, b: MediaCandidate): Boolean {
        if (a.pageSessionId != b.pageSessionId) return false
        if (fingerprint(a.mediaUrl) == fingerprint(b.mediaUrl)) return true
        val aUrls = urlsOf(a).map(::fingerprint).toSet()
        val bUrls = urlsOf(b).map(::fingerprint).toSet()
        if (aUrls.intersect(bUrls).isNotEmpty()) return true
        if (a.type == MediaType.HLS && b.type == MediaType.HLS) {
            val aUri = runCatching { URI(a.mediaUrl) }.getOrNull()
            val bUri = runCatching { URI(b.mediaUrl) }.getOrNull()
            return aUri?.host.equals(bUri?.host, true) &&
                aUri?.path?.substringBeforeLast('/') == bUri?.path?.substringBeforeLast('/')
        }
        return false
    }

    private fun urlsOf(candidate: MediaCandidate): List<String> =
        listOf(candidate.mediaUrl) + candidate.variants.map { it.url }

    private fun prefer(current: MediaCandidate, incoming: MediaCandidate): MediaCandidate = when {
        incoming.variants.size > current.variants.size -> incoming
        current.variants.size > incoming.variants.size -> current
        incoming.confidence > current.confidence -> incoming
        else -> current
    }

    private fun bestRequestContext(
        current: com.webmediacapture.model.RequestContext,
        incoming: com.webmediacapture.model.RequestContext,
    ) = if (contextScore(incoming) > contextScore(current)) incoming else current

    private fun contextScore(context: com.webmediacapture.model.RequestContext): Int = context.headers.size +
        (if (context.value("Cookie") != null) 8 else 0) +
        (if (context.value("Authorization") != null) 8 else 0)

    private val candidateOrder = compareByDescending<MediaCandidate> { it.durationSec ?: 0.0 }
        .thenByDescending { it.height ?: it.variants.maxOfOrNull { variant -> variant.height ?: 0 } ?: 0 }
        .thenByDescending { it.bitrate ?: 0 }
        .thenByDescending { it.variants.size }
        .thenByDescending { it.confidence }
}
