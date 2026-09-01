package com.webmediacapture.network

import com.webmediacapture.browser.BrowserCookieProvider
import com.webmediacapture.model.RequestContext
import java.util.Locale

class CookieBridge(private val cookies: BrowserCookieProvider = BrowserCookieProvider()) {
    fun contextFor(
        url: String,
        pageUrl: String,
        userAgent: String?,
        requestHeaders: Map<String, String> = emptyMap(),
    ): RequestContext {
        val headers = requestHeaders.toMutableMap()
        headers.putIfAbsent("Referer", pageUrl)
        userAgent?.takeIf { it.isNotBlank() }?.let { headers.putIfAbsent("User-Agent", it) }
        headers.putIfAbsent("Accept", "*/*")
        headers.putIfAbsent("Accept-Language", Locale.getDefault().toLanguageTag())
        cookies.cookiesFor(url)?.let { headers["Cookie"] = it }
        return RequestContext(headers)
    }

    fun cookiesFor(url: String): String? = cookies.cookiesFor(url)
}
