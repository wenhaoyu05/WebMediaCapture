package com.webmediacapture.download

import com.webmediacapture.detector.HlsParser
import com.webmediacapture.model.RequestContext
import com.webmediacapture.network.HeaderManager
import com.webmediacapture.util.SafeLog
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.coroutineContext

class HlsDownloader(
    private val client: OkHttpClient,
    private val muxer: SegmentMuxer,
    private val fallback: YtDlpEngine? = null,
) {
        constructor(context: android.content.Context) : this(
        client = com.webmediacapture.network.HttpClientProvider.client,
        muxer = FfmpegMuxer(context),
        fallback = YtDlpEngine(context),
    )

    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    suspend fun download(
        id: String,
        manifestUrl: String,
        destinationDir: File,
        requestContext: RequestContext,
        formatId: String? = null,
        pageUrl: String? = null,
        onProgress: suspend (YtDlpEngine.Progress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        cancelled.remove(id)
        destinationDir.mkdirs()
        val work = File(destinationDir, "hls-$id").apply { mkdirs() }
        try {
            nativeDownload(id, manifestUrl, work, destinationDir, requestContext, formatId, onProgress)
        } catch (error: Throwable) {
            if (cancelled.contains(id) || error is kotlinx.coroutines.CancellationException) throw error
            SafeLog.w("HLS", "Native HLS download failed: ${error.message}")
            // #region agent log
            com.webmediacapture.util.AgentDebugLog.emit(
                "G",
                "HlsDownloader.kt:download",
                "native-fail",
                mapOf("id" to id.take(8), "err" to (error.javaClass.simpleName + ":" + (error.message ?: "")).take(160)),
            )
            // #endregion
            val fallbackUrl = pageUrl?.takeIf { it.startsWith("http", true) }
            if (fallback != null && fallbackUrl != null && error.message?.contains("no media segments") == true) {
                // #region agent log
                com.webmediacapture.util.AgentDebugLog.emit(
                    "G",
                    "HlsDownloader.kt:download",
                    "hls-page-fallback",
                    mapOf("id" to id.take(8), "page" to com.webmediacapture.util.AgentDebugLog.safeUrl(fallbackUrl)),
                )
                // #endregion
                return@withContext fallback.download(id, fallbackUrl, destinationDir, requestContext, null, onProgress = onProgress)
            }
            throw error
        }
    }

    fun cancel(id: String) {
        cancelled += id
        fallback?.cancel(id)
    }

    private suspend fun nativeDownload(
        id: String,
        manifestUrl: String,
        work: File,
        destinationDir: File,
        requestContext: RequestContext,
        formatId: String?,
        onProgress: suspend (YtDlpEngine.Progress) -> Unit,
    ): File {
        return try {
            nativeDownloadInner(id, manifestUrl, work, destinationDir, requestContext, formatId, onProgress)
        } catch (error: Throwable) {
            if (cancelled.contains(id) || error is kotlinx.coroutines.CancellationException) throw error
            var lastError: Throwable = error
            if (isForbidden(error)) {
                try {
                    return nativeDownloadInner(
                        id, manifestUrl, work, destinationDir,
                        PlayrecordFallbackResolver.cdnContext(requestContext), formatId, onProgress,
                    )
                } catch (retry: Throwable) {
                    if (cancelled.contains(id) || retry is kotlinx.coroutines.CancellationException) throw retry
                    lastError = retry
                }
            }
            val alternates = resolveAlternates(requestContext, lastError)
            if (alternates.isEmpty()) throw lastError
            val altContext = PlayrecordFallbackResolver.cdnContext(requestContext)
            for ((index, altUrl) in alternates.withIndex()) {
                // #region agent log
                com.webmediacapture.util.AgentDebugLog.emit(
                    "G",
                    "HlsDownloader.kt:nativeDownload",
                    "hls-alternate-retry",
                    mapOf(
                        "id" to id.take(8),
                        "alt" to (index + 1),
                        "of" to alternates.size,
                        "url" to com.webmediacapture.util.AgentDebugLog.safeUrl(altUrl),
                    ),
                )
                // #endregion
                try {
                    return nativeDownloadInner(id, altUrl, work, destinationDir, altContext, formatId, onProgress)
                } catch (e: Throwable) {
                    if (cancelled.contains(id) || e is kotlinx.coroutines.CancellationException) throw e
                    lastError = e
                    SafeLog.w("HLS", "alternate ${index + 1} failed: ${e.message} url=${altUrl.take(90)}")
                }
            }
            throw lastError
        }
    }

    private fun resolveAlternates(requestContext: RequestContext, error: Throwable): List<String> {
        if (!isUnreachableError(error) && !isForbidden(error)) return emptyList()
        // #region agent log
        com.webmediacapture.util.AgentDebugLog.emit(
            "G",
            "HlsDownloader.kt:nativeDownload",
            "hls-unreachable-resolving",
            mapOf("err" to (error.javaClass.simpleName + ":" + (error.message ?: "")).take(160)),
        )
        // #endregion
        return PlayrecordFallbackResolver.alternateSources(client, requestContext, referer = null)
    }

    private fun isUnreachableError(error: Throwable): Boolean {
        if (error is java.net.ConnectException ||
            error is java.net.SocketTimeoutException ||
            error is java.net.UnknownHostException ||
            error is java.net.NoRouteToHostException
        ) return true
        val msg = error.message?.lowercase() ?: return false
        return msg.contains("failed to connect") || msg.contains("connect timed out") ||
            msg.contains("unable to resolve host") || msg.contains("connection refused") ||
            msg.contains("no route to host") || msg.contains("timed out") && msg.contains("connect")
    }

    private fun isForbidden(error: Throwable): Boolean {
        val msg = error.message?.lowercase() ?: return false
        return msg.contains("http 403") || msg.contains("http 401")
    }

    private suspend fun nativeDownloadInner(
        id: String,
        manifestUrl: String,
        work: File,
        destinationDir: File,
        requestContext: RequestContext,
        formatId: String?,
        onProgress: suspend (YtDlpEngine.Progress) -> Unit,
    ): File {
        val masterText = fetchText(manifestUrl, requestContext)
        val master = HlsParser.parse(masterText, manifestUrl)
        if (master.drmProtected) throw IllegalArgumentException("DRM protected media is not downloadable")
        val mediaUrl = if (master.isMaster) {
            val variant = master.variants.firstOrNull { it.formatId == formatId }
                ?: master.variants.maxWithOrNull(compareBy<com.webmediacapture.model.MediaVariant> { it.height ?: 0 }.thenBy { it.bitrate ?: 0 })
            variant?.url ?: manifestUrl
        } else manifestUrl
        val playlistText = if (mediaUrl == manifestUrl) masterText else fetchText(mediaUrl, requestContext)
        val parsed = HlsParser.parseMedia(playlistText, mediaUrl)
        if (parsed.drmProtected) throw IllegalArgumentException("DRM protected media is not downloadable")
        // Download is an explicit user action: take every segment listed in the playlist.
        // Many sites disguise real video segments (e.g. ...ad-site...ttam-origin.image) behind
        // names/hosts that look like ads, so per-URL ad filtering must NOT be applied here.
        val segments = parsed.segments
        if (segments.isEmpty()) throw java.io.IOException("HLS playlist has no media segments")
        val media = parsed.copy(segments = segments)
        val key = if (media.aes128 && media.keyUri != null) fetchBytes(media.keyUri, requestContext) else null
        // #region agent log
        com.webmediacapture.util.AgentDebugLog.emit(
            "G",
            "HlsDownloader.kt:nativeDownload",
            "hls-start",
            mapOf("id" to id.take(8), "segs" to media.segments.size, "url" to com.webmediacapture.util.AgentDebugLog.safeUrl(mediaUrl)),
        )
        // #endregion
        val files = mutableListOf<File>()
        val downloaded = AtomicLong(0L)
        val done = AtomicInteger(0)
        val lastEmit = AtomicLong(0L)
        val totalSegs = media.segments.size.coerceAtLeast(1)
        suspend fun emit(force: Boolean = false, merging: Boolean = false) {
            val bytes = downloaded.get()
            val completed = done.get().coerceAtLeast(0)
            val estimated = if (completed > 0) (bytes * totalSegs / completed).coerceAtLeast(bytes) else null
            val percent = when {
                merging -> 100.0
                estimated != null && estimated > 0L -> (bytes * 100.0 / estimated).coerceIn(0.0, 99.99)
                else -> (completed * 100.0 / totalSegs).coerceIn(0.0, 99.99)
            }
            val now = System.currentTimeMillis()
            val prev = lastEmit.get()
            if (!force && now - prev < 200) return
            if (!force && !lastEmit.compareAndSet(prev, now)) return
            lastEmit.set(now)
            onProgress(YtDlpEngine.Progress(percent, if (merging) "ffmpeg" else "segment $completed/$totalSegs", bytes, estimated))
        }
        media.initSegmentUrl?.let { initUrl ->
            val initFile = File(work, "init.mp4")
            if (!initFile.exists() || initFile.length() == 0L) {
                fetchToFile(initUrl, requestContext, initFile) { n -> downloaded.addAndGet(n.toLong()) }
            } else {
                downloaded.addAndGet(initFile.length())
            }
            files += initFile
        }
        val workers = Dispatchers.IO.limitedParallelism(PARALLEL)
        val segmentFiles = coroutineScope {
            media.segments.mapIndexed { index, segment ->
                async(workers) {
                    coroutineContext.ensureActive()
                    if (id in cancelled) throw kotlinx.coroutines.CancellationException("cancelled")
                    val file = File(work, "seg-${segment.sequence.toString().padStart(6, '0')}.bin")
                    if (!file.exists() || file.length() == 0L) {
                        fetchSegment(segment.url, requestContext, file, key, ivFor(media.keyIv, segment.sequence)) { n ->
                            downloaded.addAndGet(n.toLong())
                            emit()
                        }
                    } else {
                        downloaded.addAndGet(file.length())
                    }
                    done.incrementAndGet()
                    emit()
                    index to file
                }
            }.awaitAll().sortedBy { it.first }.map { it.second }
        }
        files += segmentFiles
        emit(force = true, merging = true)
        val output = File(destinationDir, "hls-$id.mp4")
        muxer.merge(files, output)
        return output
    }

    private suspend fun fetchSegment(
        url: String,
        context: RequestContext,
        dest: File,
        key: ByteArray?,
        iv: ByteArray,
        onRead: suspend (Int) -> Unit,
    ) {
        var last: Throwable? = null
        repeat(SEG_ATTEMPTS) {
            try {
                if (key != null) {
                    dest.writeBytes(decrypt(fetchBytes(url, context), key, iv))
                    onRead(dest.length().toInt().coerceAtLeast(0))
                } else {
                    fetchToFile(url, context, dest, onRead)
                }
                if (dest.length() > 0L) return
                last = java.io.IOException("empty segment")
            } catch (error: Throwable) {
                last = error
                dest.delete()
                File(dest.path + ".part").delete()
                if (!isTransient(error)) throw error
            }
        }
        throw last ?: java.io.IOException("segment failed")
    }

    private fun isTransient(error: Throwable): Boolean {
        if (error is java.net.SocketTimeoutException ||
            error is java.net.ConnectException ||
            error is java.net.UnknownHostException
        ) return true
        val msg = error.message?.lowercase() ?: return false
        return msg.contains("timeout") || msg.contains("failed to connect") ||
            msg.contains("unexpected end") || msg.contains("connection reset") ||
            msg.contains("http 429") || msg.contains("http 5")
    }

    private fun fetchText(url: String, context: RequestContext): String = fetchBytes(url, context).toString(Charsets.UTF_8)

    private suspend fun fetchToFile(
        url: String,
        context: RequestContext,
        dest: File,
        onRead: suspend (Int) -> Unit = {},
    ) {
        try {
            DownloadIo.fetchToFile(client, url, context, dest, onRead)
        } catch (error: Throwable) {
            // #region agent log
            com.webmediacapture.util.AgentDebugLog.emit(
                "H1",
                "HlsDownloader.kt:fetchToFile",
                "seg-fail",
                mapOf(
                    "url" to com.webmediacapture.util.AgentDebugLog.safeUrl(url),
                    "err" to (error.javaClass.simpleName + ":" + (error.message ?: "")).take(160),
                ),
            )
            // #endregion
            throw error
        }
    }

    private fun fetchBytes(url: String, context: RequestContext): ByteArray {
        val builder = Request.Builder().url(url)
        HeaderManager.apply(builder, context)
        try {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                return response.body?.bytes() ?: throw java.io.IOException("Empty body")
            }
        } catch (error: Throwable) {
            // #region agent log
            com.webmediacapture.util.AgentDebugLog.emit(
                "H1",
                "HlsDownloader.kt:fetchBytes",
                "bytes-fail",
                mapOf(
                    "url" to com.webmediacapture.util.AgentDebugLog.safeUrl(url),
                    "err" to (error.javaClass.simpleName + ":" + (error.message ?: "")).take(160),
                ),
            )
            // #endregion
            throw error
        }
    }

    private fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun ivFor(explicit: String?, sequence: Long): ByteArray {
        if (explicit != null) {
            val hex = explicit.removePrefix("0x").removePrefix("0X")
            return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        return ByteBuffer.allocate(16).putLong(0).putLong(sequence).array()
    }

    companion object {
        private const val PARALLEL = com.webmediacapture.network.HttpClientProvider.DOWNLOAD_PARALLEL
        private const val SEG_ATTEMPTS = 3
    }
}
