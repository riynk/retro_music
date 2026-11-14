/*
 * Copyright (c) 2026 Retro Music contributors.
 * Licensed under the GNU General Public License v3.0 or later.
 */
package code.name.monkey.retromusic.lyrics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import code.name.monkey.retromusic.lyrics.engine.LyricsSynchronizer
import code.name.monkey.retromusic.lyrics.model.LyricDocument
import code.name.monkey.retromusic.lyrics.model.LyricLine
import code.name.monkey.retromusic.lyrics.model.LyricWord
import kotlin.math.roundToInt

/**
 * Shared karaoke renderer for Replace Cover, Over Cover and the full lyrics page.
 * Timed TTML/LRC segments are progressively filled during their actual time
 * interval rather than changing colour only after the segment has finished.
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
    private var translations: List<String>? = null
    private var highlightWords = true
    private var currentLine = -2
    private var currentWord = -2
    private var currentPositionMs = 0L
    private var lineClickListener: ((Long) -> Unit)? = null
    private var translationRetryListener: (() -> Unit)? = null
    private var translationStatus: String? = null
    private var translationStatusView: TextView? = null
    private var style = LyricsDisplayStyle()

    private var primaryTextColor = resolveThemeColor(android.R.attr.textColorPrimary, Color.WHITE)
    private var secondaryTextColor =
        resolveThemeColor(android.R.attr.textColorSecondary, 0xFFBDBDBD.toInt())
    private var accentTextColor = resolveThemeColor(android.R.attr.colorAccent, 0xFF80CBC4.toInt())

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

    fun setOnTranslationRetryListener(listener: (() -> Unit)?) {
        translationRetryListener = listener
        translationStatusView?.isClickable = listener != null
    }

    fun setStyle(value: LyricsDisplayStyle) {
        style = value
        applyAlignment()
        document?.let { renderLines(it, currentLine) }
        translationStatusView?.typeface = resolveTypeface(style.fontFamily)
    }

    fun setTextColors(
        primaryColor: Int,
        secondaryColor: Int,
        accentColor: Int? = null
    ) {
        primaryTextColor = primaryColor
        secondaryTextColor = secondaryColor
        if (accentColor != null) accentTextColor = accentColor
        document?.let { renderLines(it, currentLine) }
        translationStatusView?.setTextColor(secondaryTextColor)
    }

    fun setTranslationStatus(message: String?) {
        translationStatus = message
        refreshTranslationStatus()
    }

    fun clearDocument() {
        document = null
        translations = null
        lineViews.clear()
        translationViews.clear()
        content.removeAllViews()
        translationStatusView = null
        translationStatus = null
        currentLine = -2
        currentWord = -2
        currentPositionMs = 0L
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
        translations = translation?.takeIf { it.size == value.lines.size }
        highlightWords = enableWordHighlight
        currentLine = -2
        currentWord = -2
        lineViews.clear()
        translationViews.clear()
        translationStatusView = null
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

            val translated = translations?.getOrNull(index)?.takeIf { it.isNotBlank() }
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

        refreshTranslationStatus()
        applyAlignment()
        post { updateTime(currentPositionMs) }
    }

    fun updateTime(positionMs: Long, offsetMs: Long = 0L) {
        val value = document ?: return
        currentPositionMs = (positionMs + offsetMs).coerceAtLeast(0L)
        val position = LyricsSynchronizer.locate(value, currentPositionMs)
        val lineChanged = position.lineIndex != currentLine
        currentLine = position.lineIndex
        currentWord = position.wordIndex

        if (lineChanged) {
            renderLines(value, position.lineIndex)
            if (position.lineIndex >= 0) {
                post {
                    val row = lineViews.getOrNull(position.lineIndex) ?: return@post
                    smoothScrollTo(
                        0,
                        (row.top - (height - row.height) / 2).coerceAtLeast(0)
                    )
                }
            }
        } else if (position.lineIndex >= 0) {
            // Rebuild the active line's spans on every progress tick so the
            // highlight moves smoothly through the current timed segment.
            renderActiveLine(value.lines.getOrNull(position.lineIndex))
        }
    }

    private fun renderLines(value: LyricDocument, activeIndex: Int) {
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
            lyric.text = if (active) highlightedText(line, currentPositionMs) else line.text

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

    private fun renderActiveLine(line: LyricLine?) {
        val index = currentLine
        if (line != null && index in lineViews.indices) {
            lineViews[index].text = highlightedText(line, currentPositionMs)
        }
    }

    private fun highlightedText(line: LyricLine, positionMs: Long): CharSequence {
        if (!highlightWords || line.words.isEmpty()) return line.text
        val spannable = SpannableString(line.text)
        line.words.forEach { word ->
            val start = word.startIndex.coerceIn(0, spannable.length)
            val end = word.endIndex.coerceIn(start, spannable.length)
            if (end > start) {
                val duration = (word.endMs - word.startMs).coerceAtLeast(1L)
                val progress = ((positionMs - word.startMs).toFloat() / duration)
                    .coerceIn(0f, 1f)
                val unfilled = if (word.isBackground) secondaryTextColor else primaryTextColor
                spannable.setSpan(
                    ProgressiveLyricsSpan(progress, unfilled, accentTextColor),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return spannable
    }

    private fun refreshTranslationStatus() {
        translationStatusView?.let(content::removeView)
        translationStatusView = null

        val message = translationStatus ?: return
        val status = TextView(context).apply {
            text = message
            gravity = alignmentGravity()
            typeface = resolveTypeface(style.fontFamily)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(secondaryTextColor)
            alpha = 0.9f
            setPadding(dp(12), dp(8), dp(12), dp(12))
            isClickable = translationRetryListener != null
            isFocusable = translationRetryListener != null
            setOnClickListener { translationRetryListener?.invoke() }
        }
        translationStatusView = status
        content.addView(
            status,
            0,
            LinearLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun applyAlignment() {
        content.gravity = alignmentGravity()
        lineViews.forEach { it.gravity = alignmentGravity() }
        translationViews.filterNotNull().forEach { it.gravity = alignmentGravity() }
        translationStatusView?.gravity = alignmentGravity()
    }

    private fun alignmentGravity(): Int = when (style.alignment) {
        "left" -> Gravity.LEFT or Gravity.CENTER_VERTICAL
        "right" -> Gravity.RIGHT or Gravity.CENTER_VERTICAL
        else -> Gravity.CENTER
    }

    private fun resolveTypeface(family: String): Typeface = when (family) {
        "default" -> Typeface.DEFAULT
        else -> Typeface.create(family, Typeface.NORMAL)
    }

    private fun resolveThemeColor(attribute: Int, fallback: Int): Int {
        val values = context.obtainStyledAttributes(intArrayOf(attribute))
        return try {
            values.getColorStateList(0)?.defaultColor ?: values.getColor(0, fallback)
        } finally {
            values.recycle()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private class ProgressiveLyricsSpan(
        private val progress: Float,
        private val baseColor: Int,
        private val highlightColor: Int
    ) : ReplacementSpan() {
        override fun getSize(
            paint: Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fontMetricsInt: Paint.FontMetricsInt?
        ): Int = paint.measureText(text, start, end).roundToInt()

        override fun draw(
            canvas: Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            val value = text.subSequence(start, end).toString()
            val basePaint = TextPaint(paint).apply { color = baseColor }
            val width = basePaint.measureText(value)
            canvas.drawText(value, x, y.toFloat(), basePaint)

            val fillWidth = width * progress.coerceIn(0f, 1f)
            if (fillWidth > 0f) {
                val save = canvas.save()
                canvas.clipRect(x, top.toFloat(), x + fillWidth, bottom.toFloat())
                basePaint.color = highlightColor
                canvas.drawText(value, x, y.toFloat(), basePaint)
                canvas.restoreToCount(save)
            }
        }
    }
}
