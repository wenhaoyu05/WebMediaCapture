package com.webmediacapture.browser

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.webmediacapture.model.ObservedRequest
import com.webmediacapture.network.CookieBridge
import com.webmediacapture.util.SafeLog

class BrowserWebViewClient(
    private val observer: RequestObserver,
    private val cookies: CookieBridge,
    private val session: PageSession,
    private val userAgent: String,
    private val onPageChanged: (PageSession.State) -> Unit,
    private val onPageFinished: (String) -> Unit = {},
) : WebViewClient() {

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        if (!WebViewNavigation.shouldLoad(url)) return
        super.onPageStarted(view, url, favicon)
        onPageChanged(session.start(url))
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        return !WebViewNavigation.shouldLoad(request.url.toString())
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
        if (!request.isForMainFrame) return
        val url = request.url.toString()
        val blocked = !WebViewNavigation.shouldLoad(url) ||
            error.errorCode == ERROR_UNSUPPORTED_SCHEME
        if (blocked && view.canGoBack()) view.post { if (view.canGoBack()) view.goBack() }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        observe(request, session.current())
        return GatewayPeek.forward(request, cookies, userAgent, observer, session.current())
    }

    override fun onLoadResource(view: WebView, url: String) {
        super.onLoadResource(view, url)
        val state = session.current()
        observer.observe(
            ObservedRequest(
                url = url,
                method = "GET",
                requestContext = cookies.contextFor(url, state.url, userAgent),
                pageUrl = state.url,
                pageSessionId = state.id,
                title = state.title,
            ),
        )
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (!WebViewNavigation.shouldLoad(url)) return
        super.onPageFinished(view, url)
        onPageFinished(url)
    }

    private fun observe(request: WebResourceRequest, state: PageSession.State) {
        observer.observe(
            ObservedRequest(
                url = request.url.toString(),
                method = request.method,
                requestContext = cookies.contextFor(
                    request.url.toString(),
                    state.url,
                    userAgent,
                    request.requestHeaders,
                ),
                pageUrl = state.url,
                pageSessionId = state.id,
                title = state.title,
            ),
        )
        SafeLog.d("WEBVIEW", "Request detected ${request.url}")
    }
}

internal object GatewayPeek {
    private val client by lazy {
        com.webmediacapture.network.HttpClientProvider.client.newBuilder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    fun forward(
        request: WebResourceRequest,
        cookies: CookieBridge,
        userAgent: String,
        observer: RequestObserver,
        state: PageSession.State,
    ): WebResourceResponse? {
        val url = request.url.toString()
        if (!request.method.equals("GET", true)) return null
        if (!com.webmediacapture.detector.UrlPatternDetector.isHlsGateway(url)) return null
        return runCatching { execute(url, request, cookies, userAgent, observer, state) }.getOrNull()
    }

    private fun execute(
        url: String,
        request: WebResourceRequest,
        cookies: CookieBridge,
        userAgent: String,
        observer: RequestObserver,
        state: PageSession.State,
    ): WebResourceResponse {
        val context = cookies.contextFor(url, state.url, userAgent, request.requestHeaders)
        val call = okhttp3.Request.Builder().url(url).get()
        com.webmediacapture.network.HeaderManager.apply(call, context)
        client.newCall(call.build()).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            val text = bytes.toString(Charsets.UTF_8)
            val manifests = com.webmediacapture.detector.HlsPlaylistLocator.embeddedManifestUrls(text)
            // #region agent log
            com.webmediacapture.util.AgentDebugLog.emit(
                "L2",
                "GatewayPeek.kt:forward",
                "dl-body",
                mapOf(
                    "len" to text.length,
                    "embeds" to manifests.size,
                    "m3u8" to text.contains("m3u8", true),
                    "acek" to text.contains("acek", true),
                    "head" to text.trimStart().take(80).replace(Regex("[^\\x20-\\x7E]"), "."),
                ),
            )
            // #endregion
            manifests.forEach { manifest ->
                observer.observe(
                    ObservedRequest(
                        url = manifest,
                        method = "GET",
                        mimeType = "application/vnd.apple.mpegurl",
                        requestContext = cookies.contextFor(manifest, state.url, userAgent, request.requestHeaders),
                        pageUrl = state.url,
                        pageSessionId = state.id,
                        title = state.title,
                    ),
                )
            }
            val contentType = response.header("Content-Type") ?: "text/plain; charset=utf-8"
            val mime = contentType.substringBefore(';').trim().ifBlank { "text/plain" }
            val charset = contentType.substringAfter("charset=", "utf-8").substringBefore(';').trim()
            val headers = response.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
                .filterKeys { key ->
                    !key.equals("content-encoding", true) &&
                        !key.equals("content-length", true) &&
                        !key.equals("transfer-encoding", true)
                }
            return WebResourceResponse(
                mime,
                charset,
                response.code,
                response.message.ifBlank { "OK" },
                headers,
                bytes.inputStream(),
            )
        }
    }
}
