package com.webmediacapture.util

import java.util.Locale

object ByteFormat {
    fun format(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L)
        return when {
            value < 1024 -> "$value B"
            value < 1024 * 1024 -> "%.2f KB".format(Locale.US, value / 1024.0)
            value < 1024L * 1024 * 1024 -> "%.2f MB".format(Locale.US, value / (1024.0 * 1024))
            else -> "%.2f GB".format(Locale.US, value / (1024.0 * 1024 * 1024))
        }
    }
}
