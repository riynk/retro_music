/*
 * Copyright (c) 2026 Retro Music contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package code.name.monkey.retromusic.lyrics.parser

import android.util.Xml
import code.name.monkey.retromusic.lyrics.model.LyricDocument
import code.name.monkey.retromusic.lyrics.model.LyricLine
import code.name.monkey.retromusic.lyrics.model.LyricWord
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.util.ArrayDeque

/**
 * Reads TTML paragraph timing and timed span segments (including Apple Music
 * word/phrase timing). Unsupported frame-based expressions are ignored safely.
 */
object TtmlLyricsParser {
    private data class SpanFrame(
        val startIndex: Int,
        val beginMs: Long?,
        val endMs: Long?,
        var hasChild: Boolean = false
    )

    private data class MutableLine(
        val beginMs: Long?,
        val endMs: Long?,
        val text: StringBuilder = StringBuilder(),
        val words: MutableList<LyricWord> = mutableListOf()
    )

    fun parse(xml: String): LyricDocument? {
        if (xml.isBlank()) return null
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(StringReader(xml))

        val result = mutableListOf<LyricLine>()
        val spans = ArrayDeque<SpanFrame>()
        var line: MutableLine? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name.substringAfter(':')) {
                    "p" -> {
                        line = MutableLine(
                            beginMs = parseTime(attribute(parser, "begin")),
                            endMs = parseTime(attribute(parser, "end"))
                        )
                        spans.clear()
                    }
                    "span" -> {
                        val current = line
                        if (current != null) {
                            if (spans.isNotEmpty()) spans.peekLast().hasChild = true
                            spans.addLast(
                                SpanFrame(
                                    startIndex = current.text.length,
                                    beginMs = parseTime(attribute(parser, "begin")),
                                    endMs = parseTime(attribute(parser, "end"))
                                )
                            )
                        }
                    }
                }

                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    val current = line
                    if (current != null) {
                        val value = parser.text.orEmpty()
                        current.text.append(value)
                    }
                }

                XmlPullParser.END_TAG -> when (parser.name.substringAfter(':')) {
                    "span" -> {
                        if (spans.isNotEmpty()) {
                            val frame = spans.removeLast()
                            val current = line
                            if (current != null && !frame.hasChild &&
                                frame.beginMs != null && frame.endMs != null &&
                                frame.endMs > frame.beginMs
                            ) {
                                val endIndex = current.text.length
                                val text = current.text.substring(
                                    frame.startIndex.coerceIn(0, endIndex), endIndex
                                )
                                if (text.isNotBlank()) {
                                    current.words += LyricWord(
                                        startMs = frame.beginMs,
                                        endMs = frame.endMs,
                                        text = text,
                                        startIndex = frame.startIndex,
                                        endIndex = endIndex
                                    )
                                }
                            }
                        }
                    }
                    "p" -> {
                        val current = line
                        if (current != null) {
                            val raw = current.text.toString()
                            val text = raw.trim()
                            if (text.isNotEmpty()) {
                                val leading = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                                val words = current.words.mapNotNull { word ->
                                    val start = (word.startIndex - leading).coerceAtLeast(0)
                                    val end = (word.endIndex - leading).coerceAtMost(text.length)
                                    if (end <= start) null else word.copy(startIndex = start, endIndex = end)
                                }
                                val start = current.beginMs ?: words.minOfOrNull { it.startMs } ?: 0L
                                result += LyricLine(
                                    startMs = start,
                                    endMs = current.endMs,
                                    text = text,
                                    words = words.sortedBy { it.startMs }
                                )
                            }
                        }
                        line = null
                        spans.clear()
                    }
                }
            }
            parser.next()
        }

        if (result.isEmpty()) return null
        val sorted = result.sortedBy { it.startMs }
        return LyricDocument(
            lines = sorted.mapIndexed { index, lyricLine ->
                lyricLine.copy(
                    endMs = lyricLine.endMs ?: sorted.getOrNull(index + 1)?.startMs
                        ?: lyricLine.startMs + 5_000L
                )
            },
            sourceFormat = "ttml"
        )
    }

    private fun attribute(parser: XmlPullParser, name: String): String? {
        for (index in 0 until parser.attributeCount) {
            if (parser.getAttributeName(index).substringAfter(':') == name) {
                return parser.getAttributeValue(index)
            }
        }
        return null
    }

    private fun parseTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val input = value.trim()
        val offset = Regex("""^(\d+(?:\.\d+)?)(ms|s)$""").matchEntire(input)
        if (offset != null) {
            val amount = offset.groupValues[1].toDoubleOrNull() ?: return null
            return (amount * if (offset.groupValues[2] == "s") 1_000.0 else 1.0).toLong()
        }

        val parts = input.split(':')
        if (parts.size !in 2..3) return null
        val seconds = parts.last().toDoubleOrNull() ?: return null
        if (seconds < 0 || seconds >= 60) return null
        val minutes = parts[parts.lastIndex - 1].toLongOrNull() ?: return null
        if (minutes !in 0..59) return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
        if (hours < 0) return null
        return hours * 3_600_000L + minutes * 60_000L + (seconds * 1_000.0).toLong()
    }
}
