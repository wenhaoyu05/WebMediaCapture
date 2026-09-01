package com.webmediacapture.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FfmpegMuxerTest {
    @Test
    fun concatListKeepsCommandShortForManyInputs() {
        val tmp = File.createTempFile("mux", ".dir").apply { delete(); mkdirs() }
        try {
            val inputs = (1..200).map { File(tmp, "seg-$it.ts") }
            val output = File(tmp, "out.mp4")
            val list = File(tmp, "out.mp4.concat.txt")
            val args = FfmpegMuxer.ffmpegArgs(inputs, output, list)
            assertEquals(1, args.count { it == "-i" })
            assertTrue(list.readText().lines().count { it.startsWith("file ") } == 200)
            assertFalse(args.contains(inputs.first().absolutePath))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun remuxArgsCopyIntoMp4WithoutTsBitstreamFilter() {
        val input = File("clip.webm")
        val output = File("clip.mp4")
        val args = FfmpegMuxer.remuxArgs(input, output)
        assertTrue(args.contains("-c"))
        assertTrue(args.contains("copy"))
        assertFalse(args.contains("aac_adtstoasc"))
        assertEquals(output.absolutePath, args.last())
    }
}
