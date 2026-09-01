package com.webmediacapture.browser

import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebView

class BrowserWebChromeClient(
    private val onProgress: (Int) -> Unit,
    private val onTitle: (String?) -> Unit,
    private val onIcon: (Bitmap?) -> Unit,
) : WebChromeClient() {
    override fun onProgressChanged(view: WebView?, newProgress: Int) = onProgress(newProgress)
    override fun onReceivedTitle(view: WebView?, title: String?) = onTitle(title)
    override fun onReceivedIcon(view: WebView?, icon: Bitmap?) = onIcon(icon)
}
