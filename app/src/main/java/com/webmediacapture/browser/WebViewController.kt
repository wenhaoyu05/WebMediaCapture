package com.webmediacapture.browser

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

class WebViewController(val webView: WebView) {
    val userAgent: String get() = webView.settings.userAgentString.orEmpty()
    val url: String get() = webView.url.orEmpty()
    val canGoBack: Boolean get() = webView.canGoBack()
    val canGoForward: Boolean get() = webView.canGoForward()

    @SuppressLint("SetJavaScriptEnabled")
    fun configure() {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
    }

    fun load(raw: String) {
        val value = raw.trim()
        if (value.isBlank()) return
        val target = if (value.startsWith("http://") || value.startsWith("https://")) value else "https://$value"
        webView.loadUrl(target)
    }

    fun goBack(): Boolean = if (webView.canGoBack()) {
        webView.goBack()
        true
    } else false

    fun goForward(): Boolean = if (webView.canGoForward()) {
        webView.goForward()
        true
    } else false

    fun reload() = webView.reload()

    fun destroy() {
        webView.stopLoading()
        webView.destroy()
    }
}
