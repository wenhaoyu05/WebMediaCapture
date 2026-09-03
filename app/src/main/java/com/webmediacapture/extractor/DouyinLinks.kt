package com.webmediacapture.extractor

import java.net.URI

object DouyinLinks {
    private val VIDEO_PATH = Regex("""/(?:video|share/video|note|slides)/(\d{6,})""")
    private val VIDEO_QUERY = Regex("""(?:modal_id|aweme_id|item_ids)=(\d{6,})""")

    fun isDouyinHost(host: String?): Boolean {
        val value = host?.lowercase() ?: return false
        return value == "v.douyin.com" || value.endsWith(".douyin.com") || value == "douyin.com" ||
            value.endsWith(".iesdouyin.com") || value == "iesdouyin.com"
    }

    fun isDouyinVideo(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        if (!isDouyinHost(host)) return false
        if (host.equals("v.douyin.com", true)) return true
        return videoId(url) != null
    }

    fun videoId(url: String): String? =
        VIDEO_PATH.find(url)?.groupValues?.get(1) ?: VIDEO_QUERY.find(url)?.groupValues?.get(1)

    fun watchUrl(id: String): String = "https://www.douyin.com/video/$id"

    fun pageUrlForExtract(url: String): String {
        val id = videoId(url) ?: return url
        return watchUrl(id)
    }

    fun isMediaUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.path.orEmpty().lowercase()
        return host.contains("douyinvod") || host.contains("douyincdn") ||
            path.contains("/aweme/v1/play")
    }
}
