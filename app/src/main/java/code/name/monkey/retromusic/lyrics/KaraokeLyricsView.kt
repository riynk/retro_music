/*
 * Copyright (c) 2026 Retro Music contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package code.name.monkey.retromusic.lyrics

import android.content.Context
import android.graphics.Color
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
 * Lightweight scrollable karaoke renderer. It highlights actual timed spans
 * and falls back to line-synced display when a lyric source has no word times.
 */
class KaraokeLyricsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ScrollView(context, attrs) {

    private val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(8), dp(24), dp(8), dp(32))
    }
    private val lineViews = mutableListOf<TextView>()
    private var document: LyricDocument? = null
    private var translations: List<String>? = null
    private var highlightWords = true
    private var currentLine = -2
    private var currentWord = -2
    private var lineClickListener: ((Long) -> Unit)? = null
    private val accentColor: Int
        get() {
            val value = TypedValue()
            context.theme.resolveAttribute(android.R.attr.colorAccent, value, true)
            return if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
        }

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun setOnLineClickListener(listener: (Long) -> Unit) {
        lineClickListener = listener
    }

    fun setDocument(
        value: LyricDocument,
        translation: List<String>? = null,
        enableWordHighlight: Boolean = true
    ) {
        document = value
        translations = translation?.takeIf { it.size == value.lines.size }
        highlightWords = enableWordHighlight
        currentLine = -2
        currentWord = -2
        lineViews.clear()
        content.removeAllViews()

        value.lines.forEachIndexed { index, line ->
            val lyric = TextView(context).apply {
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTextColor(Color.WHITE)
                setLineSpacing(dp(5).toFloat(), 1f)
                setPadding(dp(12), dp(12), dp(12), dp(2))
                isClickable = true
                setOnClickListener { lineClickListener?.invoke(line.startMs) }
            }
            lineViews += lyric
            content.addView(
                lyric,
                LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            )

            translations?.getOrNull(index)?.takeIf { it.isNotBlank() }?.let { translated ->
                val subtitle = TextView(context).apply {
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(0xFFBDBDBD.toInt())
                    setPadding(dp(12), dp(0), dp(12), dp(16))
                    text = translated
                }
                content.addView(
                    subtitle,
                    LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                )
            }
        }
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
                    smoothScrollTo(0, (row.top - (height - row.height) / 2).coerceAtLeast(0))
                }
            }
        } else if (position.wordIndex != currentWord) {
            currentWord = position.wordIndex
            renderActiveLine(value.lines.getOrNull(currentLine), position.wordIndex)
        }
    }

    private fun renderLines(value: LyricDocument, activeIndex: Int, activeWord: Int) {
        value.lines.forEachIndexed { index, line ->
            val view = lineViews[index]
            view.alpha = when {
                index == activeIndex -> 1f
                kotlin.math.abs(index - activeIndex) == 1 -> 0.72f
                else -> 0.42f
            }
            view.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                if (index == activeIndex) 27f else 21f
            )
            view.text = if (index == activeIndex) highlightedText(line, activeWord) else line.text
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
                val color = if (index <= activeWord) accentColor else Color.WHITE
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
