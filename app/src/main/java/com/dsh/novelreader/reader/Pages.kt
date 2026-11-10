package com.dsh.novelreader.reader

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import com.dsh.novelreader.data.Block
import com.dsh.novelreader.data.ImageBlock
import com.dsh.novelreader.data.PageImageBlock
import com.dsh.novelreader.data.Section
import com.dsh.novelreader.data.TextBlock

/**
 * A run of one text block on a display page.
 *
 * [start]/[end] are offsets INSIDE the block (the anchor used by bookmarks and
 * annotations); [pageStart]/[pageEnd] are offsets inside the rendered page text,
 * which is what a tap or a highlight needs.
 */
data class TextSlice(
    val blockId: String,
    val start: Int,
    val end: Int,
    val pageStart: Int,
    val pageEnd: Int,
)

/** One rendered page: a slice of styled text, or an image that owns the page. */
sealed class DisplayPage {
    abstract val indexInUnit: Int

    data class TextPage(
        val text: CharSequence,
        val slices: List<TextSlice>,
        override val indexInUnit: Int,
    ) : DisplayPage()

    data class ImagePage(
        val pageIndex: Int,
        val blockId: String,
        /** true = whole original page, false = embedded figure. */
        val wholePage: Boolean,
        override val indexInUnit: Int,
        /** Normalised crop of the page for an embedded figure. */
        val bbox: android.graphics.RectF? = null,
    ) : DisplayPage()
}

/**
 * Turns a page unit's blocks into display pages.
 *
 * Text blocks are concatenated into one styled [SpannableStringBuilder] with
 * their style runs and headings applied, paginated with [TextPaginator], and
 * each resulting page is mapped back to (blockId, offset) slices so bookmarks
 * and annotations survive any re-flow.
 */
object PageBuilder {

