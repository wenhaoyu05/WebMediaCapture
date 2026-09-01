package com.webmediacapture

import android.app.Application
import com.webmediacapture.browser.RequestObserver
import com.webmediacapture.database.AppDatabase
import com.webmediacapture.detector.CandidateDeduplicator
import com.webmediacapture.detector.MediaDetector
import com.webmediacapture.model.MediaRole
import com.webmediacapture.network.HttpClientProvider
import com.webmediacapture.network.NetworkProbe
import com.webmediacapture.repository.MediaRepository
import com.webmediacapture.util.SafeLog
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class WebMediaCaptureApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val requestObserver = RequestObserver()
    val mediaRepository = MediaRepository(CandidateDeduplicator())
    val database by lazy { AppDatabase.create(this) }
    val detectorProbe by lazy { NetworkProbe(HttpClientProvider.client) }
    val detector by lazy { MediaDetector(detectorProbe) }

    override fun onCreate() {
        super.onCreate()
        HttpClientProvider.install(this)
        requestObserver.requests.onEach { request ->
            if (request.role == MediaRole.AD || request.role == MediaRole.OVERLAY) {
                mediaRepository.reject(request)
            } else {
                detector.detect(request)?.let(mediaRepository::add)
            }
        }.launchIn(appScope)
        appScope.launch(Dispatchers.IO) {
            runCatching {
                YoutubeDL.getInstance().init(this@WebMediaCaptureApp)
                FFmpeg.getInstance().init(this@WebMediaCaptureApp)
            }.onFailure { SafeLog.w("YT_DLP", "Native extractor initialization failed: ${it.message}") }
        }
    }
}
