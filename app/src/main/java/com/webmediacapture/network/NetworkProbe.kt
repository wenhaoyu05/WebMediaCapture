package com.webmediacapture.network

import com.webmediacapture.model.RequestContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class NetworkProbe(private val client: OkHttpClient) {
    data class Result(
        val statusCode: Int,
        val finalUrl: String,
        val mimeType: String?,
        val contentLength: Long?,
        val bodyPrefix: ByteArray = byteArrayOf(),
    )

    suspend fun inspect(url: String, context: RequestContext): Result = withContext(Dispatchers.IO) {
        val head = request(url, context, head = true)
        if (head.statusCode !in setOf(400, 403, 405, 501) && head.mimeType != "application/octet-stream") return@withContext head
        request(url, context, head = false)
    }

    suspend fun fetchText(url: String, context: RequestContext, maxBytes: Int = 1_048_576): String = withContext(Dispatchers.IO) {
        try {
            getText(url, context, maxBytes)
        } catch (error: java.io.IOException) {
            val msg = error.message.orEmpty()
            if ("HTTP 403" !in msg && "HTTP 401" !in msg) throw error
            val stripped = RequestContext(
                context.headers.filterKeys { !it.equals("Origin", true) && !it.equals("Cookie", true) },
            )
            getText(url, stripped, maxBytes)
        }
    }

    private fun getText(url: String, context: RequestContext, maxBytes: Int): String {
        val builder = Request.Builder().url(url).get()
        HeaderManager.apply(builder, context)
        return client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            response.body?.byteStream()?.readNBytesCompat(maxBytes)?.toString(Charsets.UTF_8)
                ?: throw java.io.IOException("Empty response body")
        }
    }

    private fun request(url: String, context: RequestContext, head: Boolean): Result {
        val builder = Request.Builder().url(url)
        HeaderManager.apply(builder, context)
        if (head) builder.head() else builder.get().header("Range", "bytes=0-4095")
        client.newCall(builder.build()).execute().use { response ->
            val prefix = if (head) byteArrayOf() else response.body?.byteStream()?.readNBytesCompat(4096) ?: byteArrayOf()
            return Result(
                statusCode = response.code,
                finalUrl = response.request.url.toString(),
                mimeType = response.header("Content-Type")?.substringBefore(';')?.trim(),
                contentLength = response.header("Content-Length")?.toLongOrNull(),
                bodyPrefix = prefix,
            )
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(max)
        val buffer = ByteArray(1024)
        var remaining = max
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) break
            output.write(buffer, 0, read)
            remaining -= read
        }
        return output.toByteArray()
    }
}
