package com.webmediacapture.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressInputTest {
    private val search = "https://duckduckgo.com/?q=%1\$s"

    @Test
    fun resolvesUrlsAndSearch() {
        assertNull(AddressInput.destination("  ", search))
        assertEquals("http://127.0.0.1:9/", AddressInput.destination("http://127.0.0.1:9/", search))
        assertEquals("https://example.com", AddressInput.destination("example.com", search))
        assertEquals("https://duckduckgo.com/?q=hello+world", AddressInput.destination("hello world", search))
    }
}
