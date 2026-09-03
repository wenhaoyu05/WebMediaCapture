package com.webmediacapture.library

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.webmediacapture.model.MediaCandidate
import com.webmediacapture.network.HttpClientProvider
import com.webmediacapture.util.ImageSniff
import com.webmediacapture.util.LibraryFiles
import okhttp3.Request
import java.io.File

object LibraryMedia {
    data class Info(val durationMs: Long?, val thumb: File?)

    fun thumbFile(context: Context, id: String): File =
        File(context.cacheDir, "thumbs").apply { mkdirs() }.resolve("$id.jpg")

    fun inspect(context: Context, id: String, file: File): Info {
        val thumb = thumbFile(context, id)
        if (!file.exists()) return Info(null, thumb.takeIf { it.exists() })
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (!LibraryFiles.isAudio(file.name)) writeThumb(retriever, thumb)
            Info(duration, thumb.takeIf { it.exists() })
        } catch (_: Throwable) {
            Info(null, thumb.takeIf { it.exists() })
        } finally {
            retriever.release()
        }
    }

    fun preview(context: Context, id: String, outputPath: String?): File? {
        val dest = thumbFile(context, id)
        if (dest.exists() && dest.length() > 0) return dest
        outputPath?.let(::File)?.takeIf { it.exists() && it.length() >= MIN_FRAME_BYTES }?.let {
            if (frameFromFile(it, dest)) return dest
        }
        if (frameFromArtifacts(context, id, dest)) return dest
        return dest.takeIf { it.exists() && it.length() > 0 }
    }

    fun capture(context: Context, candidate: MediaCandidate) {
        capturePoster(context, candidate.id, candidate.thumbnailUrl, candidate.requestContext.downloadHeaders())
        preview(context, candidate.id, null)
    }

    fun capturePoster(context: Context, id: String, url: String?, headers: Map<String, String>) {
        val dest = thumbFile(context, id)
        if (dest.exists() && dest.length() > 0) return
        if (url.isNullOrBlank()) return
        downloadImage(url, headers, dest)
    }

    fun deleteThumb(context: Context, id: String) {
        thumbFile(context, id).delete()
    }

    private fun frameFromArtifacts(context: Context, id: String, dest: File): Boolean {
        val dir = File(context.getExternalFilesDir(null), "downloads")
        if (hlsProbe(dir.resolve("hls-$id"), dest)) return true
        if (dashProbe(dir.resolve("dash-$id"), dest)) return true
        return artifactFiles(dir, id).any { frameFromFile(it, dest) }
    }

    private fun hlsProbe(work: File, dest: File): Boolean {
        val first = work.listFiles()?.filter { it.isFile && it.name.startsWith("seg-") && it.length() > 0 }
            ?.minByOrNull { it.name } ?: return false
        val init = File(work, "init.mp4").takeIf { it.isFile && it.length() > 0 }
        return frameFromConcat(init, first, dest)
    }

    private fun dashProbe(work: File, dest: File): Boolean {
        val files = work.listFiles() ?: return false
        val init = files.filter { it.isFile && it.name.contains("-init.") }.minByOrNull { it.name }
        val first = files.filter { it.isFile && it.extension.equals("m4s", true) && !it.name.contains("-init.") }
            .minByOrNull { it.name } ?: return false
        return frameFromConcat(init, first, dest)
    }

    private fun frameFromConcat(init: File?, first: File, dest: File): Boolean {
        val probe = File(dest.parentFile, dest.nameWithoutExtension + ".probe.mp4")
        return try {
            if (!concatProbe(init, first, probe)) return false
            frameFromFile(probe, dest)
        } finally {
            probe.delete()
        }
    }

    private fun frameFromFile(file: File, dest: File): Boolean {
        if (readFrame(file, dest)) return true
        val ext = file.extension.lowercase()
        if (ext in KNOWN_MEDIA || file.length() > PROBE_LIMIT) return false
        val alias = File(file.parentFile, file.name + ".probe.mp4")
        return try {
            file.copyTo(alias, overwrite = true)
            readFrame(alias, dest)
        } catch (_: Throwable) {
            false
        } finally {
            alias.delete()
        }
    }

    private fun readFrame(file: File, dest: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            writeThumb(retriever, dest)
        } catch (_: Throwable) {
            false
        } finally {
            retriever.release()
        }
    }

    private fun downloadImage(url: String, headers: Map<String, String>, dest: File): Boolean {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (key, value) -> header(key, value) }
        }.build()
        return try {
            HttpClientProvider.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false
                val bytes = response.body?.bytes() ?: return false
                if (bytes.size > MAX_POSTER_BYTES || !ImageSniff.isImage(response.header("Content-Type"), bytes)) return false
                dest.writeBytes(bytes)
                true
            }
        } catch (_: Throwable) {
            dest.delete()
            false
        }
    }

    private fun writeThumb(retriever: MediaMetadataRetriever, dest: File): Boolean {
        val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime(0)
            ?: return false
        val scaled = scale(frame, 320)
        val tmp = File(dest.path + ".tmp")
        val ok = runCatching {
            tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 70, it) }
        }.getOrDefault(false)
        if (scaled !== frame) scaled.recycle()
        frame.recycle()
        if (!ok) {
            tmp.delete()
            return false
        }
        if (dest.exists()) dest.delete()
        return tmp.renameTo(dest).also { if (!it) tmp.delete() }
    }

    private fun scale(source: Bitmap, maxWidth: Int): Bitmap {
        if (source.width <= maxWidth) return source
        val height = (maxWidth.toFloat() * source.height / source.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, maxWidth, height, true)
    }

    internal const val MIN_FRAME_BYTES = 48 * 1024L
    private const val MAX_POSTER_BYTES = 2 * 1024 * 1024
    private const val PROBE_LIMIT = 8L * 1024 * 1024
    private val KNOWN_MEDIA = setOf("mp4", "m4v", "webm", "mkv", "mov", "ts", "m4s", "mp3", "m4a", "aac")

    internal fun artifactFiles(downloads: File, id: String): List<File> {
        val files = buildList {
            listOf("mp4", "m4v", "webm", "mkv", "mov", "mp3", "m4a", "aac").forEach { ext ->
                add(File(downloads, "dl-$id.$ext"))
                add(File(downloads, "dl-$id.$ext.part"))
                add(File(downloads, "dl-$id.$ext.part.0"))
            }
            add(File(downloads, "hls-$id.mp4"))
            add(File(downloads, "dash-$id.mp4"))
            downloads.resolve("hls-$id").listFiles()?.let(::addAll)
            downloads.resolve("dash-$id").listFiles()?.let(::addAll)
        }
        return files.filter { it.isFile && it.length() >= MIN_FRAME_BYTES }
            .sortedWith(compareByDescending<File> { videoScore(it) }.thenByDescending { it.length() })
    }

    internal fun concatProbe(init: File?, first: File, out: File, limit: Long = PROBE_LIMIT): Boolean {
        if (!first.isFile || first.length() <= 0L) return false
        return try {
            out.outputStream().use { output ->
                var written = 0L
                fun copyCapped(file: File) {
                    if (!file.isFile || file.length() <= 0L || written >= limit) return
                    file.inputStream().use { input ->
                        val buf = ByteArray(32 * 1024)
                        while (written < limit) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), limit - written).toInt())
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            written += n
                        }
                    }
                }
                init?.let(::copyCapped)
                copyCapped(first)
            }
            out.exists() && out.length() > 0L
        } catch (_: Throwable) {
            out.delete()
            false
        }
    }

    private fun videoScore(file: File): Int {
        val name = file.name.lowercase()
        val ext = file.extension.lowercase()
        return when {
            ext in setOf("mp4", "m4v", "webm", "mkv", "mov", "m4s") -> 2
            name.contains("init") -> 1
            else -> 0
        }
    }
}
