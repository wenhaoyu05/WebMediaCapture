package com.webmediacapture.browser

import android.webkit.JavascriptInterface
import com.webmediacapture.detector.AdMediaFilter
import com.webmediacapture.model.DetectionSource
import com.webmediacapture.model.ObservedRequest
import com.webmediacapture.model.RequestContext
import java.net.URI

/**
 * A deliberately narrow JavaScript bridge for page media metadata. It never exposes
 * cookies, credentials, filesystem paths, or arbitrary native capabilities to a page.
 */
class MediaProbeBridge(
    private val observer: RequestObserver,
    private val session: PageSession,
    private val userAgent: () -> String,
    private val onPoster: (String) -> Unit = {},
) {
    @JavascriptInterface
    fun report(
        rawUrl: String?,
        rawMimeType: String?,
        rawRole: String?,
        rawWidth: String?,
        rawHeight: String?,
        rawDuration: String?,
        rawTitle: String?,
        rawPoster: String?,
    ) {
        val url = rawUrl?.trim()?.takeIf(::isHttpUrl) ?: return
        val page = session.current()
        if (!isHttpUrl(page.url)) return
        val durationSec = rawDuration?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
        val poster = rawPoster?.trim()?.takeIf(::isHttpUrl)
        poster?.let(onPoster)
        val headers = buildMap {
            put("Referer", page.url)
            userAgent().takeIf(String::isNotBlank)?.let { put("User-Agent", it) }
        }
        observer.observe(
            ObservedRequest(
                url = url,
                method = "GET",
                mimeType = rawMimeType?.trim()?.takeIf(String::isNotBlank),
                requestContext = RequestContext(headers),
                pageUrl = page.url,
                pageSessionId = page.id,
                source = DetectionSource.DOM,
                role = AdMediaFilter.parseRole(rawRole),
                width = rawWidth?.toIntOrNull(),
                height = rawHeight?.toIntOrNull(),
                durationSec = durationSec,
                title = com.webmediacapture.util.MediaTitles.prefer(page.title, rawTitle),
                thumbnailUrl = poster,
            ),
        )
        // #region agent log
        com.webmediacapture.util.AgentDebugLog.emit(
            "C",
            "MediaProbeBridge.kt:report",
            "dom",
            mapOf(
                "url" to com.webmediacapture.util.AgentDebugLog.safeUrl(url),
                "role" to (rawRole ?: ""),
                "dur" to (durationSec ?: -1.0),
                "w" to (rawWidth ?: ""),
                "h" to (rawHeight ?: ""),
            ),
        )
        // #endregion
    }

    @JavascriptInterface
    fun poster(rawUrl: String?) {
        rawUrl?.trim()?.takeIf(::isHttpUrl)?.let(onPoster)
    }

    companion object {
        internal fun isHttpUrl(value: String): Boolean = runCatching {
            URI(value).scheme?.lowercase() in setOf("http", "https")
        }.getOrDefault(false)
    }
}
