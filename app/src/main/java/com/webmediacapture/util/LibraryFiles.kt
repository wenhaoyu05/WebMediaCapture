package com.webmediacapture.util

import java.util.Locale

object LibraryFiles {
    fun mime(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            "flac" -> "audio/flac"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "m4v", "mp4" -> "video/mp4"
            else -> "video/mp4"
        }
    }

    fun isAudio(path: String) = mime(path).startsWith("audio/")

    fun duration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
        } else {
            "%d:%02d".format(Locale.US, minutes, seconds)
        }
    }

    fun displayName(path: String, title: String?): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        val stem = title?.let(MediaTitles::sanitize)?.ifBlank { null }
            ?: path.substringAfterLast('/').substringBeforeLast('.')
        return if (ext.isEmpty()) stem else "$stem.$ext"
    }
}
