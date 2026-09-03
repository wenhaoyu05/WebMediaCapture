package com.webmediacapture.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFilesTest {
    @Test fun mimeMatchesExtension() {
        assertEquals("video/mp4", LibraryFiles.mime("/downloads/clip.mp4"))
        assertEquals("audio/mpeg", LibraryFiles.mime("track.mp3"))
        assertEquals("audio/mp4", LibraryFiles.mime("a.m4a"))
        assertEquals("video/webm", LibraryFiles.mime("x.webm"))
        assertEquals("video/mp4", LibraryFiles.mime("noext"))
        assertTrue(LibraryFiles.isAudio("song.aac"))
        assertFalse(LibraryFiles.isAudio("movie.mkv"))
    }

    @Test fun durationFormatsHoursOnlyWhenNeeded() {
        assertEquals("0:00", LibraryFiles.duration(0))
        assertEquals("0:05", LibraryFiles.duration(5_000))
        assertEquals("1:02", LibraryFiles.duration(62_000))
        assertEquals("1:01:01", LibraryFiles.duration(3_661_000))
    }

    @Test fun displayNameKeepsExtension() {
        assertEquals("片名.mp4", LibraryFiles.displayName("/downloads/clip.mp4", "片名"))
        assertEquals("clip.m4a", LibraryFiles.displayName("/downloads/clip.m4a", null))
        assertEquals("a_b.mkv", LibraryFiles.displayName("/downloads/x.mkv", "a/b"))
    }
}
