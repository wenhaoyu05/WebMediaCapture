package com.webmediacapture.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.webmediacapture.R
import com.webmediacapture.WebMediaCaptureApp
import com.webmediacapture.database.DownloadState
import com.webmediacapture.model.MediaType
import com.webmediacapture.model.RequestContext
import com.webmediacapture.network.CookieBridge
import com.webmediacapture.network.HeaderManager
import com.webmediacapture.network.HttpClientProvider
import com.webmediacapture.util.ByteFormat
import com.webmediacapture.util.MediaTitles
import com.webmediacapture.util.SafeLog
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class DownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val app = context.applicationContext as WebMediaCaptureApp
    private val dao = app.database.downloads()
    private var lastBytes = 0L
    private var lastAt = System.currentTimeMillis()
    private var speedBps = 0L

    private val lastUiAt = AtomicLong(0L)

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val id = inputData.getString(ID).orEmpty()
        return foreground(id, getString(R.string.download_preparing), 0.0, getString(R.string.download_preparing))
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString(ID) ?: return Result.failure()
        val url = inputData.getString(URL) ?: return Result.failure()
        val type = runCatching { MediaType.valueOf(inputData.getString(TYPE).orEmpty()) }.getOrDefault(MediaType.UNKNOWN)
        val formatId = inputData.getString(FORMAT_ID)
        val entity = dao.get(id) ?: return Result.failure()
        if (entity.state == DownloadState.PAUSED || entity.state == DownloadState.CANCELLED) return Result.success()
        val title = entity.title ?: getString(R.string.app_name)
        dao.updateState(id, DownloadState.DOWNLOADING, entity.bytesDownloaded, entity.totalBytes, null, null, 0, entity.progressPercent)
        try {
            setForeground(foreground(id, title, 0.0, getString(R.string.download_preparing)))
        } catch (error: Throwable) {
            SafeLog.w("DL", "Foreground start failed: ${error.message}")
        }
        // #region agent log
        com.webmediacapture.util.AgentDebugLog.emit(
            "G",
            "DownloadWorker.kt:doWork",
            "start",
            mapOf("id" to id.take(8), "type" to type.name, "url" to com.webmediacapture.util.AgentDebugLog.safeUrl(url)),
        )
        // #endregion
        val requestContext = buildContext(id, url)
        return try {
            val downloads = File(applicationContext.getExternalFilesDir(null), "downloads").apply { mkdirs() }
            when (type) {
                MediaType.DIRECT, MediaType.AUDIO -> {
                    val destination = File(downloads, "dl-$id${extension(url, type)}")
                    val result = DirectDownloader(HttpClientProvider.downloadClient).download(url, destination, requestContext) { bytes, total ->
                        publish(id, destination.name, DownloadState.DOWNLOADING, bytes, total)
                    }
                    val file = finalizeOutput(result.file, entity, type)
                    dao.updateState(id, DownloadState.COMPLETED, file.length(), result.total ?: file.length(), file.absolutePath, null, 0, 100.0)
                }
                MediaType.DRM_PROTECTED -> throw IllegalArgumentException("DRM protected media is not downloadable")
                MediaType.HLS -> {
                    val raw = HlsDownloader(applicationContext).download(
                        id, url, downloads, requestContext, formatId, entity.pageUrl, ::streamProgress,
                    )
                    val file = finalizeOutput(raw, entity, type)
                    dao.updateState(id, DownloadState.COMPLETED, file.length(), file.length(), file.absolutePath, null, 0, 100.0)
                }
                MediaType.DASH -> {
                    val raw = DashDownloader(applicationContext).download(id, url, downloads, requestContext, formatId, ::streamProgress)
                    val file = finalizeOutput(raw, entity, type)
                    dao.updateState(id, DownloadState.COMPLETED, file.length(), file.length(), file.absolutePath, null, 0, 100.0)
                }
                else -> {
                    val raw = YtDlpEngine(applicationContext).download(id, url, downloads, requestContext, formatId) { update -> streamProgress(update) }
                    val file = finalizeOutput(raw, entity, type)
                    dao.updateState(id, DownloadState.COMPLETED, file.length(), file.length(), file.absolutePath, null, 0, 100.0)
                }
            }
            DownloadContextVault.remove(id)
            Result.success()
        } catch (error: Throwable) {
            // #region agent log
            com.webmediacapture.util.AgentDebugLog.emit(
                "G",
                "DownloadWorker.kt:doWork",
                "error",
                mapOf("id" to id.take(8), "type" to type.name, "err" to (error.javaClass.simpleName + ":" + (error.message ?: "")).take(160)),
            )
            // #endregion
            if (isStopped) {
                runCatching { cancelStream(type, id) }
                val latest = dao.get(id)
                if (latest?.state != DownloadState.PAUSED) {
                    dao.updateState(id, DownloadState.CANCELLED, latest?.bytesDownloaded ?: entity.bytesDownloaded, latest?.totalBytes ?: entity.totalBytes, null, null, 0, latest?.progressPercent ?: entity.progressPercent)
                }
                Result.success()
            } else {
                val message = error.message ?: error.javaClass.simpleName
                val retryable = type == MediaType.DIRECT || type == MediaType.AUDIO
                if (retryable && runAttemptCount < MAX_RETRIES) {
                    dao.setState(id, DownloadState.PENDING, message)
                    Result.retry()
                } else {
                    dao.updateState(id, DownloadState.FAILED, entity.bytesDownloaded, entity.totalBytes, null, message, 0, entity.progressPercent)
                    Result.failure()
                }
            }
        }
    }

    private suspend fun streamProgress(update: YtDlpEngine.Progress) {
        val merging = update.line.contains("[Merger]", true) || update.line.contains("ffmpeg", true)
        val id = inputData.getString(ID).orEmpty()
        val label = if (merging) getString(R.string.download_merging) else getString(R.string.download_stream)
        publish(id, label, if (merging) DownloadState.MERGING else DownloadState.DOWNLOADING, update.bytes, update.total, update.percent, force = merging)
    }

    private suspend fun publish(
        id: String,
        title: String,
        state: DownloadState,
        bytes: Long,
        total: Long?,
        percentOverride: Double? = null,
        force: Boolean = false,
    ) {
        updateSpeed(bytes)
        val now = System.currentTimeMillis()
        val prev = lastUiAt.get()
        if (!force && now - prev < 250) return
        if (!force && !lastUiAt.compareAndSet(prev, now)) return
        lastUiAt.set(now)
        val percent = percentOverride ?: percent(bytes, total)
        dao.updateState(id, state, bytes, total, null, null, speedBps, percent)
        val name = title.ifBlank { getString(R.string.app_name) }
        runCatching { setForeground(foreground(id, name, percent, progressText(name, bytes, total, percent))) }
    }

    private fun cancelStream(type: MediaType, id: String) = when (type) {
        MediaType.HLS -> HlsDownloader(applicationContext).cancel(id)
        MediaType.DASH -> DashDownloader(applicationContext).cancel(id)
        else -> YtDlpEngine(applicationContext).cancel(id)
    }

    private fun buildContext(id: String, url: String): RequestContext {
        val pairs = inputData.getStringArray(HEADERS).orEmpty().toList().chunked(2).mapNotNull {
            if (it.size == 2) it[0] to it[1] else null
        }.toMap().toMutableMap()
        DownloadContextVault.get(id)?.headers?.let(pairs::putAll)
        CookieBridge().cookiesFor(url)?.let { pairs["Cookie"] = it }
        inputData.getString(PAGE_URL)?.let { page ->
            pairs.putIfAbsent("Referer", page)
        }
        return RequestContext(HeaderManager.downloadHeaders(RequestContext(pairs)))
    }

    private fun updateSpeed(bytes: Long) {
        val now = System.currentTimeMillis()
        val elapsed = (now - lastAt).coerceAtLeast(1L)
        if (elapsed >= 500) {
            speedBps = ((bytes - lastBytes) * 1000L) / elapsed
            lastBytes = bytes
            lastAt = now
        }
    }

    private fun progressText(name: String, bytes: Long, total: Long?, percent: Double): String {
        val speed = ByteFormat.format(speedBps) + "/s"
        val size = if (total != null && total > 0) {
            "${ByteFormat.format(bytes)} / ${ByteFormat.format(total)}"
        } else {
            ByteFormat.format(bytes)
        }
        return "$name · $size · $speed · ${"%.2f".format(Locale.US, percent)}%"
    }

    private fun foreground(id: String, title: String, percent: Double, text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.download_notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(10_000, (percent * 100).toInt().coerceIn(0, 10_000), percent <= 0.0)
            .addAction(notificationAction(id, DownloadActionReceiver.ACTION_PAUSE, R.string.download_pause, 1))
            .addAction(notificationAction(id, DownloadActionReceiver.ACTION_CANCEL, R.string.download_cancel, 2))
            .build()
        val notificationId = id.hashCode() and 0x7FFFFFFF
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun notificationAction(id: String, action: String, label: Int, requestCodeOffset: Int): NotificationCompat.Action {
        val intent = Intent(applicationContext, DownloadActionReceiver::class.java)
            .setAction(action)
            .putExtra(ID, id)
        val pendingIntent = PendingIntentCompat.getBroadcast(
            applicationContext,
            id.hashCode() + requestCodeOffset,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            false,
        ) ?: throw IllegalStateException("Cannot create download action")
        return NotificationCompat.Action.Builder(0, getString(label), pendingIntent).build()
    }

    private fun getString(id: Int) = applicationContext.getString(id)

    private suspend fun finalizeOutput(source: File, entity: com.webmediacapture.database.DownloadEntity, type: MediaType): File {
        return try {
            val dir = source.parentFile ?: return source
            val stem = MediaTitles.fileStem(entity.title, entity.pageUrl, entity.id)
            if (type == MediaType.AUDIO) {
                val ext = source.extension.takeIf { it.isNotBlank() } ?: "m4a"
                val dest = File(dir, "$stem.$ext")
                return if (sameFile(source, dest)) source else MediaTitles.moveMp4(source, dest)
            }
            val dest = MediaTitles.uniqueMp4(dir, stem, source)
            if (source.extension.equals("mp4", true)) {
                MediaTitles.moveMp4(source, dest)
            } else {
                runCatching { FfmpegMuxer(applicationContext).remuxToMp4(source, dest) }.getOrElse { source }
            }
        } catch (error: Throwable) {
            SafeLog.w("DL", "finalize failed: ${error.message}")
            source
        }
    }

    private fun sameFile(a: File, b: File) =
        runCatching { a.canonicalFile == b.canonicalFile }.getOrDefault(a.absolutePath == b.absolutePath)

    private fun percent(bytes: Long, total: Long?) =
        if (total == null || total <= 0) 0.0 else (bytes * 100.0 / total).coerceIn(0.0, 100.0)
    private fun extension(url: String, type: MediaType): String {
        val ext = runCatching { java.net.URI(url).path.substringAfterLast('.', "") }.getOrDefault("").lowercase()
        return if (ext in setOf("mp4", "m4v", "webm", "mov", "mkv", "mp3", "m4a", "aac", "ogg")) ".$ext" else if (type == MediaType.AUDIO) ".m4a" else ".mp4"
    }

    companion object {
        const val ID = "id"
        const val URL = "url"
        const val PAGE_URL = "page_url"
        const val TYPE = "type"
        const val FORMAT_ID = "format_id"
        const val HEADERS = "headers"
        private const val CHANNEL = "media-downloads"
        private const val MAX_RETRIES = 2
    }
}
