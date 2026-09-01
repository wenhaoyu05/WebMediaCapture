package com.webmediacapture.download

import com.webmediacapture.model.MediaCandidate

/** App-owned queue facade; it deliberately does not use Android's DownloadManager. */
class DownloadManager(private val repository: DownloadRepository) {
    suspend fun enqueue(candidate: MediaCandidate) = repository.enqueue(candidate)
    suspend fun pause(id: String) = repository.pause(id)
    suspend fun resume(id: String) = repository.resume(id)
    suspend fun cancel(id: String) = repository.cancel(id)
    suspend fun delete(id: String) = repository.delete(id)
}
