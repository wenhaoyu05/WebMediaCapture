package com.webmediacapture.repository

import com.webmediacapture.detector.CandidateDeduplicator
import com.webmediacapture.model.DetectionSource
import com.webmediacapture.model.MediaCandidate
import com.webmediacapture.model.MediaRole
import com.webmediacapture.model.MediaType
import com.webmediacapture.model.ObservedRequest
import com.webmediacapture.model.RequestContext
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaRepositoryTest {
    private val repo = MediaRepository(CandidateDeduplicator())

    @Test
    fun keepsMainVideoAndDropsAdsAndOverlays() {
        repo.startSession("s1")
        repo.add(item("https://cdn.test/corner.mp4", MediaRole.UNKNOWN, 180))
        repo.add(item("https://cdn.test/main.mp4", MediaRole.MAIN, 720))
        repo.add(item("https://cdn.test/pip.mp4", MediaRole.OVERLAY, 240))
        repo.reject(
            ObservedRequest(
                url = "https://cdn.test/pip.mp4",
                method = "GET",
                requestContext = RequestContext(),
                pageUrl = "https://page.test",
                pageSessionId = "s1",
                role = MediaRole.OVERLAY,
            ),
        )
        repo.add(item("https://pagead2.googlesyndication.com/pagead/clip.mp4", MediaRole.UNKNOWN, 1080))
        assertEquals(
            listOf("https://cdn.test/main.mp4", "https://cdn.test/corner.mp4"),
            repo.candidates.value.map { it.mediaUrl },
        )
    }

    @Test
    fun keepsAllVideosSortedByDurationDesc() {
        repo.startSession("s1")
        repo.add(item("https://cdn.test/short.mp4", MediaRole.UNKNOWN, 360).copy(durationSec = 10.0))
        repo.add(item("https://cdn.test/medium.mp4", MediaRole.UNKNOWN, 480).copy(durationSec = 45.0))
        repo.add(item("https://cdn.test/feature.mp4", MediaRole.UNKNOWN, 720).copy(durationSec = 96.0))
        assertEquals(
            listOf(
                "https://cdn.test/feature.mp4",
                "https://cdn.test/medium.mp4",
                "https://cdn.test/short.mp4",
            ),
            repo.candidates.value.map { it.mediaUrl },
        )
    }

    @Test
    fun keepsUnknownDurationAndLongClips() {
        repo.startSession("s1")
        repo.add(item("https://cdn.test/unknown.mp4", MediaRole.UNKNOWN, 480))
        assertEquals(listOf("https://cdn.test/unknown.mp4"), repo.candidates.value.map { it.mediaUrl })
        repo.startSession("s2")
        repo.add(item("https://cdn.test/feature.mp4", MediaRole.UNKNOWN, 480).copy(pageSessionId = "s2", durationSec = 90.0))
        assertEquals(listOf("https://cdn.test/feature.mp4"), repo.candidates.value.map { it.mediaUrl })
    }

    @Test
    fun hidesLibraryPreviewsUntilRealHlsArrives() {
        repo.startSession("s1")
        repo.add(item("https://z6v2p9a8.bkcdn.net/library/914186/clip.mp4", MediaRole.UNKNOWN, 0))
        assertEquals(emptyList<String>(), repo.candidates.value.map { it.mediaUrl })
        repo.add(
            MediaCandidate(
                pageSessionId = "s1",
                pageUrl = "https://page.test",
                mediaUrl = "https://cdn.acek.test/hls/master.m3u8",
                type = MediaType.HLS,
                height = 1080,
                durationSec = 4819.0,
                source = DetectionSource.WEBVIEW_NETWORK,
                role = MediaRole.UNKNOWN,
            ),
        )
        assertEquals(listOf("https://cdn.acek.test/hls/master.m3u8"), repo.candidates.value.map { it.mediaUrl })
    }

    @Test
    fun appliesPageTitleToCapturedItems() {
        repo.startSession("s1")
        repo.add(item("https://cdn.test/master.m3u8", MediaRole.MAIN, 720).copy(type = MediaType.HLS))
        repo.applyTitle("网页里的视频名")
        assertEquals("网页里的视频名", repo.candidates.value.single().title)
    }

    @Test
    fun applyPosterFillsMissingThumbnails() {
        repo.startSession("s1")
        repo.add(item("https://cdn.test/main.mp4", MediaRole.MAIN, 720))
        repo.applyPoster("https://cdn.test/cover.jpg")
        assertEquals("https://cdn.test/cover.jpg", repo.candidates.value.single().thumbnailUrl)
    }

    private fun item(url: String, role: MediaRole, height: Int) = MediaCandidate(
        pageSessionId = "s1",
        pageUrl = "https://page.test",
        mediaUrl = url,
        type = MediaType.DIRECT,
        height = height,
        source = DetectionSource.DOM,
        role = role,
    )
}
