package com.webmediacapture.detector

import java.net.URI

object PreviewMediaFilter {
    fun isPreviewUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host.orEmpty().lowercase()
        val path = uri.path.orEmpty().lowercase()
        if (path.split('/').any { it == "library" }) return true
        if (host.contains("growcdnssedge")) return true
        return false
    }
}
