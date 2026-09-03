package com.webmediacapture.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LibraryMediaTest {
    @Test fun artifactFilesPrefersMp4AndSkipsTinyBins() {
        val dir = File.createTempFile("arts", ".dir").apply { delete(); mkdirs() }
        try {
            val id = "abc"
            File(dir, "hls-$id").mkdirs()
            File(dir, "hls-$id/seg-000001.bin").writeBytes(ByteArray(64 * 1024))
            File(dir, "hls-$id.mp4").writeBytes(ByteArray(80 * 1024))
            File(dir, "dl-$id.mp4.part").writeBytes(ByteArray(8 * 1024))
            val files = LibraryMedia.artifactFiles(dir, id)
            assertEquals("hls-$id.mp4", files.first().name)
            assertTrue(files.none { it.length() < LibraryMedia.MIN_FRAME_BYTES })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun concatProbeWritesInitThenFirstSegment() {
        val dir = File.createTempFile("probe", ".dir").apply { delete(); mkdirs() }
        try {
            val init = File(dir, "init.mp4").apply { writeBytes("INIT".toByteArray()) }
            val first = File(dir, "seg-000001.bin").apply { writeBytes("SEGM".toByteArray()) }
            val out = File(dir, "clip.mp4")
            assertTrue(LibraryMedia.concatProbe(init, first, out))
            assertEquals("INITSEGM", out.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
