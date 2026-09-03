package com.webmediacapture.browser

import java.net.URI

object WebViewNavigation {
    fun shouldLoad(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return true
        return host != "z.douyin.com" && !host.endsWith(".z.douyin.com")
    }
}
