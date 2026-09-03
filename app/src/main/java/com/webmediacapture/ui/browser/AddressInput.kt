package com.webmediacapture.ui.browser

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object AddressInput {
    private val HTTP_URL = Regex("""https?://[^\s<>"'，。、）)】\]]+""", RegexOption.IGNORE_CASE)

    fun firstHttpUrl(raw: String): String? =
        HTTP_URL.find(raw.trim())?.value?.trimEnd(',', ';')

    fun destination(raw: String, searchTemplate: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        firstHttpUrl(value)?.let { return it }
        val looksLikeHost = value.contains('.') && !value.contains(' ')
        return if (looksLikeHost) "https://$value" else searchTemplate.format(URLEncoder.encode(value, StandardCharsets.UTF_8.name()))
    }
}
