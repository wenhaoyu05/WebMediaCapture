package com.webmediacapture.repository

import com.webmediacapture.detector.AdMediaFilter
import com.webmediacapture.detector.CandidateDeduplicator
import com.webmediacapture.detector.PreviewMediaFilter
import com.webmediacapture.model.MediaCandidate
import com.webmediacapture.model.MediaRole
import com.webmediacapture.model.ObservedRequest
import com.webmediacapture.util.AgentDebugLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MediaRepository(private val deduplicator: CandidateDeduplicator = CandidateDeduplicator()) {
    private val mutableCandidates = MutableStateFlow<List<MediaCandidate>>(emptyList())
    val candidates: StateFlow<List<MediaCandidate>> = mutableCandidates.asStateFlow()
    private var currentSessionId: String? = null
    private val rejected = mutableSetOf<String>()
    private var pageTitle: String? = null
    private var pagePoster: String? = null

    @Synchronized
    fun startSession(id: String) {
        val previous = mutableCandidates.value.size
        currentSessionId = id
        rejected.clear()
        pageTitle = null
        pagePoster = null
        mutableCandidates.value = emptyList()
        // #region agent log
        AgentDebugLog.emit("A", "MediaRepository.kt:startSession", "session", mapOf("id" to id.take(8), "had" to previous))
        // #endregion
    }

    @Synchronized
    fun reject(request: ObservedRequest) {
        if (request.pageSessionId != currentSessionId) return
        rejected += deduplicator.fingerprint(request.url)
        mutableCandidates.value = publish(mutableCandidates.value.filterNot(::blocked))
    }

    @Synchronized
    fun applyTitle(title: String?) {
        val cleaned = com.webmediacapture.util.MediaTitles.clean(title) ?: return
        pageTitle = com.webmediacapture.util.MediaTitles.prefer(pageTitle, cleaned)
        val current = pageTitle ?: return
        mutableCandidates.value = mutableCandidates.value.map { item ->
            item.copy(title = com.webmediacapture.util.MediaTitles.prefer(item.title, current))
        }
    }

    @Synchronized
    fun applyPoster(url: String?) {
        val poster = url?.trim()?.takeIf { it.startsWith("http") } ?: return
        pagePoster = poster
        mutableCandidates.value = mutableCandidates.value.map { item ->
            if (item.thumbnailUrl == null) item.copy(thumbnailUrl = poster) else item
        }
    }

    @Synchronized
    fun add(candidate: MediaCandidate) {
        val named = candidate.copy(
            title = if (candidate.title.isNullOrBlank()) {
                pageTitle
            } else {
                com.webmediacapture.util.MediaTitles.prefer(candidate.title, pageTitle)
            },
            thumbnailUrl = candidate.thumbnailUrl ?: pagePoster,
        )
        if (named.pageSessionId != currentSessionId) {
            // #region agent log
            AgentDebugLog.emit(
                "A",
                "MediaRepository.kt:add",
                "skip-session",
                mapOf("url" to AgentDebugLog.safeUrl(candidate.mediaUrl), "inSess" to candidate.pageSessionId.take(8), "cur" to currentSessionId?.take(8)),
            )
            // #endregion
            return
        }
        if (named.role == MediaRole.AD || named.role == MediaRole.OVERLAY || blocked(named)) {
            if (PreviewMediaFilter.isPreviewUrl(named.mediaUrl)) {
                // #region agent log
                AgentDebugLog.emit(
                    "K1",
                    "MediaRepository.kt:add",
                    "skip-preview",
                    mapOf("url" to AgentDebugLog.safeUrl(candidate.mediaUrl)),
                )
                // #endregion
            }
            return
        }
        mutableCandidates.value = publish(deduplicator.merge(mutableCandidates.value, named))
        // #region agent log
        AgentDebugLog.emit(
            "C",
            "MediaRepository.kt:add",
            "after",
            mapOf(
                "inUrl" to AgentDebugLog.safeUrl(candidate.mediaUrl),
                "inH" to (candidate.height ?: 0),
                "inDur" to (candidate.durationSec ?: -1.0),
                "kept" to mutableCandidates.value.size,
                "keptH" to (mutableCandidates.value.firstOrNull()?.height ?: 0),
                "keptDur" to (mutableCandidates.value.firstOrNull()?.durationSec ?: -1.0),
                "keptType" to (mutableCandidates.value.firstOrNull()?.type?.name ?: ""),
                "keptUrls" to mutableCandidates.value.joinToString(",") { AgentDebugLog.safeUrl(it.mediaUrl) },
            ),
        )
        // #endregion
    }

    private fun publish(items: List<MediaCandidate>): List<MediaCandidate> =
        deduplicator.keepPrimary(items.filterNot(::blocked))

    private fun blocked(candidate: MediaCandidate): Boolean =
        AdMediaFilter.isAdUrl(candidate.mediaUrl) ||
            AdMediaFilter.isAdReferer(candidate.requestContext.value("Referer")) ||
            PreviewMediaFilter.isPreviewUrl(candidate.mediaUrl) ||
            rejected.contains(deduplicator.fingerprint(candidate.mediaUrl)) ||
            candidate.variants.any { rejected.contains(deduplicator.fingerprint(it.url)) }
}
