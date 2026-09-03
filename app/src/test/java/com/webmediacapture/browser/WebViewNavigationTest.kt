package com.webmediacapture.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewNavigationTest {
    @Test fun keepsHttpPagesAndBlocksAppOpenJumps() {
        assertTrue(WebViewNavigation.shouldLoad("https://m.douyin.com/share/video/7680975876750121330"))
        assertTrue(WebViewNavigation.shouldLoad("https://www.douyin.com/video/7680975876750121330"))
        assertTrue(WebViewNavigation.shouldLoad("https://v.douyin.com/O-oi-7UJhgI/"))
        assertFalse(WebViewNavigation.shouldLoad("https://z.douyin.com/JhgUA"))
        assertFalse(WebViewNavigation.shouldLoad("snssdk1128://aweme/detail/1"))
        assertFalse(WebViewNavigation.shouldLoad("intent://www.douyin.com/#Intent;scheme=https;end"))
        assertFalse(WebViewNavigation.shouldLoad("chrome-error://chromewebdata/"))
    }
}
