package com.webmediacapture.detector

import com.webmediacapture.model.MediaVariant
import java.net.URI

object HlsParser {
    data class Result(
        val isMaster: Boolean,
        val variants: List<MediaVariant>,
        /** True only for known content-protection schemes, never for ordinary AES-128 transport encryption. */
        val drmProtected: Boolean,
    )

    data class HlsSegment(
        val url: String,
        val durationSec: Double,
        val sequence: Long,
    )

    data class MediaPlaylist(
        val segments: List<HlsSegment>,
        val initSegmentUrl: String?,
        val drmProtected: Boolean,
        val aes128: Boolean,
        val keyUri: String?,
        val keyIv: String?,
        val mediaSequence: Long,
        val ended: Boolean,
        val durationSec: Double,
    )

    fun parse(content: String, manifestUrl: String): Result {
        if (!content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().startsWith("#EXTM3U")) {
            throw IllegalArgumentException("Not an HLS playlist")
        }
        val lines = content.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        val variants = buildList {
            lines.forEachIndexed { index, line ->
                if (!line.startsWith("#EXT-X-STREAM-INF:", true)) return@forEachIndexed
                val attrs = attributes(line.substringAfter(':'))
                val target = lines.drop(index + 1).firstOrNull { !it.startsWith('#') } ?: return@forEachIndexed
                val resolution = attrs["RESOLUTION"]?.split('x')
                add(
                    MediaVariant(
                        url = URI(manifestUrl).resolve(target).toString(),
                        width = resolution?.getOrNull(0)?.toIntOrNull(),
                        height = resolution?.getOrNull(1)?.toIntOrNull(),
                        bitrate = attrs["BANDWIDTH"]?.toLongOrNull(),
                        codecs = attrs["CODECS"]?.trim('"'),
                        audioGroup = attrs["AUDIO"]?.trim('"'),
                    ),
                )
            }
        }
        return Result(
            isMaster = variants.isNotEmpty(),
            variants = variants,
            drmProtected = lines.any(::isDrmKeyTag),
        )
    }

    fun parseMedia(content: String, playlistUrl: String): MediaPlaylist {
        if (!content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().startsWith("#EXTM3U")) {
            throw IllegalArgumentException("Not an HLS playlist")
        }
        val lines = content.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        var mediaSequence = 0L
        var initSegmentUrl: String? = null
        var keyUri: String? = null
        var keyIv: String? = null
        var aes128 = false
        var pendingDuration = 0.0
        val segments = mutableListOf<HlsSegment>()
        var sequence = 0L
        var ended = false
        for (line in lines) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:", true) -> {
                    mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                    sequence = mediaSequence
                }
                line.startsWith("#EXT-X-MAP:", true) -> {
                    val attrs = attributes(line.substringAfter(':'))
                    initSegmentUrl = attrs["URI"]?.trim('"')?.let { URI(playlistUrl).resolve(it).toString() }
                }
                line.startsWith("#EXT-X-KEY:", true) || line.startsWith("#EXT-X-SESSION-KEY:", true) -> {
                    val attrs = attributes(line.substringAfter(':'))
                    val method = attrs["METHOD"]?.trim('"')?.uppercase().orEmpty()
                    aes128 = method == "AES-128"
                    keyUri = attrs["URI"]?.trim('"')?.let { URI(playlistUrl).resolve(it).toString() }
                    keyIv = attrs["IV"]?.trim('"')
                }
                line.startsWith("#EXT-X-ENDLIST", true) -> ended = true
                line.startsWith("#EXTINF:", true) -> {
                    pendingDuration = line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
                }
                !line.startsWith('#') -> {
                    segments += HlsSegment(URI(playlistUrl).resolve(line).toString(), pendingDuration, sequence)
                    sequence += 1
                    pendingDuration = 0.0
                }
            }
        }
        return MediaPlaylist(
            segments = segments,
            initSegmentUrl = initSegmentUrl,
            drmProtected = lines.any(::isDrmKeyTag),
            aes128 = aes128,
            keyUri = keyUri,
            keyIv = keyIv,
            mediaSequence = mediaSequence,
            ended = ended,
            durationSec = segments.sumOf { it.durationSec },
        )
    }

    private fun isDrmKeyTag(line: String): Boolean {
        if (!line.startsWith("#EXT-X-KEY:", true) && !line.startsWith("#EXT-X-SESSION-KEY:", true)) return false
        val attrs = attributes(line.substringAfter(':'))
        val method = attrs["METHOD"]?.trim('"')?.uppercase().orEmpty()
        if (method.startsWith("SAMPLE-AES")) return true
        val keyFormat = attrs["KEYFORMAT"]?.trim('"')?.lowercase().orEmpty()
        return keyFormat.contains("streamingkeydelivery") ||
            keyFormat.contains("widevine") ||
            keyFormat.contains("playready") ||
            keyFormat.contains("fairplay") ||
            keyFormat.startsWith("urn:uuid:")
    }

    private fun attributes(value: String): Map<String, String> = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")
        .findAll(value).associate { it.groupValues[1] to it.groupValues[2] }
}
