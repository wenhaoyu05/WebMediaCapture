package com.webmediacapture.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.webmediacapture.R
import com.webmediacapture.WebMediaCaptureApp
import com.webmediacapture.database.DownloadState
import com.webmediacapture.util.MediaTitles
import com.webmediacapture.util.SafeLog
import java.io.File

class ConvertWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun getForegroundInfo(): ForegroundInfo = foreground()

    override suspend fun doWork(): Result {
        val id = inputData.getString(ID) ?: return Result.failure()
        val dao = (applicationContext as WebMediaCaptureApp).database.downloads()
        val entity = dao.get(id) ?: return Result.failure()
        val source = entity.outputPath?.let(::File)
        if (source == null || !source.exists()) {
            dao.setState(id, DownloadState.COMPLETED, applicationContext.getString(R.string.library_missing))
            return Result.failure()
        }
        if (!MediaTitles.needsMp4Convert(source.absolutePath)) return Result.success()
        val dest = MediaTitles.convertMp4Dest(source)
        runCatching { setForeground(foreground(entity.title ?: source.name)) }
        return try {
            val out = FfmpegMuxer(applicationContext).convertToMp4(source, dest)
            if (dao.get(id) == null) {
                if (out.absolutePath != source.absolutePath) out.delete()
                return Result.success()
            }
            dao.updateState(id, DownloadState.COMPLETED, out.length(), out.length(), out.absolutePath, null, 0, 100.0)
            Result.success()
        } catch (error: Throwable) {
            SafeLog.w("CONVERT", "Convert failed: ${error.message}")
            dest.delete()
            if (dao.get(id) != null) {
                dao.setState(id, DownloadState.COMPLETED, applicationContext.getString(R.string.library_convert_failed))
            }
            Result.failure()
        }
    }

    private fun foreground(title: String = applicationContext.getString(R.string.library_converting)): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, applicationContext.getString(R.string.download_notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(applicationContext.getString(R.string.library_converting))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        val notificationId = (id.hashCode() and 0x7FFFFFFF) xor 0x5A5A5A5A
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val ID = "id"
        private const val CHANNEL = "media-downloads"
        fun workName(id: String) = "convert-$id"
    }
}
