package com.webmediacapture.download

import com.webmediacapture.model.RequestContext
import com.webmediacapture.network.HeaderManager
import com.webmediacapture.network.HttpClientProvider
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.coroutineContext

internal object DownloadIo {
    val BUFFER = HttpClientProvider.DOWNLOAD_BUFFER

    suspend fun copy(input: InputStream, output: OutputStream, onRead: suspend (Int) -> Unit = {}): Long {
        val buf = ByteArray(BUFFER)
        var total = 0L
        while (true) {
            coroutineContext.ensureActive()
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            total += n
            onRead(n)
        }
        return total
    }

    suspend fun fetchToFile(
        client: OkHttpClient,
        url: String,
        context: RequestContext,
        dest: File,
        onRead: suspend (Int) -> Unit = {},
    ) {
        val part = File(dest.path + ".part")
        val builder = Request.Builder().url(url)
        HeaderManager.apply(builder, context)
        try {
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                val body = response.body ?: throw java.io.IOException("Empty body")
                part.outputStream().use { out -> copy(body.byteStream(), out, onRead) }
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
        } catch (error: Throwable) {
            part.delete()
            dest.delete()
            throw error
        }
    }
}
