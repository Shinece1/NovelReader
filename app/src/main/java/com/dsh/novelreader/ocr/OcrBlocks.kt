package com.dsh.novelreader.ocr

import android.graphics.Rect
import android.graphics.RectF
import com.dsh.novelreader.data.Block
import com.dsh.novelreader.data.StyleRun
import com.dsh.novelreader.data.TextBlock
import com.google.mlkit.vision.text.Text
import kotlin.math.abs

/**
 * Converts an ML Kit result into the same block model the PDF text layer uses.
 *
 * Two things happen before a block is emitted:
 *  - fragments that sit on the same visual line are merged (ML Kit often splits
 *    a line at wide gaps such as a filled table of contents);
 *  - lines inside a contents page are kept on separate lines and their dot
 *    leaders normalised, so entries stop running into each other.
 */
object OcrBlocks {

    private val LEADER_DOTS = Regex("[.．。·…]{3,}|(?:\\s*[.．·]\\s*){3,}")
    private val GLUED_ENTRY = Regex("(?<=\\d{1,4})(?=[一二三四五六七八九十百]{1,3}、|附\\s*录)")
    private val TRAILING_PAGE = Regex("([\\u4E00-\\u9FFF）)】])(?:[.．·…\\s]*)(\\d{1,4})$")
    private val SPACES = Regex("[ \\t]{2,}")

    fun toBlocks(
        result: Text,
        bitmapWidth: Int,
        bitmapHeight: Int,
        pageIndex: Int,
    ): List<Block> {
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return emptyList()
        val raw = result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            val lines = block.lines.mapNotNull { it.text?.trim()?.takeIf { t -> t.isNotEmpty() } }
            if (lines.isEmpty()) return@mapNotNull null
            val heights = block.lines.mapNotNull { it.boundingBox?.height()?.toFloat() }
            RawBlock(box, lines, heights)
        }
        if (raw.isEmpty()) {
            val fallback = result.text.trim()
            return if (fallback.isEmpty()) {
                emptyList()
            } else {
                listOf(TextBlock("p${pageIndex}b1", pageIndex, null, fallback))
            }
        }

