package com.webmediacapture.util

import java.io.File
import java.net.URI

object MediaTitles {
    private val generic = setOf(
        "capture", "home", "youtube", "untitled", "null", "undefined", "new tab", "about:blank",
    )
    private val fileExt = setOf("m3u8", "mpd", "mp4", "m4s", "m4v", "ts", "webm", "mkv", "mov", "mp3", "m4a", "aac")

    fun clean(raw: String?): String? {
        val value = raw?.replace('\u00a0', ' ')?.trim()?.replace(Regex("\\s+"), " ").orEmpty()
        if (value.length < 2) return null
        if (value.lowercase() in generic) return null
        return value.take(180)
    }

    fun prefer(current: String?, incoming: String?): String? {
        val a = clean(current)
        val b = clean(incoming)
        if (a == null) return b
        if (b == null) return a
        if (looksLikeFile(a) && !looksLikeFile(b)) return b
        if (looksLikeFile(b) && !looksLikeFile(a)) return a
        if (b.contains(a) && b.length > a.length) return b
        if (a.contains(b) && a.length > b.length) return a
        return if (b.length > a.length) b else a
    }

    fun looksLikeFile(value: String): Boolean {
        val name = value.substringAfterLast('/').substringBefore('?').substringBefore('#')
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in fileExt
    }

    fun fileStem(title: String?, pageUrl: String, id: String): String {
        clean(title)?.let { return sanitize(it) }
        val slug = runCatching { URI(pageUrl).path.trim('/').substringAfterLast('/') }.getOrNull()
        if (!slug.isNullOrBlank() && !looksLikeFile(slug)) return sanitize(slug)
        return "video-${id.take(8)}"
    }

    fun sanitize(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F\\x7F]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim('.', ' ', '_')
            .take(80)
            .ifBlank { "video" }

    fun needsMp4Convert(path: String?): Boolean {
        val name = path?.substringAfterLast('/') ?: return false
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty() || ext == name.lowercase()) return true
        if (ext == "mp4") return false
        return ext !in setOf("mp3", "m4a", "aac", "ogg", "wav", "flac")
    }

    fun convertMp4Dest(source: File): File {
        val dir = source.parentFile ?: return File("${source.path}.mp4")
        val dest = uniqueMp4(dir, source.nameWithoutExtension, source)
        val same = runCatching { dest.canonicalFile == source.canonicalFile }.getOrDefault(dest.absolutePath == source.absolutePath)
        return if (same) uniqueMp4(dir, "${source.nameWithoutExtension}-mp4") else dest
    }

    fun uniqueMp4(dir: File, stem: String, ignore: File? = null): File =
        uniqueFile(dir, stem, "mp4", ignore)

    fun uniqueFile(dir: File, stem: String, ext: String, ignore: File? = null): File {
        val suffix = ext.trimStart('.').lowercase().let { if (it.isEmpty()) "" else ".$it" }
        var index = 2
        var file = File(dir, "$stem$suffix")
        val skip = runCatching { ignore?.canonicalFile }.getOrNull()
        while (file.exists() && runCatching { file.canonicalFile }.getOrNull() != skip) {
            file = File(dir, "$stem-$index$suffix")
            index += 1
        }
        return file
    }

    fun renameKeepingExt(source: File, title: String): File {
        val dir = source.parentFile ?: return source
        val stem = sanitize(title).ifBlank { source.nameWithoutExtension }
        val dest = uniqueFile(dir, stem, source.extension, source)
        return moveMp4(source, dest)
    }

    fun moveMp4(source: File, dest: File): File {
        val sourceCanon = runCatching { source.canonicalFile }.getOrDefault(source)
        val destCanon = runCatching { dest.canonicalFile }.getOrDefault(dest)
        if (sourceCanon == destCanon) return source
        if (source.renameTo(dest)) return dest
        dest.parentFile?.mkdirs()
        source.copyTo(dest, overwrite = true)
        source.delete()
        return dest
    }
}
