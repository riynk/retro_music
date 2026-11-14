/*
 * Copyright (c) 2026 Retro Music contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package code.name.monkey.retromusic.lyrics

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import code.name.monkey.retromusic.lyrics.engine.LyricsSynchronizer
import code.name.monkey.retromusic.lyrics.model.LyricDocument
import code.name.monkey.retromusic.lyrics.model.LyricLine

/**
 * Shared karaoke renderer for Replace Cover, Over Cover and the full lyrics page.
 * All appearance choices are supplied by LyricsDisplaySettings, while colours
 * follow the current theme unless the Now Playing theme provides adaptive colours.
 */
class KaraokeLyricsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(24), dp(8), dp(32))
    }
    private val lineViews = mutableListOf<TextView>()
    private val translationViews = mutableListOf<TextView?>()
    private var document: LyricDocument? = null
    private var highlightWords = true
    private var currentLine = -2
    private var currentWord = -2
    private var lineClickListener: ((Long) -> Unit)? = null
    private var style = LyricsDisplayStyle()

    private var primaryTextColor = resolveThemeColor(android.R.attr.textColorPrimary, Color.WHITE)
    private var secondaryTextColor = resolveThemeColor(android.R.attr.textColorSecondary, 0xFFBDBDBD.toInt())
    private var accentTextColor = resolveThemeColor(android.R.attr.colorAccent, Color.WHITE)

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        applyAlignment()
    }

    fun setOnLineClickListener(listener: (Long) -> Unit) {
        lineClickListener = listener
    }

    fun setStyle(value: LyricsDisplayStyle) {
        style = value
        applyAlignment()
        document?.let { renderLines(it, currentLine, currentWord) }
    }

    fun setTextColors(
        primaryColor: Int,
        secondaryColor: Int,
        accentColor: Int? = null
    ) {
        primaryTextColor = primaryColor
        secondaryTextColor = secondaryColor
        if (accentColor != null) accentTextColor = accentColor
        document?.let { renderLines(it, currentLine, currentWord) }
    }

    fun clearDocument() {
        document = null
        lineViews.clear()
        translationViews.clear()
        content.removeAllViews()
        currentLine = -2
        currentWord = -2
        scrollTo(0, 0)
    }

    fun setEmptyMessage(message: String) {
        clearDocument()
        val label = TextView(context).apply {
            text = message
            gravity = alignmentGravity()
            typeface = resolveTypeface(style.fontFamily)
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                (style.textSizeSp * 0.72f).coerceAtLeast(14f)
            )
            setTextColor(secondaryTextColor)
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        content.addView(
            label,
            LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
        )
    }

    fun setDocument(
        value: LyricDocument,
        translation: List<String>? = null,
        enableWordHighlight: Boolean = true
    ) {
        document = value
        highlightWords = enableWordHighlight
        currentLine = -2
        currentWord = -2
        lineViews.clear()
        translationViews.clear()
        content.removeAllViews()

        value.lines.forEachIndexed { index, line ->
            val lyric = TextView(context).apply {
                setLineSpacing(dp(5).toFloat(), 1f)
                setPadding(dp(12), dp(12), dp(12), dp(2))
                isClickable = true
                isFocusable = true
                setOnClickListener { lineClickListener?.invoke(line.startMs) }
            }
            lineViews += lyric
            content.addView(
                lyric,
                LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT
                )
            )

            val translated = translation?.getOrNull(index)?.takeIf { it.isNotBlank() }
            val subtitle = translated?.let { text ->
                TextView(context).apply {
                    this.text = text
                    setPadding(dp(12), 0, dp(12), dp(16))
                    alpha = 0.9f
                }
            }
            translationViews += subtitle
            if (subtitle != null) {
                content.addView(
                    subtitle,
                    LinearLayout.LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }

        applyAlignment()
        post { updateTime(0L) }
    }

    fun updateTime(positionMs: Long, offsetMs: Long = 0L) {
        val value = document ?: return
        val position = LyricsSynchronizer.locate(value, positionMs, offsetMs)
        if (position.lineIndex != currentLine) {
            currentLine = position.lineIndex
            currentWord = position.wordIndex
            renderLines(value, position.lineIndex, position.wordIndex)
            if (position.lineIndex >= 0) {
                post {
                    val row = lineViews.getOrNull(position.lineIndex) ?: return@post
                    smoothScrollTo(
                        0,
                        (row.top - (height - row.height) / 2).coerceAtLeast(0)
                    )
                }
            }
        } else if (position.wordIndex != currentWord) {
            currentWord = position.wordIndex
            renderActiveLine(value.lines.getOrNull(currentLine), position.wordIndex)
        }
    }

    private fun renderLines(value: LyricDocument, activeIndex: Int, activeWord: Int) {
        value.lines.forEachIndexed { index, line ->
            val active = index == activeIndex
            val lyric = lineViews[index]
            lyric.alpha = when {
                active -> 1f
                kotlin.math.abs(index - activeIndex) == 1 -> 0.72f
                else -> 0.42f
            }
            lyric.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                style.textSizeSp * if (active) 1f else 0.78f
            )
            lyric.typeface = resolveTypeface(style.fontFamily)
            lyric.gravity = alignmentGravity()
            lyric.setTextColor(if (active) primaryTextColor else secondaryTextColor)
            lyric.text = if (active) highlightedText(line, activeWord) else line.text

            translationViews.getOrNull(index)?.let { subtitle ->
                subtitle.alpha = if (active) 0.9f else 0.42f
                subtitle.setTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    (style.textSizeSp * 0.58f).coerceAtLeast(12f)
                )
                subtitle.typeface = resolveTypeface(style.fontFamily)
                subtitle.gravity = alignmentGravity()
                subtitle.setTextColor(secondaryTextColor)
            }
        }
    }

    private fun renderActiveLine(line: LyricLine?, activeWord: Int) {
        val index = currentLine
        if (line != null && index in lineViews.indices) {
            lineViews[index].text = highlightedText(line, activeWord)
        }
    }

    private fun highlightedText(line: LyricLine, activeWord: Int): CharSequence {
        if (!highlightWords || line.words.isEmpty()) return line.text
        val spannable = SpannableString(line.text)
        line.words.forEachIndexed { index, word ->
            val start = word.startIndex.coerceIn(0, spannable.length)
            val end = word.endIndex.coerceIn(start, spannable.length)
            if (end > start) {
                val color = if (index <= activeWord) accentTextColor else primaryTextColor
                spannable.setSpan(
                    ForegroundColorSpan(color),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return spannable
    }

    private fun applyAlignment() {
        content.gravity = alignmentGravity()
        lineViews.forEach { it.gravity = alignmentGravity() }
        translationViews.filterNotNull().forEach { it.gravity = alignmentGravity() }
    }

    private fun alignmentGravity(): Int = when (style.alignment) {
        "left" -> Gravity.START or Gravity.CENTER_VERTICAL
        "right" -> Gravity.END or Gravity.CENTER_VERTICAL
        else -> Gravity.CENTER
    }

    private fun resolveTypeface(family: String): Typeface = when (family) {
        "default" -> Typeface.DEFAULT
        else -> Typeface.create(family, Typeface.NORMAL)
    }

    private fun resolveThemeColor(attribute: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attribute, value, true)) return fallback
        return if (value.resourceId != 0) {
            runCatching { context.getColor(value.resourceId) }.getOrDefault(fallback)
        } else {
            value.data
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
