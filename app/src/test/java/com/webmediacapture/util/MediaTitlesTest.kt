package com.webmediacapture.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MediaTitlesTest {
    @Test fun prefersPageNameOverCdnFile() {
        assertEquals("真实片名", MediaTitles.prefer("master.m3u8", "真实片名"))
        assertEquals("真实片名", MediaTitles.prefer(null, "真实片名"))
        assertNull(MediaTitles.clean("Capture"))
        assertTrue(MediaTitles.looksLikeFile("index.mp4"))
        assertFalse(MediaTitles.looksLikeFile("真实片名"))
    }

    @Test fun fileStemSanitizesAndFallsBack() {
        assertEquals("A_B", MediaTitles.fileStem("A/B", "https://page.test/watch/x", "abcdef12"))
        assertEquals("watch-me", MediaTitles.fileStem(null, "https://page.test/watch-me", "abcdef12"))
        assertEquals("video-abcdef12", MediaTitles.fileStem(null, "https://cdn.test/master.m3u8", "abcdef12-rest"))
        assertEquals("foo bar", MediaTitles.fileStem("foo\nbar", "https://page.test", "id"))
    }

    @Test fun uniqueFileKeepsExtensionAndIgnoresSource() {
        val dir = File.createTempFile("unique", ".dir").apply { delete(); mkdirs() }
        try {
            val source = File(dir, "clip.mkv").apply { writeText("x") }
            assertEquals(source, MediaTitles.uniqueFile(dir, "clip", "mkv", source))
            File(dir, "other.webm").writeText("y")
            assertEquals(File(dir, "other-2.webm"), MediaTitles.uniqueFile(dir, "other", "webm", source))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun uniqueMp4IgnoresSourceFile() {
        val dir = File.createTempFile("titles", ".dir").apply { delete(); mkdirs() }
        try {
            val source = File(dir, "clip.mp4").apply { writeText("x") }
            assertEquals(source, MediaTitles.uniqueMp4(dir, "clip", source))
            File(dir, "other.mp4").writeText("y")
            assertEquals(File(dir, "other-2.mp4"), MediaTitles.uniqueMp4(dir, "other", source))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun renameKeepingExtMovesBesideOriginal() {
        val dir = File.createTempFile("rename", ".dir").apply { delete(); mkdirs() }
        try {
            val source = File(dir, "old.mp4").apply { writeText("x") }
            val dest = MediaTitles.renameKeepingExt(source, "新片名")
            assertEquals(File(dir, "新片名.mp4"), dest)
            assertTrue(dest.exists())
            assertFalse(source.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun needsMp4ConvertSkipsMp4AndAudio() {
        assertFalse(MediaTitles.needsMp4Convert("/downloads/clip.mp4"))
        assertFalse(MediaTitles.needsMp4Convert("/downloads/track.m4a"))
        assertTrue(MediaTitles.needsMp4Convert("/downloads/clip.webm"))
        assertTrue(MediaTitles.needsMp4Convert("/downloads/clip.mkv"))
        assertTrue(MediaTitles.needsMp4Convert("/downloads/clip.ts"))
        assertTrue(MediaTitles.needsMp4Convert("/downloads/clip"))
        assertFalse(MediaTitles.needsMp4Convert(null))
    }

    @Test fun convertMp4DestNeverOverwritesSource() {
        val dir = File.createTempFile("conv", ".dir").apply { delete(); mkdirs() }
        try {
            val mp4 = File(dir, "clip.mp4").apply { writeText("x") }
            val dest = MediaTitles.convertMp4Dest(mp4)
            assertEquals(File(dir, "clip-mp4.mp4"), dest)
            val webm = File(dir, "other.webm").apply { writeText("y") }
            assertEquals(File(dir, "other.mp4"), MediaTitles.convertMp4Dest(webm))
        } finally {
            dir.deleteRecursively()
        }
    }
}
