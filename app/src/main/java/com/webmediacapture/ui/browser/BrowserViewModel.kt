package com.webmediacapture.ui.browser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.webmediacapture.WebMediaCaptureApp
import com.webmediacapture.download.DownloadManager
import com.webmediacapture.download.DownloadRepository
import com.webmediacapture.extractor.ExtractorEngine
import com.webmediacapture.model.MediaCandidate
import com.webmediacapture.model.MediaType
import com.webmediacapture.model.MediaVariant
import com.webmediacapture.model.RequestContext
import com.webmediacapture.util.AppSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.URI

class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WebMediaCaptureApp
    val candidates: StateFlow<List<MediaCandidate>> = app.mediaRepository.candidates
    private val downloads = DownloadManager(DownloadRepository(app, app.database.downloads()))
    val downloadTasks = app.database.downloads().observeAll()
    val history = app.database.history().observeRecent(20)
    private val extractor by lazy { ExtractorEngine(app, app.detectorProbe) }
    private var analyzeJob: Job? = null

    fun startSession(id: String) {
        analyzeJob?.cancel()
        app.mediaRepository.startSession(id)
    }

    fun setPageTitle(title: String?) {
        app.mediaRepository.applyTitle(title)
    }

    fun download(candidate: MediaCandidate, variant: MediaVariant? = null) = viewModelScope.launch {
        if (candidate.type == MediaType.DRM_PROTECTED) return@launch
        val selected = selectVariant(candidate, variant ?: candidate.variants.maxWithOrNull(compareBy<MediaVariant> { it.height ?: 0 }.thenBy { it.bitrate ?: 0 }))
        val live = app.mediaRepository.candidates.value.firstOrNull { it.mediaUrl == selected.mediaUrl }
        val named = selected.copy(title = com.webmediacapture.util.MediaTitles.prefer(selected.title, live?.title))
        // #region agent log
        com.webmediacapture.util.AgentDebugLog.emit(
            "G",
            "BrowserViewModel.kt:download",
            "enqueue",
            mapOf(
                "type" to selected.type.name,
                "h" to (selected.height ?: 0),
                "dur" to (selected.durationSec ?: -1.0),
                "url" to com.webmediacapture.util.AgentDebugLog.safeUrl(selected.mediaUrl),
                "title" to (named.title ?: ""),
                "variants" to candidate.variants.size,
            ),
        )
        // #endregion
        downloads.enqueue(named)
    }

    fun pauseDownload(id: String) = viewModelScope.launch { downloads.pause(id) }
    fun resumeDownload(id: String) = viewModelScope.launch { downloads.resume(id) }
    fun cancelDownload(id: String) = viewModelScope.launch { downloads.cancel(id) }
    fun deleteDownload(id: String) = viewModelScope.launch { downloads.delete(id) }

    fun analyzePage(pageSessionId: String, pageUrl: String, requestContext: RequestContext) = viewModelScope.launch {
        runCatching { extractor.extractPage(pageSessionId, pageUrl, requestContext) }
            .onSuccess { candidate -> app.mediaRepository.add(candidate) }
    }

    fun maybeAnalyze(pageSessionId: String, pageUrl: String, requestContext: RequestContext) {
        analyzeJob?.cancel()
        if (!AppSettings.autoYtDlp(app) || isSearchHome(pageUrl)) return
        analyzeJob = viewModelScope.launch {
            delay(2_500)
            analyzePage(pageSessionId, pageUrl, requestContext)
        }
    }

    private fun isSearchHome(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return true
        return host.contains("google.") || host.contains("bing.") || host.contains("baidu.") ||
            host.contains("duckduckgo.") || host == "127.0.0.1" || host == "localhost"
    }

    private fun selectVariant(candidate: MediaCandidate, variant: MediaVariant?): MediaCandidate {
        if (variant == null) return candidate
        return if (candidate.type == MediaType.HLS) {
            candidate.copy(
                mediaUrl = variant.url,
                width = variant.width ?: candidate.width,
                height = variant.height ?: candidate.height,
                bitrate = variant.bitrate ?: candidate.bitrate,
                codecs = variant.codecs ?: candidate.codecs,
                variants = emptyList(),
            )
        } else {
            candidate.copy(
                selectedFormatId = variant.formatId,
                width = variant.width ?: candidate.width,
                height = variant.height ?: candidate.height,
                bitrate = variant.bitrate ?: candidate.bitrate,
                codecs = variant.codecs ?: candidate.codecs,
            )
        }
    }
}
