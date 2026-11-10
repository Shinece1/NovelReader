package com.dsh.novelreader.reader

import android.graphics.Paint
import android.text.style.LineHeightSpan

/**
 * Adds extra space BELOW the line that contains [atIndex].
 *
 * Android calls `chooseHeight` for every line the span set is expanded over, so
 * the span remembers its own character and only acts on the line that contains
 * it. Otherwise every line of the paragraph would get the gap.
 */
class ParagraphGapSpan(
    private val extraPx: Int,
    private val atIndex: Int,
) : LineHeightSpan {

    override fun chooseHeight(
        text: CharSequence,
        start: Int,
        end: Int,
        spanstartv: Int,
        lineHeight: Int,
        fm: Paint.FontMetricsInt,
    ) {
        if (extraPx <= 0) return
        if (atIndex < start || atIndex >= end) return
        fm.descent += extraPx
    }
}