        val filtered = raw.filterNot { block ->
            // A bare page number near the top/bottom edge is running chrome.
            val text = block.lines.joinToString("").trim()
            val centerY = (block.box.top + block.box.bottom) / 2f / bitmapHeight
            Regex("^\\d{1,4}$").matches(text) && (centerY < 0.05f || centerY > 0.90f)
        }
        val merged = mergeSameLine(if (filtered.isEmpty()) raw else filtered)
        val contentsPage = looksLikeContents(merged.flatMap { it.lines })
        val blocks = if (contentsPage) mergeContentsWraps(merged) else mergeParagraphs(merged)
        val median = blocks.map { it.maxHeight }.sorted().let {
            if (it.isEmpty()) 1f else it[it.size / 2].coerceAtLeast(1f)
        }
        return blocks.mapIndexed { index, item ->
            val text = if (contentsPage) {
                item.lines
                    .filterNot { line -> line.replace("…", "").replace(".", "").isBlank() }
                    .joinToString("\n") { normalizeContentsLine(it) }
            } else {
                joinLines(item.lines)
            }
            val ratio = item.maxHeight / median
            val heading = when {
                contentsPage -> 0
                ratio >= 1.45f && text.length <= 40 -> 1
                ratio >= 1.2f && text.length <= 60 -> 2
                else -> 0
            }
            TextBlock(
                id = "p${pageIndex}b${index + 1}",
                pageIndex = pageIndex,
                bbox = RectF(
                    item.box.left.toFloat() / bitmapWidth,
                    item.box.top.toFloat() / bitmapHeight,
                    item.box.right.toFloat() / bitmapWidth,
                    item.box.bottom.toFloat() / bitmapHeight,
                ),
                text = text,
                runs = buildRuns(text, ratio),
                headingLevel = heading,
            )
        }
    }

    /** Joins blocks that share a visual line, left to right. */
    private fun mergeSameLine(blocks: List<RawBlock>): List<RawBlock> {
        if (blocks.size < 2) return blocks
        val sorted = blocks.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        val out = ArrayList<RawBlock>()
        for (block in sorted) {
            val last = out.lastOrNull()
            if (last != null && last.lines.size == 1 && block.lines.size == 1 &&
                verticalOverlap(last.box, block.box) >= 0.5f
            ) {
                val left = last.lines.first().lastOrNull()
                val right = block.lines.first().firstOrNull()
                val separator = if (left != null && right != null && left.code < 128 && right.code < 128) " " else ""
                val union = Rect(last.box).apply { union(block.box) }
                out[out.lastIndex] = RawBlock(
                    box = union,
                    lines = listOf(last.lines.first() + separator + block.lines.first()),
                    heights = last.heights + block.heights,
                )
            } else {
                out.add(block)
            }
        }
        return out
    }

    private fun verticalOverlap(a: Rect, b: Rect): Float {
        val top = maxOf(a.top, b.top)
        val bottom = minOf(a.bottom, b.bottom)
        val overlap = (bottom - top).toFloat()
        if (overlap <= 0f) return 0f
        val smaller = minOf(a.height(), b.height()).coerceAtLeast(1)
        return overlap / smaller
    }

    /**
     * ML Kit tends to emit one block per visual line. Lines that continue the
     * same sentence are joined back into a paragraph, so the reader shows real
     * paragraphs instead of one line per block (which also made paragraph and
     * line spacing look identical).
     */
    private fun mergeParagraphs(blocks: List<RawBlock>): List<RawBlock> {
        if (blocks.size < 2) return blocks
        val median = blocks.map { it.maxHeight }.sorted().let {
            if (it.isEmpty()) 1f else it[it.size / 2].coerceAtLeast(1f)
        }
        val out = ArrayList<RawBlock>()
        for (block in blocks.sortedWith(compareBy({ it.box.top }, { it.box.left }))) {
            val last = out.lastOrNull()
            val lastText = last?.lines?.lastOrNull()
            val firstText = block.lines.firstOrNull()
            val continues = lastText != null && firstText != null &&
                !SENTENCE_END.containsMatchIn(lastText) &&
                !ENTRY_START.containsMatchIn(firstText.trim()) &&
                block.box.top - last!!.box.bottom < median * 1.1f &&
                block.box.left <= last.box.right + median
            if (continues && last != null) {
                val left = lastText?.lastOrNull()
                val right = firstText?.firstOrNull()
                val separator = if (left != null && right != null && left.code < 128 && right.code < 128) " " else ""
                val mergedLine = (lastText ?: "") + separator + (firstText ?: "")
                val union = Rect(last.box).apply { union(block.box) }
                out[out.lastIndex] = RawBlock(
                    box = union,
                    lines = last.lines.dropLast(1) + mergedLine + block.lines.drop(1),
                    heights = last.heights + block.heights,
                )
            } else {
                out.add(block)
            }
        }
        return out
    }

    private val SENTENCE_END = Regex("[。！？：；…”』」.!?:;]$")
    private val ENTRY_START = Regex("^[（(]?[一二三四五六七八九十百\\d]{1,3}[)）、.．]|^第[\\d一二三四五六七八九十百]+[章节]|^附\\s*录")

    /**
     * A contents page: most lines end with a dot leader and/or a page number.
     * Such lines must stay separate; joining them produced entries like
     * "93三、中国共产党中央委员会……" on one line.
     */
    /**
     * A long contents entry wraps in the scan; the second physical line is a
     * continuation, not a new entry. Join it back so "……的进" + "攻" reads
     * "……的进攻".
     */
    private fun mergeContentsWraps(blocks: List<RawBlock>): List<RawBlock> {
        if (blocks.size < 2) return blocks
        val maxRight = blocks.maxOf { it.box.right }
        val median = blocks.map { it.maxHeight }.sorted().let {
            if (it.isEmpty()) 1f else it[it.size / 2].coerceAtLeast(1f)
        }
        val out = ArrayList<RawBlock>()
        for (block in blocks.sortedWith(compareBy({ it.box.top }, { it.box.left }))) {
            val last = out.lastOrNull()
            val lastText = last?.lines?.lastOrNull()?.trim().orEmpty()
            val firstText = block.lines.firstOrNull()?.trim().orEmpty()
            val continues = last != null &&
                !CONTENTS_START.containsMatchIn(firstText) &&
                last.box.right >= maxRight - median * 1.5f &&
                lastText.lastOrNull()?.isDigit() != true
            if (continues && last != null) {
                val union = Rect(last.box).apply { union(block.box) }
                val left = lastText.lastOrNull()
                val right = firstText.firstOrNull()
                val separator = if (left != null && right != null && left.code < 128 && right.code < 128) " " else ""
                out[out.lastIndex] = RawBlock(
                    box = union,
                    lines = listOf(lastText + separator + firstText) + block.lines.drop(1),
                    heights = last.heights + block.heights,
                )
            } else {
                out.add(block)
            }
        }
        return out
    }

    private val CONTENTS_START =
        Regex("^[（(]?[一二三四五六七八九十百\\d]{1,3}[)）、.．]|^第[\\d一二三四五六七八九十百]+[章节]|^附\\s*录|^\\d{1,3}[、.]")

    private fun looksLikeContents(lines: List<String>): Boolean {
        if (lines.size < 2) return false
        val hits = lines.count { line ->
            LEADER_DOTS.containsMatchIn(line) ||
                Regex("[.．·…]{2,}\\s*\\d{1,4}\\s*$").containsMatchIn(line) ||
                // OCR often drops the dot leader and leaves "南泥湾3".
                Regex("[\\u4E00-\\u9FFF）)】]\\s*\\d{1,4}\\s*$").containsMatchIn(line)
        }
        return hits * 2 >= lines.size
    }

    private fun normalizeContentsLine(line: String): String {
        var text = LEADER_DOTS.replace(line, " …… ")
        text = GLUED_ENTRY.replace(text, "\n")
        // OCR often drops the dot leader and leaves "南泥湾3"; keep the page
        // number recognisable by separating it from the title.
        text = TRAILING_PAGE.replace(text, "$1  $2")
        text = SPACES.replace(text, " ")
        return text.trim()
    }

    private fun joinLines(lines: List<String>): String {
        val builder = StringBuilder()
        lines.forEachIndexed { index, line ->
            if (index > 0) {
                val left = builder.lastOrNull()
                val right = line.firstOrNull()
                if (left != null && right != null && left.code < 128 && right.code < 128) {
                    builder.append(' ')
                }
            }
            builder.append(line)
        }
        return builder.toString().trim()
    }

    /** A block whose font became noticeably larger than body text is emphasised. */
    private fun buildRuns(text: String, ratio: Float): List<StyleRun> {
        if (text.isEmpty()) return emptyList()
        if (abs(ratio - 1f) <= 0.15f) return emptyList()
        return listOf(StyleRun(0, text.length, bold = ratio >= 1.45f, sizeRatio = ratio.coerceAtMost(1.6f)))
    }

    private class RawBlock(val box: Rect, val lines: List<String>, val heights: List<Float>) {
        val maxHeight: Float = heights.maxOrNull() ?: 1f
    }
}
