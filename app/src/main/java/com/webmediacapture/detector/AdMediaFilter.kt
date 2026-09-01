package com.webmediacapture.detector

import com.webmediacapture.model.MediaRole
import java.net.URI

object AdMediaFilter {
    private val adHosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "pagead2.googlesyndication.com",
        "adservice.google.com", "adsystem.com", "adnxs.com", "adsrvr.org", "moatads.com",
        "imasdk.googleapis.com", "adsafeprotected.com", "taboola.com", "outbrain.com",
        "criteo.com", "pubmatic.com", "rubiconproject.com", "openx.net", "mgid.com",
        "adservice.google", "advertising.com", "smartadserver.com", "adform.net", "gumgum.com",
        "33across.com", "sonobi.com", "triplelift.com", "indexexchange.com", "sovrn.com",
        "yieldmo.com", "adcolony.com", "vungle.com", "inmobi.com", "tapjoy.com",
    )
    private val adSegments = setOf(
        "ad", "ads", "advert", "advertisement", "preroll", "midroll", "postroll", "vast", "vpaid", "ima",
        "banner", "sponsor", "promo", "splash", "interstitial", "native", "popup", "popunder", "affiliate",
        "dfp", "gpt", "fbau", "adsense", "adx", "pubads",
    )

    private val AD_TRACKER_RE = Regex(
        "(nettrck|adtrk|adtrack|adclick|clicktrk|clicktrack|adtracker|afftrk|clkmg|adserving|trking|tracker)",
        RegexOption.IGNORE_CASE,
    )

    fun isAdUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        if (adHosts.any { host == it || host.endsWith(".$it") }) return true
        if (host.contains("tiktokcdn") && uri.path.orEmpty().contains("ad-site", true)) return true
        val path = uri.path.orEmpty().lowercase().split('/').filter { it.isNotBlank() }
        return path.any { segment ->
            val name = segment.substringBefore('.')
            name in adSegments || name.startsWith("pagead")
        }
    }

    /**
     * True when a media request's Referer points at an ad-tracking / click-redirect
     * gateway (e.g. t.nettrck.store). Such requests are auto-playing embedded ads,
     * not the page's main content, and should not be captured.
     */
    fun isAdReferer(referer: String?): Boolean {
        if (referer.isNullOrBlank()) return false
        val host = runCatching { URI(referer).host }.getOrNull()?.lowercase() ?: return false
        if (adHosts.any { host == it || host.endsWith(".$it") }) return true
        return AD_TRACKER_RE.containsMatchIn(host)
    }

    fun parseRole(raw: String?): MediaRole = when (raw?.trim()?.lowercase()) {
        "main" -> MediaRole.MAIN
        "ad" -> MediaRole.AD
        "overlay" -> MediaRole.OVERLAY
        else -> MediaRole.UNKNOWN
    }
}
