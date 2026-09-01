package com.webmediacapture.download

import android.content.Context
import com.webmediacapture.model.RequestContext
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class YtDlpEngine(private val context: Context) {
    data class Progress(val percent: Double, val line: String, val bytes: Long = 0L, val total: Long? = null)

    suspend fun download(
        id: String,
        url: String,
        destinationDir: File,
        requestContext: RequestContext,
        formatId: String? = null,
        extraOptions: List<String> = emptyList(),
        onProgress: suspend (Progress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        destinationDir.mkdirs()
        val before = destinationDir.listFiles()?.toSet().orEmpty()
        val cookie = cookieFile(id, url, requestContext.value("Cookie"))
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--continue")
            addOption("--no-mtime")
            addOption("--merge-output-format", "mp4")
            addOption("-f", formatId?.let { "$it+bestaudio/best" } ?: "bestvideo+bestaudio/best")
            addOption("-o", File(destinationDir, "yt-%(id)s.%(ext)s").absolutePath)
            addOption("--retries", "1")
            addOption("--fragment-retries", "1")
            addOption("--socket-timeout", "20")
            extraOptions.forEach { addOption(it) }
            cookie?.let { addOption("--cookies", it.absolutePath) }
            requestContext.downloadHeaders().filterKeys { !it.equals("Cookie", true) }.forEach { (name, value) ->
                addOption("--add-header", "$name:$value")
            }
        }
        try {
            YoutubeDL.getInstance().execute(request, id) { progress, _, line ->
                kotlinx.coroutines.runBlocking { onProgress(Progress(progress.toDouble().coerceIn(0.0, 100.0), line)) }
            }
        } finally {
            cookie?.delete()
        }
        val created = destinationDir.listFiles()?.filter { it !in before && it.isFile && !it.name.endsWith(".part") }.orEmpty()
        created.maxByOrNull(File::lastModified) ?: throw java.io.IOException("yt-dlp produced no output file")
    }

    fun cancel(id: String) { YoutubeDL.getInstance().destroyProcessById(id) }

    private fun cookieFile(id: String, url: String, value: String?): File? {
        if (value.isNullOrBlank()) return null
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return null
        val file = File(context.cacheDir, "yt-$id.cookies")
        val lines = value.split(';').mapNotNull { item ->
            val name = item.substringBefore('=', "").trim()
            val cookieValue = item.substringAfter('=', "").trim()
            if (name.isBlank()) null else "$host\tTRUE\t/\tFALSE\t0\t$name\t$cookieValue"
        }
        file.writeText("# Netscape HTTP Cookie File\n${lines.joinToString("\n")}\n")
        return file
    }
}
