package com.webmediacapture.detector

import com.webmediacapture.model.MediaType

object MimeTypeDetector {
    fun classify(mimeType: String?): MediaType? {
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return when {
            mime in setOf("application/vnd.apple.mpegurl", "application/x-mpegurl") -> MediaType.HLS
            mime == "application/dash+xml" -> MediaType.DASH
            mime.startsWith("video/") && mime != "video/mp2t" -> MediaType.DIRECT
            mime.startsWith("audio/") -> MediaType.AUDIO
            else -> null
        }
    }

    fun isMpegTs(mimeType: String?): Boolean =
        mimeType?.substringBefore(';')?.trim()?.startsWith("video/mp2t", true) == true

    fun isOctetStream(mimeType: String?): Boolean =
        mimeType?.substringBefore(';')?.trim().equals("application/octet-stream", true)
}
