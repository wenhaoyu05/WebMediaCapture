package com.webmediacapture.library

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.webmediacapture.util.LibraryFiles
import com.webmediacapture.util.MediaTitles
import java.io.File

object LibraryExport {
    fun copyToPublic(context: Context, file: File, title: String?): Uri? {
        if (!file.exists()) return null
        val mime = LibraryFiles.mime(file.name)
        val name = LibraryFiles.displayName(file.absolutePath, title)
        return if (Build.VERSION.SDK_INT >= 29) copyMediaStore(context, file, mime, name) else copyLegacy(context, file, mime, name)
    }

    @RequiresApi(29)
    private fun copyMediaStore(context: Context, file: File, mime: String, name: String): Uri? {
        val audio = mime.startsWith("audio/")
        val collection = if (audio) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                if (audio) "${Environment.DIRECTORY_MUSIC}/Capture" else "${Environment.DIRECTORY_MOVIES}/Capture",
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: throw java.io.IOException("Cannot open export stream")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (_: Throwable) {
            resolver.delete(uri, null, null)
            null
        }
    }

    private fun copyLegacy(context: Context, file: File, mime: String, name: String): Uri? {
        val publicType = if (mime.startsWith("audio/")) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES
        val dir = File(Environment.getExternalStoragePublicDirectory(publicType), "Capture")
        if (!dir.exists() && !dir.mkdirs()) return null
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', file.extension)
        val dest = MediaTitles.uniqueFile(dir, stem, ext)
        return try {
            file.copyTo(dest, overwrite = false)
            MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf(mime), null)
            Uri.fromFile(dest)
        } catch (_: Throwable) {
            dest.delete()
            null
        }
    }
}
