package com.webmediacapture.download

import com.webmediacapture.model.RequestContext
import com.webmediacapture.network.HeaderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

class DirectDownloader(
    private val client: OkHttpClient,
    private val parts: Int = 8,
    private val minMultiBytes: Long = 1L * 1024 * 1024,
) {
    data class Result(val file: File, val downloaded: Long, val total: Long?)

    suspend fun download(
        url: String,
        destination: File,
        context: RequestContext,
        onProgress: suspend (downloaded: Long, total: Long?) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val partial = File(destination.absolutePath + ".part")
        val offset = partial.takeIf(File::exists)?.length() ?: 0L
        if (offset > 0L) {
            return@withContext singleStream(url, destination, partial, offset, context, onProgress)
        }
        try {
            parallelOrSingle(url, destination, partial, context, onProgress)
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            cleanupParts(partial)
            singleStream(url, destination, partial, 0L, context, onProgress)
        }
    }

    private suspend fun parallelOrSingle(
        url: String,
        destination: File,
        partial: File,
        context: RequestContext,
        onProgress: suspend (downloaded: Long, total: Long?) -> Unit,
    ): Result {
        val builder = Request.Builder().url(url)
        HeaderManager.apply(builder, context)
        builder.header("Range", "bytes=0-0")
        val response = client.newCall(builder.build()).execute()
        try {
            if (!response.isSuccessful && response.code != 206) {
                response.close()
                return singleStream(url, destination, partial, 0L, context, onProgress)
            }
            val total = parseTotal(response, start = 0L)
            if (response.code == 206) {
                response.close()
                if (total != null && total >= minMultiBytes && parts > 1) {
                    return try {
                        parallel(url, destination, partial, total, context, onProgress)
                    } catch (error: Throwable) {
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        cleanupParts(partial)
                        singleStream(url, destination, partial, 0L, context, onProgress)
                    }
                }
                return singleStream(url, destination, partial, 0L, context, onProgress)
            }
            val length = response.body?.contentLength() ?: -1L
            if (length in 0L..1L) {
                response.close()
                return singleStream(url, destination, partial, 0L, context, onProgress)
            }
            return drain(response, destination, partial, append = false, start = 0L, total = total, onProgress = onProgress)
        } finally {
            runCatching { response.close() }
        }
    }

    private suspend fun parallel(
        url: String,
        destination: File,
        partial: File,
        total: Long,
        context: RequestContext,
        onProgress: suspend (downloaded: Long, total: Long?) -> Unit,
    ): Result {
        val chunk = ((total + parts - 1) / parts).coerceAtLeast(1L)
        val downloaded = AtomicLong(0L)
        val lastEmit = AtomicLong(0L)
        val workers = Dispatchers.IO.limitedParallelism(parts)
        val files = coroutineScope {
            (0 until parts).map { index ->
                val start = index * chunk
                val end = minOf(total - 1, start + chunk - 1)
                async(workers) {
                    if (start >= total) return@async null
                    val file = File("${partial.path}.$index")
                    val expected = end - start + 1
                    val existing = file.takeIf { it.exists() }?.length()?.coerceAtMost(expected) ?: 0L
                    if (existing > 0) downloaded.addAndGet(existing)
                    fetchRange(url, context, file, start, end, existing) { n ->
                        val nowBytes = downloaded.addAndGet(n.toLong())
                        val now = System.currentTimeMillis()
                        val prev = lastEmit.get()
                        if (now - prev >= 200 && lastEmit.compareAndSet(prev, now)) {
                            onProgress(nowBytes, total)
                        }
                    }
                    file
                }
            }.awaitAll().filterNotNull()
        }
        onProgress(total, total)
        concat(files, destination)
        files.forEach { it.delete() }
        cleanupParts(partial)
        return Result(destination, total, total)
    }

    private suspend fun fetchRange(
        url: String,
        context: RequestContext,
        dest: File,
        start: Long,
        end: Long,
        existing: Long,
        onRead: suspend (Int) -> Unit,
    ) {
        val expected = end - start + 1
        if (existing == expected) return
        val from = start + existing
        val builder = Request.Builder().url(url)
        HeaderManager.apply(builder, context)
        builder.header("Range", "bytes=$from-$end")
        client.newCall(builder.build()).execute().use { response ->
            if (response.code != 206) throw java.io.IOException("HTTP ${response.code}")
            val body = response.body ?: throw java.io.IOException("Empty body")
            java.io.FileOutputStream(dest, existing > 0).use { out ->
                DownloadIo.copy(body.byteStream(), out, onRead)
            }
        }
        if (dest.length() != expected) throw java.io.IOException("Incomplete range $start-$end")
    }

    private suspend fun singleStream(
        url: String,
        destination: File,
        partial: File,
        offset: Long,
        context: RequestContext,
        onProgress: suspend (downloaded: Long, total: Long?) -> Unit,
    ): Result {
        val builder = Request.Builder().url(url)
        HeaderManager.apply(builder, context)
        if (offset > 0) builder.header("Range", "bytes=$offset-")
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            val append = offset > 0 && response.code == 206
            if (offset > 0 && !append) partial.delete()
            val start = if (append) offset else 0L
            val total = parseTotal(response, start)
            return drain(response, destination, partial, append, start, total, onProgress)
        }
    }

    private suspend fun drain(
        response: Response,
        destination: File,
        partial: File,
        append: Boolean,
        start: Long,
        total: Long?,
        onProgress: suspend (downloaded: Long, total: Long?) -> Unit,
    ): Result {
        var downloaded = start
        var lastEmit = 0L
        java.io.FileOutputStream(partial, append).use { output ->
            val input = response.body?.byteStream() ?: throw java.io.IOException("Empty response body")
            DownloadIo.copy(input, output) { read ->
                downloaded += read
                val now = System.currentTimeMillis()
                if (now - lastEmit >= 200 || total != null && downloaded >= total) {
                    lastEmit = now
                    onProgress(downloaded, total)
                }
            }
            output.fd.sync()
        }
        onProgress(downloaded, total)
        if (destination.exists() && !destination.delete()) throw java.io.IOException("Cannot replace destination")
        if (!partial.renameTo(destination)) throw java.io.IOException("Cannot finalize download")
        return Result(destination, downloaded, total)
    }

    private fun parseTotal(response: Response, start: Long): Long? {
        val range = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
        if (range != null && range > 0) return range
        val length = response.body?.contentLength()?.takeIf { it >= 0 } ?: return null
        return length + start
    }

    private suspend fun concat(parts: List<File>, destination: File) {
        coroutineContext.ensureActive()
        if (destination.exists() && !destination.delete()) throw java.io.IOException("Cannot replace destination")
        destination.outputStream().use { out ->
            parts.forEach { part ->
                part.inputStream().use { input -> DownloadIo.copy(input, out) }
            }
        }
    }

    private fun cleanupParts(partial: File) {
        partial.parentFile?.listFiles()?.filter { it.name.startsWith(partial.name) }?.forEach { it.delete() }
    }
}
