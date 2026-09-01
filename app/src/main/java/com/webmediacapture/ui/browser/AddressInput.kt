package com.webmediacapture.ui.browser

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object AddressInput {
    fun destination(raw: String, searchTemplate: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (value.startsWith("http://", true) || value.startsWith("https://", true)) return value
        val looksLikeHost = value.contains('.') && !value.contains(' ')
        return if (looksLikeHost) "https://$value" else searchTemplate.format(URLEncoder.encode(value, StandardCharsets.UTF_8.name()))
    }
}
