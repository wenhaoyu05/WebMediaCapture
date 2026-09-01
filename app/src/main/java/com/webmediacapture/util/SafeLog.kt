package com.webmediacapture.util

import android.util.Log

object SafeLog {
    private val secretQuery = Regex("(?i)(token|signature|sig|auth|key|expires)=([^&\\s]+)")

    fun d(tag: String, message: String) {
        if (com.webmediacapture.BuildConfig.DEBUG) Log.d(tag, redact(message))
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        Log.w(tag, redact(message), error)
    }

    internal fun redact(value: String): String = value
        .replace(Regex("(?i)(cookie|authorization)\\s*[:=]\\s*[^,;\\n]+"), "$1=[REDACTED]")
        .replace(secretQuery, "$1=[REDACTED]")
}
