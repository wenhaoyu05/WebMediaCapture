package com.webmediacapture.download

import com.webmediacapture.detector.DashParser
import com.webmediacapture.model.RequestContext
import com.webmediacapture.network.HeaderManager
import com.webmediacapture.util.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

class DashDownloader(
    private val client: OkHttpClient,
    private val muxer: SegmentMuxer,
    private val fallback: YtDlpEngine? = null,
) {
        constructor(context: android.content.Context) : this(
        client = com.webmediacapture.network.HttpClientProvider.client,
        muxer = FfmpegMuxer(context),
        fallback = null,
    )

    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    suspend fun download(
        id: String,
        manifestUrl: String,
        destinationDir: File,
        requestContext: RequestContext,
        formatId: String? = null,
        onProgress: suspend (YtDlpEngine.Progress) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        cancelled.remove(id)
        destinationDir.mkdirs()
        val work = File(destinationDir, "dash-$id").apply { mkdirs() }
        try {
            nativeDownload(id, manifestUrl, work, destinationDir, requestContext, formatId, onProgress)
        } catch (error: Throwable) {
            if (cancelled.contains(id) || error is kotlinx.coroutines.CancellationException) throw error
            SafeLog.w("DASH", "Native DASH download failed: ${error.message}")
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
        val parsed = DashParser.parse(fetchText(manifestUrl, requestContext), manifestUrl)
        if (parsed.drmProtected) throw IllegalArgumentException("DRM protected media is not downloadable")
        val video = parsed.representations.filter { it.isVideo }.let { videos ->
            videos.firstOrNull { it.id == formatId } ?: videos.maxWithOrNull(compareBy<DashParser.Representation> { it.height ?: 0 }.thenBy { it.bandwidth ?: 0 })
        } ?: parsed.representations.maxByOrNull { it.bandwidth ?: 0 }
        val audio = parsed.representations.filter { it.isAudio && it.id != video?.id }
            .maxByOrNull { it.bandwidth ?: 0 }
        if (video == null || video.mediaUrls.isEmpty()) throw java.io.IOException("DASH representation has no enumerable segments")
        val tracks = listOfNotNull(video, audio)
        val totalSegs = tracks.sumOf { it.mediaUrls.size + if (it.initUrl != null) 1 else 0 }.coerceAtLeast(1)
        val downloaded = AtomicLong(0L)
        val done = AtomicInteger(0)
        val lastEmit = AtomicLong(0L)
        suspend fun emit(force: Boolean = false, merging: Boolean = false) {
            val bytes = downloaded.get()
            val completed = done.get()
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
        val workers = Dispatchers.IO.limitedParallelism(PARALLEL)
        val outputs = coroutineScope {
            tracks.map { track ->
                async {
                    downloadTrack(id, track, work, requestContext, workers, downloaded, done, ::emit)
                }
            }.awaitAll()
        }
        emit(force = true, merging = true)
        val output = File(destinationDir, "dash-$id.mp4")
        muxer.merge(outputs, output)
        return output
    }

    private suspend fun downloadTrack(
        id: String,
        track: DashParser.Representation,
        work: File,
        requestContext: RequestContext,
        workers: kotlin.coroutines.CoroutineContext,
        downloaded: AtomicLong,
        done: AtomicInteger,
        emit: suspend (Boolean, Boolean) -> Unit,
    ): File {
        val parts = mutableListOf<File>()
        track.initUrl?.let { initUrl ->
            val init = File(work, "${track.id ?: "track"}-init.m4s")
            if (!init.exists() || init.length() == 0L) {
                fetchToFile(initUrl, requestContext, init) { n ->
                    downloaded.addAndGet(n.toLong())
                    emit(false, false)
                }
            } else {
                downloaded.addAndGet(init.length())
            }
            parts += init
            done.incrementAndGet()
            emit(false, false)
        }
        val segmentFiles = coroutineScope {
            track.mediaUrls.mapIndexed { index, url ->
                async(workers) {
                    coroutineContext.ensureActive()
                    if (id in cancelled) throw kotlinx.coroutines.CancellationException("cancelled")
                    val part = File(work, "${track.id ?: "track"}-${index.toString().padStart(6, '0')}.m4s")
                    if (!part.exists() || part.length() == 0L) {
                        fetchToFile(url, requestContext, part) { n ->
                            downloaded.addAndGet(n.toLong())
                            emit(false, false)
                        }
                    } else {
                        downloaded.addAndGet(part.length())
                    }
                    done.incrementAndGet()
                    emit(false, false)
                    index to part
                }
            }.awaitAll().sortedBy { it.first }.map { it.second }
        }
        parts += segmentFiles
        val assembled = File(work, "${track.id ?: "track"}.bin")
        ConcatMuxer().merge(parts, assembled)
        return assembled
    }

    private fun fetchText(url: String, context: RequestContext): String = fetchBytes(url, context).toString(Charsets.UTF_8)

    private suspend fun fetchToFile(url: String, context: RequestContext, dest: File, onRead: suspend (Int) -> Unit) {
        DownloadIo.fetchToFile(client, url, context, dest, onRead)
    }

    private fun fetchBytes(url: String, context: RequestContext): ByteArray {
        val builder = Request.Builder().url(url)
        HeaderManager.apply(builder, context)
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            return response.body?.bytes() ?: throw java.io.IOException("Empty body")
        }
    }

    companion object {
        private const val PARALLEL = com.webmediacapture.network.HttpClientProvider.DOWNLOAD_PARALLEL
    }
}
