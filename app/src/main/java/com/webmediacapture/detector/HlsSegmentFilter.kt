package com.webmediacapture.detector

object HlsSegmentFilter {
    fun isMedia(url: String): Boolean = !AdMediaFilter.isAdUrl(url)
}
