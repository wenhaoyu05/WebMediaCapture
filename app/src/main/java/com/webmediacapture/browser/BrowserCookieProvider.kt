package com.webmediacapture.browser

import android.webkit.CookieManager

class BrowserCookieProvider(private val manager: CookieManager = CookieManager.getInstance()) {
    fun cookiesFor(url: String): String? = manager.getCookie(url)?.takeIf(String::isNotBlank)
}
