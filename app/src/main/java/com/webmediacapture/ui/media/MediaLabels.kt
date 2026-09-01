package com.webmediacapture.ui.media

import com.webmediacapture.model.MediaCandidate
import com.webmediacapture.model.MediaType
import com.webmediacapture.model.MediaVariant

object MediaLabels {
    fun quality(candidate: MediaCandidate): String {
        val height = candidate.height ?: candidate.variants.maxOfOrNull { it.height ?: 0 }?.takeIf { it > 0 }
        return height?.let { "${it}p" } ?: fallbackType(candidate.type)
    }

    fun summary(candidate: MediaCandidate): String {
        val parts = mutableListOf(quality(candidate), candidate.type.name)
        codecLabel(candidate.codecs ?: candidate.variants.firstOrNull()?.codecs)?.let { parts += it }
        candidate.estimatedSize?.takeIf { it > 0 }?.let { parts += formatSize(it) }
        candidate.durationSec?.takeIf { it.isFinite() && it > 0 }?.let { parts += formatDuration(it) }
        candidate.width?.let { width ->
            candidate.height?.let { height -> parts += "${width}×${height}" }
        }
        if (candidate.variants.isNotEmpty()) parts += "${candidate.variants.size} variants"
        return parts.joinToString(" · ")
    }

    fun variantLabel(variant: MediaVariant, type: MediaType): String {
        val quality = variant.height?.let { "${it}p" } ?: type.name
        val codec = codecLabel(variant.codecs) ?: type.name
        return "$quality · $codec"
    }

    fun codecLabel(codecs: String?): String? {
        if (codecs.isNullOrBlank()) return null
        val tokens = codecs.lowercase()
        val video = when {
            "avc" in tokens || "avc1" in tokens -> "AVC"
            "hvc" in tokens || "hev1" in tokens || "hevc" in tokens -> "HEVC"
            "vp9" in tokens || "vp09" in tokens -> "VP9"
            "av01" in tokens -> "AV1"
            else -> null
        }
        val audio = when {
            "mp4a" in tokens || "aac" in tokens -> "AAC"
            "opus" in tokens -> "Opus"
            "mp3" in tokens -> "MP3"
            else -> null
        }
        return listOfNotNull(video, audio).joinToString("/").ifBlank { codecs }
    }

    private fun fallbackType(type: MediaType) = when (type) {
        MediaType.HLS -> "HLS"
        MediaType.DASH -> "DASH"
        MediaType.AUDIO -> "AUDIO"
        else -> "MEDIA"
    }

    private fun formatDuration(seconds: Double): String {
        val total = seconds.toInt().coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val remain = total % 60
        return when {
            hours > 0 -> "${hours}h${minutes}m"
            minutes > 0 -> "${minutes}m${remain}s"
            else -> "${remain}s"
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024 * 1024) return "${bytes / 1024}KB"
        return "%.0fMB".format(bytes / (1024.0 * 1024.0))
    }
}
