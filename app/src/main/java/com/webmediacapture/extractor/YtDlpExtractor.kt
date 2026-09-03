package com.webmediacapture.extractor

import android.content.Context
import com.webmediacapture.extractor.DouyinLinks
import com.webmediacapture.model.DetectionSource
import com.webmediacapture.model.MediaCandidate
import com.webmediacapture.model.MediaType
import com.webmediacapture.model.MediaVariant
import com.webmediacapture.model.RequestContext
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class YtDlpExtractor(private val context: Context) {
    suspend fun extract(pageSessionId: String, pageUrl: String, requestContext: RequestContext): MediaCandidate = withContext(Dispatchers.IO) {
        val extractUrl = DouyinLinks.pageUrlForExtract(pageUrl)
        val cookie = cookieFile(pageSessionId, extractUrl, requestContext.value("Cookie"))
        val request = YoutubeDLRequest(extractUrl).apply {
            addOption("--dump-single-json")
            addOption("--skip-download")
            addOption("--no-playlist")
            cookie?.let { addOption("--cookies", it.absolutePath) }
            requestContext.downloadHeaders().filterKeys { !it.equals("Cookie", true) }.forEach { (name, value) ->
                addOption("--add-header", "$name:$value")
            }
        }
        val output = try { YoutubeDL.getInstance().execute(request).out } finally { cookie?.delete() }
        val json = JSONObject(output)
        val variants = json.optJSONArray("formats")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val format = array.optJSONObject(index) ?: continue
                    val url = format.optString("url").takeIf(String::isNotBlank) ?: continue
                    val videoCodec = format.optString("vcodec").takeUnless { it == "none" || it.isBlank() }
                    val audioCodec = format.optString("acodec").takeUnless { it == "none" || it.isBlank() }
                    add(MediaVariant(
                        url = url,
                        formatId = format.optString("format_id").takeIf(String::isNotBlank),
                        width = format.optInt("width").takeIf { it > 0 },
                        height = format.optInt("height").takeIf { it > 0 },
                        bitrate = format.optDouble("tbr").takeIf { it > 0 }?.times(1000)?.toLong(),
                        codecs = listOfNotNull(videoCodec, audioCodec).joinToString("/").takeIf(String::isNotBlank),
                    ))
                }
            }
        }.orEmpty()
        MediaCandidate(
            pageSessionId = pageSessionId,
            pageUrl = pageUrl,
            mediaUrl = pageUrl,
            title = json.optString("title").takeIf(String::isNotBlank),
            type = MediaType.UNKNOWN,
            width = json.optInt("width").takeIf { it > 0 },
            height = json.optInt("height").takeIf { it > 0 },
            estimatedSize = json.optLong("filesize").takeIf { it > 0 },
            durationSec = json.optDouble("duration").takeIf { it.isFinite() && it > 0 },
            thumbnailUrl = json.optString("thumbnail").takeIf { it.startsWith("http") },
            requestContext = requestContext,
            source = DetectionSource.YT_DLP,
            variants = variants,
            confidence = 95,
        )
    }

    private fun cookieFile(id: String, url: String, cookieHeader: String?): File? {
        if (cookieHeader.isNullOrBlank()) return null
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return null
        return File(context.cacheDir, "extract-${id.take(12)}.cookies").apply {
            val values = cookieHeader.split(';').mapNotNull { item ->
                val name = item.substringBefore('=', "").trim()
                val value = item.substringAfter('=', "").trim()
                if (name.isBlank()) null else "$host\tTRUE\t/\tFALSE\t0\t$name\t$value"
            }
            writeText("# Netscape HTTP Cookie File\n${values.joinToString("\n")}\n")
        }
    }
}
