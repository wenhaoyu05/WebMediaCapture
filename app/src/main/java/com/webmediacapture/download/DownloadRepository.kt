package com.webmediacapture.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.webmediacapture.database.DownloadDao
import com.webmediacapture.database.DownloadEntity
import com.webmediacapture.database.DownloadState
import com.webmediacapture.model.MediaCandidate
import com.webmediacapture.model.RequestContext

class DownloadRepository(
    private val context: Context,
    private val dao: DownloadDao,
) {
    suspend fun enqueue(candidate: MediaCandidate) {
        val entity = DownloadEntity(
            id = candidate.id,
            mediaUrl = candidate.mediaUrl,
            pageUrl = candidate.pageUrl,
            title = candidate.title,
            type = candidate.type.name,
            selectedFormatId = candidate.selectedFormatId,
            requestHeaders = HeaderStore.encode(candidate.requestContext),
            state = DownloadState.PREPARING,
        )
        dao.upsert(entity)
        DownloadContextVault.put(candidate.id, candidate.requestContext)
        schedule(entity, candidate.requestContext)
    }

    suspend fun pause(id: String) {
        dao.setState(id, DownloadState.PAUSED)
        WorkManager.getInstance(context).cancelUniqueWork(id)
    }

    suspend fun resume(id: String) {
        val entity = dao.get(id) ?: return
        if (entity.state == DownloadState.COMPLETED || entity.state == DownloadState.CANCELLED) return
        val requestContext = DownloadContextVault.get(id) ?: HeaderStore.decode(entity.requestHeaders)
        dao.setState(id, DownloadState.PENDING)
        schedule(entity, requestContext)
    }

    suspend fun cancel(id: String) {
        dao.setState(id, DownloadState.CANCELLED)
        DownloadContextVault.remove(id)
        WorkManager.getInstance(context).cancelUniqueWork(id)
    }

    suspend fun delete(id: String) {
        val entity = dao.get(id)
        WorkManager.getInstance(context).cancelUniqueWork(id)
        DownloadContextVault.remove(id)
        val downloads = java.io.File(context.getExternalFilesDir(null), "downloads")
        entity?.outputPath?.let { java.io.File(it).delete() }
        downloads.resolve("hls-$id").deleteRecursively()
        downloads.resolve("dash-$id").deleteRecursively()
        dao.delete(id)
    }

    private fun schedule(entity: DownloadEntity, requestContext: RequestContext) {
        val safeHeaders = requestContext.downloadHeaders().filterKeys {
            !it.equals("Cookie", true) && !it.equals("Authorization", true)
        }
        val data = Data.Builder()
            .putString(DownloadWorker.ID, entity.id)
            .putString(DownloadWorker.URL, entity.mediaUrl)
            .putString(DownloadWorker.PAGE_URL, entity.pageUrl)
            .putString(DownloadWorker.TYPE, entity.type)
            .putString(DownloadWorker.FORMAT_ID, entity.selectedFormatId)
            .putStringArray(DownloadWorker.HEADERS, safeHeaders.flatMap { listOf(it.key, it.value) }.toTypedArray())
            .build()
        val work = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(entity.id)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(entity.id, ExistingWorkPolicy.REPLACE, work)
    }
}
