package com.dsh.novelreader.reader

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/**
 * Splits a section's text into screen-sized pages using [StaticLayout], the same
 * engine that draws the text, so measurement and rendering always agree.
 *
 * Pagination is per section: a PDF page (or a ~3000-char TXT chunk) is paginated
 * on its own, which means a later OCR result never shifts already-read pages.
 */
object TextPaginator {

    data class Page(val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    fun paginate(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        height: Int,
        lineSpacingMultiplier: Float,
    ): List<Page> {
        if (width <= 0 || height <= 0) return listOf(Page(0, text.length))
        if (text.isEmpty()) return listOf(Page(0, 0))

        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setLineSpacing(0f, lineSpacingMultiplier)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

        val pages = ArrayList<Page>()
        var firstLine = 0
        while (firstLine < layout.lineCount) {
            val top = layout.getLineTop(firstLine)
            var lastLine = firstLine
            while (lastLine + 1 < layout.lineCount &&
                layout.getLineBottom(lastLine + 1) - top <= height
            ) {
                lastLine++
            }
            val start = layout.getLineStart(firstLine)
            val end = layout.getLineEnd(lastLine)
            pages.add(Page(start, end))
            firstLine = lastLine + 1
        }
        return if (pages.isEmpty()) listOf(Page(0, text.length)) else pages
    }

    /** Index of the page that contains [charOffset] (clamped). */
    fun pageForOffset(pages: List<Page>, charOffset: Int): Int {
        if (pages.isEmpty()) return 0
        val index = pages.indexOfFirst { charOffset >= it.start && charOffset < it.end }
        return if (index >= 0) index else pages.lastIndex
    }
}
