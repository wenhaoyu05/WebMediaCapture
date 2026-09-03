package com.webmediacapture.extractor

import com.webmediacapture.model.DetectionSource
import com.webmediacapture.model.MediaCandidate
import com.webmediacapture.model.MediaType
import com.webmediacapture.model.RequestContext
import com.webmediacapture.network.HeaderManager
import com.webmediacapture.network.HttpClientProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder

class DouyinExtractor(private val client: OkHttpClient = HttpClientProvider.client) {
    data class Info(
        val playUrl: String,
        val title: String? = null,
        val cover: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        val durationSec: Double? = null,
    )

    fun resolve(pageUrl: String, pageSessionId: String, context: RequestContext): MediaCandidate {
        val headers = context.headers.toMutableMap()
        headers.putIfAbsent("User-Agent", UA)
        headers.putIfAbsent("Referer", "https://www.douyin.com/")
        headers.putIfAbsent("Accept", "*/*")
        val requestContext = RequestContext(headers)
        val location = finalUrl(pageUrl, requestContext)
        val id = DouyinLinks.videoId(location) ?: throw IOException("not a douyin video")
        val info = itemInfo(id, requestContext) ?: sharePage(id, requestContext)
            ?: throw IOException("douyin parse failed")
        return candidate(info, pageSessionId, DouyinLinks.pageUrlForExtract(location), requestContext)
    }

    fun finalUrl(pageUrl: String, context: RequestContext): String {
        val builder = Request.Builder().url(pageUrl).get()
        HeaderManager.apply(builder, context)
        return client.newCall(builder.build()).execute().use { it.request.url.toString() }
    }

    private fun itemInfo(id: String, context: RequestContext): Info? {
        val urls = listOf(
            "https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=$id",
            "https://www.iesdouyin.com/aweme/v1/web/aweme/detail/?aweme_id=$id&aid=1128&device_platform=webapp",
        )
        urls.forEach { url ->
            val body = runCatching { getText(url, context) }.getOrNull() ?: return@forEach
            fromJson(body)?.let { return it }
        }
        return null
    }

    private fun sharePage(id: String, context: RequestContext): Info? {
        val html = runCatching { getText("https://www.iesdouyin.com/share/video/$id", context) }.getOrNull() ?: return null
        return fromShareHtml(html)
    }

    private fun getText(url: String, context: RequestContext): String {
        val builder = Request.Builder().url(url).get()
        HeaderManager.apply(builder, context)
        return client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    companion object {
        const val UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"

        const val PAGE_PROBE_JS =
            "(function(){function t(id){var el=document.getElementById(id);return el&&el.textContent?el.textContent:'';}" +
                "var encoded=t('RENDER_DATA')||t('_ROUTER_DATA');if(encoded)return encoded;" +
                "try{if(window._ROUTER_DATA)return JSON.stringify(window._ROUTER_DATA);" +
                "if(window.RENDER_DATA)return JSON.stringify(window.RENDER_DATA);}catch(e){}" +
                "var html=document.documentElement?document.documentElement.innerHTML:'';return html.slice(0,400000);})()"

        fun candidate(info: Info, pageSessionId: String, pageUrl: String, context: RequestContext): MediaCandidate =
            MediaCandidate(
                pageSessionId = pageSessionId,
                pageUrl = pageUrl,
                mediaUrl = info.playUrl,
                title = info.title,
                type = MediaType.DIRECT,
                width = info.width,
                height = info.height,
                durationSec = info.durationSec,
                thumbnailUrl = info.cover,
                requestContext = context,
                source = DetectionSource.HTTP_PROBE,
                confidence = 92,
            )

        internal fun parseJsResult(raw: String?): String {
            if (raw.isNullOrBlank() || raw == "null") return ""
            val trimmed = raw.trim()
            if (trimmed.length < 2 || !trimmed.startsWith('"') || !trimmed.endsWith('"')) return trimmed
            return trimmed.substring(1, trimmed.length - 1)
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\\\", "\\")
        }

        internal fun fromPagePayload(raw: String): Info? {
            if (raw.isBlank()) return null
            val decoded = runCatching { URLDecoder.decode(raw, Charsets.UTF_8.name()) }.getOrDefault(raw)
            return fromJson(raw) ?: fromJson(decoded) ?: fromShareHtml(raw) ?: fromShareHtml(decoded)
        }

        internal fun nowatermark(url: String): String =
            url.replace("playwm", "play").replaceFirst("http://", "https://")

        internal fun fromJson(raw: String): Info? {
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            root.optJSONArray("item_list")?.optJSONObject(0)?.let { fromAweme(it)?.let { info -> return info } }
            root.optJSONObject("aweme_detail")?.let { fromAweme(it)?.let { info -> return info } }
            return fromAweme(root)
        }

        internal fun fromShareHtml(html: String): Info? {
            val encoded = Regex("""id="RENDER_DATA"[^>]*>([^<]+)</script>""").find(html)?.groupValues?.get(1)
                ?: Regex("""id="_ROUTER_DATA"[^>]*>([^<]+)</script>""").find(html)?.groupValues?.get(1)
            if (!encoded.isNullOrBlank()) {
                val json = runCatching { URLDecoder.decode(encoded, Charsets.UTF_8.name()) }.getOrNull()
                json?.let { fromJson(it) }?.let { return it }
            }
            val play = Regex("""https:\\?/\\?/[^"'\\\s]+(?:playwm|play)[^"'\\\s]*""").find(html)?.value
                ?.replace("\\/", "/")
                ?.replace("\\u002F", "/")
            return play?.let { Info(playUrl = nowatermark(it)) }
        }

        internal fun fromAweme(node: JSONObject): Info? {
            val play = playUrl(node) ?: return null
            val video = node.optJSONObject("video")
            val duration = (video?.optDouble("duration") ?: node.optDouble("duration")).takeIf { it.isFinite() && it > 0 }
            return Info(
                playUrl = play,
                title = node.optString("desc").ifBlank { node.optString("preview_title") }.ifBlank { null },
                cover = firstUrl(video?.optJSONObject("cover") ?: video?.optJSONObject("origin_cover") ?: node.optJSONObject("cover")),
                width = video?.optInt("width")?.takeIf { it > 0 } ?: node.optInt("width").takeIf { it > 0 },
                height = video?.optInt("height")?.takeIf { it > 0 } ?: node.optInt("height").takeIf { it > 0 },
                durationSec = duration?.let { if (it > 1000) it / 1000.0 else it },
            )
        }

        private fun playUrl(node: JSONObject): String? {
            node.optJSONObject("video")?.let { video ->
                firstUrl(video.optJSONObject("play_addr"))?.let { return nowatermark(it) }
                firstUrl(video.optJSONObject("download_addr"))?.let { return nowatermark(it) }
                firstUrl(video.optJSONObject("play_addr_h264"))?.let { return nowatermark(it) }
            }
            firstUrl(node.optJSONObject("play_addr"))?.let { return nowatermark(it) }
            val keys = node.keys() ?: return null
            while (keys.hasNext()) {
                when (val value = node.opt(keys.next())) {
                    is JSONObject -> playUrl(value)?.let { return it }
                    is JSONArray -> {
                        for (index in 0 until value.length()) {
                            val child = value.optJSONObject(index) ?: continue
                            playUrl(child)?.let { return it }
                        }
                    }
                }
            }
            return null
        }

        private fun firstUrl(node: JSONObject?): String? {
            val list = node?.optJSONArray("url_list") ?: return null
            for (index in 0 until list.length()) {
                val url = list.optString(index).takeIf { it.startsWith("http") } ?: continue
                return url
            }
            return null
        }
    }
}