    fun build(
        section: Section,
        paint: TextPaint,
        width: Int,
        height: Int,
        lineSpacing: Float,
        imageMode: Boolean,
        fidelity: Boolean,
    ): List<DisplayPage> {
        val textBlocks = section.blocks.filterIsInstance<TextBlock>()
        val pageImage = section.blocks.firstOrNull { it is PageImageBlock }

        if (imageMode && pageImage != null) {
            return listOf(DisplayPage.ImagePage(pageImage.pageIndex, pageImage.id, true, 0))
        }
        if (textBlocks.isEmpty()) {
            // Text-only mode never falls back to the page bitmap: the reader shows
            // the section's state instead (blank page / still recognising).
            return if (imageMode && pageImage != null) {
                listOf(DisplayPage.ImagePage(pageImage.pageIndex, pageImage.id, true, 0))
            } else {
                emptyList()
            }
        }

        val separator = if (fidelity) "\n\n" else "\n"
        val pages = ArrayList<DisplayPage>()
        var pendingText = SpannableStringBuilder()
        var pendingRanges = ArrayList<Pair<String, IntRange>>()
        var lastTextBlock: TextBlock? = null

        fun flushText() {
            if (pendingText.isEmpty()) return
            val localRanges = pendingRanges.toList()
            val paginated = TextPaginator.paginate(pendingText, paint, width, height, lineSpacing)
            paginated.forEach { page ->
                val slices = sliceRange(localRanges, page.start, page.end)
                pages.add(
                    DisplayPage.TextPage(
                        text = pendingText.subSequence(page.start, page.end),
                        slices = slices,
                        indexInUnit = pages.size,
                    ),
                )
            }
            pendingText = SpannableStringBuilder()
            pendingRanges = ArrayList()
        }

        for (block in section.blocks) {
            when (block) {
                is TextBlock -> {
                    if (pendingText.isNotEmpty()) {
                        // Contents entries stay one line apart even in 高度还原 mode,
                        // where normal paragraphs get a blank line between them.
                        val blankLine = fidelity && !isEntry(lastTextBlock) && !isEntry(block)
                        pendingText.append(if (blankLine) separator else "\n")
                    }
                    val start = pendingText.length
                    pendingText.append(block.text)
                    val end = pendingText.length
                    applyStyles(pendingText, block, start, end)
                    // A visible gap after a paragraph. Blocks that are only a hard
                    // wrapped line (no sentence end) must stay tight, or every line
                    // would look like its own paragraph.
                    val endsParagraph = block.headingLevel > 0 ||
                        (block.text.lastOrNull()?.let { it in "。！？…”』」.!?:：" } == true)
                    if (end > start && !isEntry(block) && endsParagraph) {
                        val factor = if (fidelity) 0.25f else 0.65f
                        val extra = (paint.textSize * lineSpacing * factor).toInt()
                        pendingText.setSpan(
                            ParagraphGapSpan(extra, end - 1),
                            end - 1,
                            end,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                    pendingRanges.add(block.id to (start until end))
                    lastTextBlock = block
                }
                is PageImageBlock -> {
                    // In text mode the page image is skipped: the OCR blocks that
                    // follow carry the readable content.
                    if (imageMode) {
                        flushText()
                        pages.add(DisplayPage.ImagePage(block.pageIndex, block.id, true, pages.size))
                    }
                }
                is ImageBlock -> {
                    flushText()
                    pages.add(
                        DisplayPage.ImagePage(
                            pageIndex = block.pageIndex,
                            blockId = block.id,
                            wholePage = false,
                            indexInUnit = pages.size,
                            bbox = block.bbox,
                        ),
                    )
                }
            }
        }
        flushText()

        return pages.mapIndexed { index, page ->
            when (page) {
                is DisplayPage.TextPage -> page.copy(indexInUnit = index)
                is DisplayPage.ImagePage -> page.copy(indexInUnit = index)
            }
        }
    }

    private fun isEntry(block: TextBlock?): Boolean {
        if (block == null) return false
        val height = block.bbox?.height() ?: 1f
        val text = block.text.trim()
        if (height >= 0.06f || text.length > 80) return false
        // A one-line paragraph still ends with sentence punctuation; a contents
        // entry / index line usually does not.
        val last = text.lastOrNull() ?: return false
        return last !in ".?!。！？…”』」"
    }

    private fun applyStyles(builder: SpannableStringBuilder, block: TextBlock, start: Int, end: Int) {
        if (block.headingLevel > 0) {
            val ratio = if (block.headingLevel == 1) 1.3f else 1.12f
            builder.setSpan(RelativeSizeSpan(ratio), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return
        }
        for (run in block.runs) {
            val from = (start + run.start).coerceIn(start, end)
            val to = (start + run.end).coerceIn(from, end)
            if (to <= from) continue
            if (run.bold || run.italic) {
                val style = when {
                    run.bold && run.italic -> Typeface.BOLD_ITALIC
                    run.bold -> Typeface.BOLD
                    else -> Typeface.ITALIC
                }
                builder.setSpan(StyleSpan(style), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (run.sizeRatio > 1.02f) {
                builder.setSpan(RelativeSizeSpan(run.sizeRatio), from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun sliceRange(
        ranges: List<Pair<String, IntRange>>,
        pageStart: Int,
        pageEnd: Int,
    ): List<TextSlice> {
        val slices = ArrayList<TextSlice>()
        for ((blockId, range) in ranges) {
            val from = maxOf(pageStart, range.first)
            val to = minOf(pageEnd, range.last + 1)
            if (to <= from) continue
            slices.add(
                TextSlice(
                    blockId = blockId,
                    start = from - range.first,
                    end = to - range.first,
                    pageStart = from - pageStart,
                    pageEnd = to - pageStart,
                ),
            )
        }
        return slices
    }

    /** Locates the display page that contains [blockId] at [offset]. */
    fun findPage(pages: List<DisplayPage>, blockId: String, offset: Int): Int {
        if (blockId.isEmpty()) return 0
        var fallback = -1
        pages.forEachIndexed { index, page ->
            when (page) {
                is DisplayPage.TextPage -> {
                    val slice = page.slices.firstOrNull { it.blockId == blockId } ?: return@forEachIndexed
                    // Half-open range: an offset exactly at a page boundary belongs
                    // to the NEXT page, not the one that ends there.
                    if (offset >= slice.start && offset < slice.end) return index
                    if (offset >= slice.start) fallback = index
                }
                is DisplayPage.ImagePage -> if (page.blockId == blockId) return index
            }
        }
        return fallback
    }
}
