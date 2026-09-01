package com.webmediacapture.download

import android.content.Context
import com.webmediacapture.util.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

interface SegmentMuxer {
    suspend fun merge(inputs: List<File>, output: File)
}

class ConcatMuxer : SegmentMuxer {
    override suspend fun merge(inputs: List<File>, output: File) = withContext(Dispatchers.IO) {
        output.outputStream().use { out ->
            inputs.forEach { input -> input.inputStream().use { it.copyTo(out) } }
        }
    }
}

class FfmpegMuxer(private val context: Context) : SegmentMuxer {
    override suspend fun merge(inputs: List<File>, output: File) = withContext(Dispatchers.IO) {
        if (inputs.isEmpty()) throw java.io.IOException("No media inputs to mux")
        if (inputs.size == 1 && inputs.first().extension.equals("mp4", true)) {
            inputs.first().copyTo(output, overwrite = true)
            return@withContext
        }
        val binary = ffmpegBinary()
        val listFile = File(output.parentFile, "${output.name}.concat.txt")
        val args = ffmpegArgs(inputs, output, listFile)
        val process = try {
            ProcessBuilder(listOf(binary.absolutePath) + args)
                .redirectErrorStream(true)
                .directory(output.parentFile)
                .start()
        } catch (error: Throwable) {
            SafeLog.w("FFMPEG", "Launch failed: ${error.message}")
            ConcatMuxer().merge(inputs, output)
            return@withContext
        }
        val log = process.inputStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        listFile.delete()
        if (code != 0 || !output.exists() || output.length() == 0L) {
            SafeLog.w("FFMPEG", "Mux failed code=$code")
            ConcatMuxer().merge(inputs, output)
            if (!output.exists() || output.length() == 0L) throw java.io.IOException("FFmpeg mux failed: ${log.take(400)}")
        }
    }

    suspend fun remuxToMp4(input: File, output: File): File = withContext(Dispatchers.IO) {
        if (!input.exists() || input.length() == 0L) throw java.io.IOException("Empty input")
        if (input.extension.equals("mp4", true)) {
            return@withContext com.webmediacapture.util.MediaTitles.moveMp4(input, output)
        }
        output.parentFile?.mkdirs()
        val binary = ffmpegBinary()
        val attempts = listOf(remuxArgs(input, output), tsRemuxArgs(input, output))
        var lastLog = ""
        for (args in attempts) {
            output.delete()
            val process = try {
                ProcessBuilder(listOf(binary.absolutePath) + args)
                    .redirectErrorStream(true)
                    .directory(output.parentFile)
                    .start()
            } catch (error: Throwable) {
                lastLog = error.message ?: error.javaClass.simpleName
                continue
            }
            lastLog = process.inputStream.bufferedReader().use { it.readText() }
            if (process.waitFor() == 0 && output.exists() && output.length() > 0L) {
                input.delete()
                return@withContext output
            }
        }
        throw java.io.IOException("FFmpeg remux failed: ${lastLog.take(400)}")
    }

    private fun ffmpegBinary(): File {
        val candidates = listOf(
            File(context.filesDir, "packages/ffmpeg/usr/bin/ffmpeg"),
            File(context.filesDir, "youtubedl-android/usr/bin/ffmpeg"),
            File(context.filesDir, "ffmpeg"),
            File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw java.io.IOException("FFmpeg binary is not initialized")
    }

    companion object {
        fun ffmpegArgs(inputs: List<File>, output: File, listFile: File): List<String> {
            if (inputs.size == 1) {
                return listOf("-y", "-i", inputs.first().absolutePath, "-c", "copy", "-bsf:a", "aac_adtstoasc", "-movflags", "+faststart", output.absolutePath)
            }
            listFile.writeText(inputs.joinToString("\n") { file ->
                val path = file.absolutePath.replace("\\", "/").replace("'", "'\\''")
                "file '$path'"
            } + "\n")
            return listOf(
                "-y", "-f", "concat", "-safe", "0", "-i", listFile.absolutePath,
                "-c", "copy", "-movflags", "+faststart", output.absolutePath,
            )
        }

        fun remuxArgs(input: File, output: File): List<String> =
            listOf("-y", "-i", input.absolutePath, "-c", "copy", "-movflags", "+faststart", output.absolutePath)

        fun tsRemuxArgs(input: File, output: File): List<String> =
            listOf("-y", "-i", input.absolutePath, "-c", "copy", "-bsf:a", "aac_adtstoasc", "-movflags", "+faststart", output.absolutePath)
    }
}
