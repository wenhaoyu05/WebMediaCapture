package com.webmediacapture.browser

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class PageSession {
    data class State(val id: String, val url: String, val title: String? = null)
    private val current = AtomicReference(State(UUID.randomUUID().toString(), "about:blank"))

    fun start(url: String): State = State(UUID.randomUUID().toString(), url).also(current::set)
    fun current(): State = current.get()
    fun setTitle(title: String?): State {
        val previous = current.get()
        val next = previous.copy(title = com.webmediacapture.util.MediaTitles.prefer(previous.title, title))
        current.set(next)
        return next
    }
}
