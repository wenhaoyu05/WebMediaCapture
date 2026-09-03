package com.webmediacapture.ui.browser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.webmediacapture.R
import com.webmediacapture.WebMediaCaptureApp
import com.webmediacapture.download.DownloadManager
import com.webmediacapture.download.DownloadRepository
import com.webmediacapture.extractor.DouyinExtractor
import com.webmediacapture.extractor.DouyinLinks
import com.webmediacapture.extractor.ExtractorEngine
import com.webmediacapture.library.LibraryMedia
import com.webmediacapture.model.DetectionSource
import com.webmediacapture.model.MediaCandidate
import com.webmediacapture.model.MediaType
import com.webmediacapture.model.MediaVariant
import com.webmediacapture.model.RequestContext
import com.webmediacapture.util.AppSettings
import com.webmediacapture.util.MediaTitles
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

class BrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as WebMediaCaptureApp
    val candidates: StateFlow<List<MediaCandidate>> = app.mediaRepository.candidates
    private val downloads = DownloadManager(DownloadRepository(app, app.database.downloads()))
    val downloadTasks = app.database.downloads().observeAll()
    val history = app.database.history().observeRecent(20)
    private val convertingFrom = mutableMapOf<String, String?>()
    private val _converting = MutableStateFlow<Set<String>>(emptySet())
    val converting: StateFlow<Set<String>> = _converting
    private val extractor by lazy { ExtractorEngine(app, app.detectorProbe) }
    private var analyzeJob: Job? = null
    private var douyinPageJob: Job? = null
    private val douyinArmed = AtomicBoolean(false)
    private val _douyinQueued = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val douyinQueued: SharedFlow<Unit> = _douyinQueued

    init {
        viewModelScope.launch {
            downloadTasks.collect { list ->
                _converting.update { ids ->
                    ids.filter { id ->
                        val task = list.find { it.id == id } ?: return@filter false
                        if (!task.error.isNullOrBlank()) return@filter false
                        convertingFrom[id] == null || task.outputPath == convertingFrom[id]
                    }.toSet()
                }
            }
        }
    }

    fun startSession(id: String) {
        analyzeJob?.cancel()
        app.mediaRepository.startSession(id)
    }

    fun setPageTitle(title: String?) {
        app.mediaRepository.applyTitle(title)
    }

    fun setPagePoster(url: String?) {
        app.mediaRepository.applyPoster(url)
    }

    fun download(candidate: MediaCandidate, variant: MediaVariant? = null) = viewModelScope.launch {
        if (candidate.type == MediaType.DRM_PROTECTED) return@launch
        val selected = selectVariant(candidate, variant ?: candidate.variants.maxWithOrNull(compareBy<MediaVariant> { it.height ?: 0 }.thenBy { it.bitrate ?: 0 }))
        val live = app.mediaRepository.candidates.value.firstOrNull { it.mediaUrl == selected.mediaUrl }
        val named = selected.copy(
            title = MediaTitles.prefer(selected.title, live?.title),
            thumbnailUrl = selected.thumbnailUrl ?: live?.thumbnailUrl,
        )
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
        withContext(Dispatchers.IO) { LibraryMedia.capture(app, named) }
    }

    fun armDouyinCapture() {
        douyinPageJob?.cancel()
        douyinArmed.set(true)
    }

    fun disarmDouyinCapture() {
        douyinArmed.set(false)
        douyinPageJob?.cancel()
    }

    fun isDouyinArmed(): Boolean = douyinArmed.get()

    fun offerDouyinMedia(candidate: MediaCandidate): Boolean {
        if (candidate.type == MediaType.DRM_PROTECTED || !isDouyinCapture(candidate)) return false
        if (!douyinArmed.compareAndSet(true, false)) return false
        douyinPageJob?.cancel()
        download(candidate)
        _douyinQueued.tryEmit(Unit)
        return true
    }

    fun captureDouyinFromPage(pageSessionId: String, pageUrl: String, requestContext: RequestContext, payload: String?) {
        if (!douyinArmed.get()) return
        douyinPageJob?.cancel()
        douyinPageJob = viewModelScope.launch {
            val extractUrl = DouyinLinks.pageUrlForExtract(pageUrl)
            val headers = requestContext.headers.toMutableMap()
            headers.putIfAbsent("Referer", "https://www.douyin.com/")
            headers.putIfAbsent("Accept", "*/*")
            val context = RequestContext(headers)
            payload?.let { DouyinExtractor.fromPagePayload(it) }?.let { info ->
                val titled = if (info.title.isNullOrBlank()) info.copy(title = app.getString(R.string.douyin_fallback_title)) else info
                if (offerDouyinMedia(DouyinExtractor.candidate(titled, pageSessionId, extractUrl, context))) return@launch
            }
            runCatching {
                withContext(Dispatchers.IO) { DouyinExtractor().resolve(extractUrl, pageSessionId, context) }
            }.onSuccess { if (offerDouyinMedia(it)) return@launch }
            runCatching { extractor.extractPage(pageSessionId, extractUrl, context) }
                .onSuccess { candidate ->
                    app.mediaRepository.add(candidate)
                    offerDouyinMedia(candidate)
                }
        }
    }

    fun pauseDownload(id: String) = viewModelScope.launch { downloads.pause(id) }
    fun resumeDownload(id: String) = viewModelScope.launch { downloads.resume(id) }
    fun cancelDownload(id: String) = viewModelScope.launch { downloads.cancel(id) }
    fun deleteDownload(id: String) = viewModelScope.launch { downloads.delete(id) }
    fun renameDownload(id: String, title: String) = viewModelScope.launch { downloads.rename(id, title) }
    fun convertToMp4(id: String, fromPath: String?) = viewModelScope.launch {
        if (id in _converting.value) return@launch
        convertingFrom[id] = fromPath
        _converting.update { it + id }
        downloads.convertToMp4(id)
    }

    fun analyzePage(pageSessionId: String, pageUrl: String, requestContext: RequestContext) = viewModelScope.launch {
        runCatching { extractor.extractPage(pageSessionId, pageUrl, requestContext) }
            .onSuccess { candidate -> app.mediaRepository.add(candidate) }
    }

    fun maybeAnalyze(pageSessionId: String, pageUrl: String, requestContext: RequestContext) {
        analyzeJob?.cancel()
        if (douyinArmed.get() || !AppSettings.autoYtDlp(app) || isSearchHome(pageUrl)) return
        analyzeJob = viewModelScope.launch {
            delay(2_500)
            analyzePage(pageSessionId, pageUrl, requestContext)
        }
    }

    private fun isDouyinCapture(candidate: MediaCandidate): Boolean {
        if (DouyinLinks.isMediaUrl(candidate.mediaUrl)) return true
        if (candidate.source == DetectionSource.YT_DLP && candidate.variants.isNotEmpty()) return true
        if (candidate.type == MediaType.DIRECT || candidate.type == MediaType.HLS) {
            val duration = candidate.durationSec ?: 0.0
            return duration >= 3 || (candidate.height ?: 0) >= 360
        }
        return false
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
