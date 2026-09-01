package com.webmediacapture.detector

import com.webmediacapture.model.MediaVariant
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

object DashParser {
    data class Representation(
        val id: String?,
        val mimeType: String?,
        val width: Int?,
        val height: Int?,
        val bandwidth: Long?,
        val codecs: String?,
        val isVideo: Boolean,
        val isAudio: Boolean,
        val initUrl: String?,
        val mediaUrls: List<String>,
    )

    data class Result(
        val drmProtected: Boolean,
        val representations: List<Representation>,
    ) {
        fun variants(): List<MediaVariant> = representations
            .filter { it.isVideo || (!representations.any { item -> item.isVideo } && it.isAudio) }
            .map {
                MediaVariant(
                    url = it.mediaUrls.firstOrNull() ?: it.initUrl.orEmpty(),
                    formatId = it.id,
                    width = it.width,
                    height = it.height,
                    bitrate = it.bandwidth,
                    codecs = it.codecs,
                )
            }
            .filter { it.url.isNotBlank() }
    }

    fun parse(content: String, manifestUrl: String = "https://example.test/manifest.mpd"): Result {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(content)))
        val mpd = document.documentElement
        val drm = mpd.getElementsByTagName("*").let { nodes ->
            (0 until nodes.length).any { localName(nodes.item(it) as? Element) == "ContentProtection" }
        }
        val mpdBase = resolveBase(manifestUrl, childText(mpd, "BaseURL"))
        val representations = mutableListOf<Representation>()
        elements(mpd, "Period").ifEmpty { listOf(mpd) }.forEach { period ->
            val periodBase = resolveBase(mpdBase, childText(period, "BaseURL"))
            elements(period, "AdaptationSet").forEach { adaptation ->
                val adaptationBase = resolveBase(periodBase, childText(adaptation, "BaseURL"))
                val adaptationMime = attr(adaptation, "mimeType")
                val adaptationCodecs = attr(adaptation, "codecs")
                val contentType = attr(adaptation, "contentType")
                elements(adaptation, "Representation").forEach { representation ->
                    val id = attr(representation, "id")
                    val mime = attr(representation, "mimeType") ?: adaptationMime
                    val codecs = attr(representation, "codecs") ?: adaptationCodecs
                    val bandwidth = attr(representation, "bandwidth")?.toLongOrNull()
                    val width = attr(representation, "width")?.toIntOrNull()
                    val height = attr(representation, "height")?.toIntOrNull()
                    val representationBase = resolveBase(adaptationBase, childText(representation, "BaseURL"))
                    val (init, media) = segmentUrls(representation, adaptation, representationBase, id, bandwidth)
                    val isVideo = mime?.startsWith("video/") == true || contentType == "video" || (width != null && height != null)
                    val isAudio = mime?.startsWith("audio/") == true || contentType == "audio"
                    representations += Representation(
                        id = id,
                        mimeType = mime,
                        width = width,
                        height = height,
                        bandwidth = bandwidth,
                        codecs = codecs,
                        isVideo = isVideo,
                        isAudio = isAudio,
                        initUrl = init,
                        mediaUrls = media,
                    )
                }
            }
        }
        return Result(drm, representations)
    }

    private fun segmentUrls(
        representation: Element,
        adaptation: Element,
        base: String,
        id: String?,
        bandwidth: Long?,
    ): Pair<String?, List<String>> {
        val listElement = first(representation, "SegmentList") ?: first(adaptation, "SegmentList")
        if (listElement != null) {
            val init = first(listElement, "Initialization")?.let { attr(it, "sourceURL") }?.let { resolveBase(base, it) }
            val media = elements(listElement, "SegmentURL").mapNotNull { attr(it, "media") }.map { resolveBase(base, it) }
            return init to media
        }
        val template = first(representation, "SegmentTemplate") ?: first(adaptation, "SegmentTemplate")
        if (template != null) {
            val startNumber = attr(template, "startNumber")?.toLongOrNull() ?: 1L
            val initialization = attr(template, "initialization")?.let {
                resolveBase(base, expandTemplate(it, id.orEmpty(), startNumber, 0, bandwidth ?: 0))
            }
            val mediaTemplate = attr(template, "media") ?: return initialization to emptyList()
            val timeline = first(template, "SegmentTimeline")
            val urls = if (timeline != null) {
                expandTimeline(timeline, startNumber).map { (number, time) ->
                    resolveBase(base, expandTemplate(mediaTemplate, id.orEmpty(), number, time, bandwidth ?: 0))
                }
            } else {
                val duration = attr(template, "duration")?.toLongOrNull()
                val timescale = attr(template, "timescale")?.toLongOrNull() ?: 1L
                val count = if (duration != null && duration > 0) {
                    (attr(template.ownerDocument.documentElement, "mediaPresentationDuration")
                        ?.let(::parseDurationSeconds)
                        ?.let { total -> ((total * timescale) / duration).toInt().coerceAtLeast(1) }
                        ?: 0)
                } else 0
                if (count <= 0) emptyList()
                else (0 until count.coerceAtMost(10_000)).map { index ->
                    val number = startNumber + index
                    resolveBase(base, expandTemplate(mediaTemplate, id.orEmpty(), number, (index * (duration ?: 0)), bandwidth ?: 0))
                }
            }
            return initialization to urls
        }
        return null to if (base.endsWith(".m4s") || base.endsWith(".mp4")) listOf(base) else emptyList()
    }

    private fun expandTimeline(timeline: Element, startNumber: Long): List<Pair<Long, Long>> {
        var number = startNumber
        var time = 0L
        val result = mutableListOf<Pair<Long, Long>>()
        elements(timeline, "S").forEach { item ->
            time = attr(item, "t")?.toLongOrNull() ?: time
            val duration = attr(item, "d")?.toLongOrNull() ?: 0L
            val repeat = attr(item, "r")?.toIntOrNull() ?: 0
            repeat(repeat + 1) {
                result += number to time
                number += 1
                time += duration
            }
        }
        return result
    }

    private fun expandTemplate(template: String, id: String, number: Long, time: Long, bandwidth: Long): String {
        var value = template.replace("$$", "\u0000")
        value = value.replace("\$RepresentationID\$", id)
        value = replaceToken(value, "Number", number)
        value = replaceToken(value, "Time", time)
        value = replaceToken(value, "Bandwidth", bandwidth)
        return value.replace("\u0000", "$")
    }

    private fun replaceToken(template: String, name: String, number: Long): String {
        val regex = Regex("\\$$name(?:%0(\\d+)d)?\\$")
        return regex.replace(template) { match ->
            val width = match.groupValues.getOrNull(1)?.toIntOrNull()
            if (width != null) number.toString().padStart(width, '0') else number.toString()
        }
    }

    private fun parseDurationSeconds(value: String): Double? {
        val match = Regex("^PT(?:(\\d+(?:\\.\\d+)?)H)?(?:(\\d+(?:\\.\\d+)?)M)?(?:(\\d+(?:\\.\\d+)?)S)?$").matchEntire(value) ?: return null
        val hours = match.groupValues[1].toDoubleOrNull() ?: 0.0
        val minutes = match.groupValues[2].toDoubleOrNull() ?: 0.0
        val seconds = match.groupValues[3].toDoubleOrNull() ?: 0.0
        return hours * 3600 + minutes * 60 + seconds
    }

    private fun resolveBase(parent: String, child: String?): String {
        if (child.isNullOrBlank()) return parent
        return runCatching { URI(parent).resolve(child).toString() }.getOrDefault(child)
    }

    private fun elements(parent: Element, name: String): List<Element> {
        val found = mutableListOf<Element>()
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val node = children.item(index) as? Element ?: continue
            if (localName(node) == name) found += node
        }
        return found
    }

    private fun first(parent: Element, name: String): Element? = elements(parent, name).firstOrNull()

    private fun childText(parent: Element, name: String): String? = first(parent, name)?.textContent?.trim()?.takeIf { it.isNotBlank() }

    private fun attr(element: Element, name: String): String? = element.getAttribute(name).takeIf { it.isNotBlank() }

    private fun localName(element: Element?): String? = element?.localName?.takeIf { it.isNotBlank() } ?: element?.tagName?.substringAfter(':')
}
