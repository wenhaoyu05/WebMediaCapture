package com.webmediacapture.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class HttpClientProviderTest {
    @Test fun preferIpv4SortsV4First() {
        val v6 = InetAddress.getByName("::1")
        val v4 = InetAddress.getByName("127.0.0.1")
        assertEquals(listOf(v4), HttpClientProvider.preferIpv4(listOf(v6, v4)))
        assertEquals(listOf(v4), HttpClientProvider.preferIpv4(listOf(v4)))
        assertEquals(listOf(v6), HttpClientProvider.preferIpv4(listOf(v6)))
    }
}
