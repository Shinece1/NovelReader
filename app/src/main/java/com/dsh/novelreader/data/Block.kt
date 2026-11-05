package com.dsh.novelreader.data

import android.graphics.RectF

/**
 * One unit of page content in reading order.
 *
 * A PDF page is stored as a list of blocks rather than one string: a scanned
 * page becomes a single [PageImageBlock], a text page becomes a list of
 * [TextBlock]s (with per-run font styling), and embedded figures are
 * [ImageBlock]s positioned where they occur on the page.
 *
 * Every block carries a stable id `p<pageIndex>b<ordinal>` derived from its
 * original page and its order in the extracted layout. Re-flowing the text,
 * changing the font size or rotating the screen never changes an id, which is
 * what bookmarks and annotations are anchored to.
 */
sealed class Block {
    abstract val id: String
    abstract val pageIndex: Int

    /** Normalised (0..1) bounding box on the original page, when known. */
    abstract val bbox: RectF?
}

/** A contiguous style change inside a [TextBlock]'s text. */
data class StyleRun(
    val start: Int,
    val end: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val sizeRatio: Float = 1f,
)

/** A paragraph (or heading) of text. */
data class TextBlock(
    override val id: String,
    override val pageIndex: Int,
    override val bbox: RectF?,
    val text: String,
    val runs: List<StyleRun> = emptyList(),
    /** 0 = body text, 1..3 = heading levels. */
    val headingLevel: Int = 0,
) : Block()

/** An embedded figure that should be shown near its original position. */
data class ImageBlock(
    override val id: String,
    override val pageIndex: Int,
    override val bbox: RectF?,
    val caption: String? = null,
) : Block()

/** A whole page rendered as-is (scanned / image-only page). */
data class PageImageBlock(
    override val id: String,
    override val pageIndex: Int,
    override val bbox: RectF? = null,
) : Block()
