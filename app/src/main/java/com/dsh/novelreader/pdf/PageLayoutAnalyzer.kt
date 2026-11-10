package com.dsh.novelreader.pdf

import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import com.dsh.novelreader.data.Block
import com.dsh.novelreader.data.ImageBlock
import com.dsh.novelreader.data.StyleRun
import com.dsh.novelreader.data.TextBlock
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Layout analysis for one PDF page.
 *
 * All geometry stays in PDF points while lines are grouped (so font size and
 * baseline distance are comparable), and is normalised to 0..1 only on the
 * finished blocks.
 *
 *  - lines are grouped into paragraphs with an ADAPTIVE vertical threshold
 *    derived from the page's own median line advance, so a document with 1.9x
 *    leading is not split into one block per line;
 *  - running headers/footers and bare page numbers are dropped;
 *  - a clear two-column page is read column by column;
 *  - font size / bold / italic are kept per character as [StyleRun]s relative
 *    to the page's body size, and an oversized short block becomes a heading.
 */
class PageLayoutAnalyzer {

    fun analyze(document: PDDocument, pageIndex: Int): PageAnalysis {
        val collector = LineCollector().apply {
            startPage = pageIndex + 1
            endPage = pageIndex + 1
            sortByPosition = true
        }
        val raw = runCatching { collector.getText(document) }.getOrDefault("")
        val page = document.getPage(pageIndex)
        val pageWidth = page.mediaBox.width
        val pageHeight = page.mediaBox.height
        if (pageWidth <= 0f || pageHeight <= 0f) return PageAnalysis(emptyList(), raw)

        val lines = collector.lines.map { it.toLine() }.filter { it.text.isNotBlank() }
        if (lines.isEmpty()) return PageAnalysis(emptyList(), raw)

        val body = dropChrome(lines, pageHeight)
        if (body.isEmpty()) return PageAnalysis(emptyList(), raw)

        val bodySize = medianSize(body)
        val ordered = splitColumns(body, pageWidth).flatMap { column -> column.sortedBy { it.top } }
        val textBlocks = groupBlocks(ordered, bodySize, pageWidth, pageHeight)
        val figures = collectFigures(page, pageWidth, pageHeight)
        val merged = insertFigures(textBlocks, figures)
        return PageAnalysis(
            blocks = merged.mapIndexed { index, block ->
                when (block) {
                    is TextBlock -> block.copy(id = "p${pageIndex}b$index", pageIndex = pageIndex)
                    is ImageBlock -> block.copy(id = "p${pageIndex}b$index", pageIndex = pageIndex)
                    else -> block
                }
            },
            rawText = raw,
        )
    }

    /**
     * Embedded figures with their page position, collected from the content
     * stream's image operators (the CTM maps the unit square onto the image).
     * Decorations (<2% of the page) and full-page scans (>60%) are dropped.
     */
    private fun collectFigures(page: PDPage, pageWidth: Float, pageHeight: Float): List<RectF> {
        val engine = runCatching { FigureCollector(page) }.getOrNull() ?: return emptyList()
        runCatching { engine.processPage(page) }
        val pageArea = pageWidth * pageHeight
        return engine.boxes.mapNotNull { box ->
            val rect = RectF(
                (box.left / pageWidth).coerceIn(0f, 1f),
                ((pageHeight - box.bottom) / pageHeight).coerceIn(0f, 1f),
                (box.right / pageWidth).coerceIn(0f, 1f),
                ((pageHeight - box.top) / pageHeight).coerceIn(0f, 1f),
            )
            val area = (rect.width() * pageWidth) * (rect.height() * pageHeight)
            if (rect.width() < 0.05f || rect.height() < 0.05f) return@mapNotNull null
            val ratio = if (pageArea <= 0f) 0f else area / pageArea
            if (ratio < 0.02f || ratio > 0.6f) null else rect
        }
    }

    /** Places each figure before the first text block that starts below it. */
    private fun insertFigures(blocks: List<Block>, figures: List<RectF>): List<Block> {
        if (figures.isEmpty()) return blocks
        val result = ArrayList<Block>(blocks.size + figures.size)
        val pending = figures.sortedBy { it.top }.toMutableList()
        for (block in blocks) {
            val top = block.bbox?.top ?: 0f
            while (pending.isNotEmpty() && pending.first().top <= top) {
                val box = pending.removeAt(0)
                result.add(ImageBlock("", -1, box))
            }
            result.add(block)
        }
        pending.forEach { result.add(ImageBlock("", -1, it)) }
        return result
    }

    /** Captures image draw operations with their transformed bounding boxes. */
    private class FigureCollector(page: PDPage) : PDFGraphicsStreamEngine(page) {
        val boxes = ArrayList<RectF>()

        override fun drawImage(pdImage: PDImage?) {
            val ctm = graphicsState.currentTransformationMatrix
            val p0 = ctm.transformPoint(0f, 0f)
            val p1 = ctm.transformPoint(1f, 0f)
            val p2 = ctm.transformPoint(0f, 1f)
            val p3 = ctm.transformPoint(1f, 1f)
            val left = minOf(p0.x, p1.x, p2.x, p3.x)
            val right = maxOf(p0.x, p1.x, p2.x, p3.x)
            val bottom = minOf(p0.y, p1.y, p2.y, p3.y)
            val top = maxOf(p0.y, p1.y, p2.y, p3.y)
            if (right - left > 1f && top - bottom > 1f) {
                boxes.add(RectF(left, bottom, right, top))
            }
        }

        override fun appendRectangle(p0: PointF?, p1: PointF?, p2: PointF?, p3: PointF?) = Unit
        override fun clip(fillType: Path.FillType?) = Unit
        override fun moveTo(x: Float, y: Float) = Unit
        override fun lineTo(x: Float, y: Float) = Unit
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = Unit
        override fun getCurrentPoint(): PointF = PointF()
        override fun closePath() = Unit
        override fun endPath() = Unit
        override fun strokePath() = Unit
        override fun fillPath(fillType: Path.FillType?) = Unit
        override fun fillAndStrokePath(fillType: Path.FillType?) = Unit
        override fun shadingFill(shadingName: COSName?) = Unit
    }

    /** Running headers/footers: outermost 7% of the page, short or a page number. */
    private fun dropChrome(lines: List<Line>, pageHeight: Float): List<Line> = lines.filterNot { line ->
        val band = line.top < pageHeight * 0.07f || line.bottom > pageHeight * 0.93f
        band && (line.text.length < 64 || PAGE_NUMBER.matches(line.text.trim()))
    }

    /** Two columns when a clean central gutter exists on both sides. */
    private fun splitColumns(lines: List<Line>, pageWidth: Float): List<List<Line>> {
        if (lines.size < 6) return listOf(lines)
        val left = lines.filter { it.right <= pageWidth * 0.62f }
        val right = lines.filter { it.left >= pageWidth * 0.38f }
        val clean = left.size >= 3 && right.size >= 3 &&
            left.maxOf { it.right } <= right.minOf { it.left } + pageWidth * 0.02f
        return if (clean) listOf(left, right) else listOf(lines)
    }

    private fun groupBlocks(
        lines: List<Line>,
        bodySize: Float,
        pageWidth: Float,
        pageHeight: Float,
    ): List<Block> {
        // Adaptive paragraph gap: the page's own median line advance * 1.35.
        val advances = lines.zipWithNext { a, b -> b.bottom - a.bottom }
            .filter { it > 0.5f && it < bodySize * 4f }
            .sorted()
        val medianAdvance = if (advances.isEmpty()) bodySize * 1.4f else advances[advances.size / 2]
        val paragraphGap = max(medianAdvance * 1.25f, bodySize * 1.15f)

        val blocks = ArrayList<Block>()
        var current = ArrayList<Line>()
        // Indentation is measured against the page's leftmost text line, so a
        // single TOC entry still knows how far it is indented.
        val pageLeft = lines.minOfOrNull { it.left } ?: 0f
        val pageMaxRight = lines.maxOfOrNull { it.right } ?: 0f

        fun flush() {
            if (current.isEmpty()) return
            val maxRight = current.maxOf { it.right }
            // A block whose lines are mostly much shorter than its widest line is
            // line-oriented content (table of contents, index, list of figures):
            // every line is its own entry, so never join them into a paragraph.
            val shortCount = current.count { it.right < maxRight - bodySize * 1.2f }
            val entryStarts = current.count { CONTENTS_START.containsMatchIn(it.text.trim()) }
            val contentsLike = (current.size >= 2 &&
                current.count {
                    LEADER_DOTS.containsMatchIn(it.text) || TRAILING_PAGE.containsMatchIn(it.text.trim())
                } * 2 >= current.size) ||
                // Contents pages without dot leaders or page numbers still have
                // one numbered entry per line.
                (current.size >= 4 && entryStarts * 2 > current.size) ||
                (entryStarts >= 3 && current.size >= 5)
            val lineOriented = contentsLike || (current.size >= 3 && shortCount * 2 > current.size)

            if (lineOriented) {
                val minLeft = minOf(pageLeft, current.minOf { it.left })
                // A long contents entry wraps in the PDF; its continuation must be
                // joined back to the entry instead of becoming a second entry.
                val entries = ArrayList<Triple<Line, String, Boolean>>()
                for (line in current) {
                    val text = normalizeEntry(line.text.trim())
                    // A line that only carries the dot leader has nothing to read.
                    if (text.isEmpty() || text.replace("…", "").isBlank()) continue
                    val previous = entries.lastOrNull()
                    val previousFull = previous != null &&
                        previous.first.right >= pageMaxRight - bodySize * 1.2f
                    // Only a short wrap remainder ("……的进" + "攻", "……处布" + "告")
                    // is joined; a full entry line starts a new entry.
                    val nextIsShort = (line.right - line.left) < bodySize * 6.5f
                    val previousComplete = previous != null &&
                        previous.second.trimEnd().lastOrNull()?.isDigit() == true
                    val startsEntry = CONTENTS_START.containsMatchIn(text)
                    if (previous != null && !startsEntry && previousFull && nextIsShort && !previousComplete) {
                        val merged = previous.second + text
                        entries[entries.lastIndex] = Triple(previous.first, merged, true)
                    } else {
                        entries.add(Triple(line, text, false))
                    }
                }
                entries.forEach { (line, text, merged) ->
                    val indent = line.left - minLeft
                    val level = when {
                        indent > bodySize * 1.2f -> 2
                        indent > bodySize * 0.5f -> 1
                        else -> 0
                    }
                    val prefix = "  ".repeat(level)
                    val runs = if (merged) {
                        emptyList()
                    } else {
                        buildRuns(listOf(line), bodySize).map {
                            it.copy(start = it.start + prefix.length, end = it.end + prefix.length)
                        }
                    }
                    blocks.add(blockFor(listOf(line), prefix + text, runs, bodySize, pageWidth, pageHeight))
                }
            } else {
                // A block can also be a single TOC entry (it was flushed by the
                // numbering rule), so normalise its spacing and keep its indent.
                var text = normalizeEntry(joinLines(current))
                var runs = buildRuns(current, bodySize)
                if (current.size == 1 && TOC_ENTRY.containsMatchIn(text)) {
                    val line = current.first()
                    val indent = line.left - pageLeft
                    val level = when {
                        indent > bodySize * 1.2f -> 2
                        indent > bodySize * 0.5f -> 1
                        else -> 0
                    }
                    if (level > 0) {
                        val prefix = "  ".repeat(level)
                        text = prefix + text
                        runs = runs.map { it.copy(start = it.start + prefix.length, end = it.end + prefix.length) }
                    }
                }
                if (text.isNotBlank()) {
                    blocks.add(blockFor(current, text, runs, bodySize, pageWidth, pageHeight))
                }
            }
            current = ArrayList()
        }

        var previous: Line? = null
        for (line in lines) {
            val prev = previous
            if (prev != null) {
                val advance = line.bottom - prev.bottom
                val sizeJump = abs(line.fontSize - prev.fontSize) > prev.fontSize * 0.3f
                val startsEntry = TOC_ENTRY.containsMatchIn(line.text.trim())
                // Indented first lines are the paragraph marker in books without
                // paragraph spacing; continuation lines sit on the page margin.
                val indented = line.left - pageLeft > bodySize * 0.6f
                if (advance > paragraphGap || sizeJump || startsEntry || indented) flush()
            }
            current.add(line)
            previous = line
        }
        flush()
        return blocks
    }

    /**
     * PDF typesetting often places the space after a section number by
     * positioning, not by a space glyph, so "7.1.1 编写…" arrives as
     * "7.1.1编写…". Restore the separator for numbered/章节 entries.
     */
    private fun normalizeEntry(text: String): String {
        var value = LEADER_DOTS.replace(text, " …… ")
        // "……3一、一个不同的国家?" — the page number of one entry is glued to
        // the next entry; split them back onto separate lines.
        value = GLUED_ENTRY.replace(value, "\n")
        // OCR / text layers often drop the leader and leave "译者的话3".
        value = TRAILING_PAGE.replace(value, "$1  $2")
        if (NUMBER_PREFIX.containsMatchIn(value)) {
            value = value.replaceFirst(NUMBER_PREFIX, "$1 ")
        }
        if (CHAPTER_PREFIX.containsMatchIn(value)) {
            value = value.replaceFirst(CHAPTER_PREFIX, "$1 ")
        }
        return value
    }

    private fun blockFor(
        lines: List<Line>,
        text: String,
        runs: List<StyleRun>,
        bodySize: Float,
        pageWidth: Float,
        pageHeight: Float,
    ): TextBlock {
        val size = lines.map { it.fontSize }.average().toFloat()
        val headingLevel = when {
            size >= bodySize * 1.25f -> 1
            size >= bodySize * 1.08f -> 2
            else -> 0
        }
        return TextBlock(
            id = "",
            pageIndex = -1,
            bbox = RectF(
                (lines.minOf { it.left } / pageWidth).coerceIn(0f, 1f),
                (lines.minOf { it.top } / pageHeight).coerceIn(0f, 1f),
                (lines.maxOf { it.right } / pageWidth).coerceIn(0f, 1f),
                (lines.maxOf { it.bottom } / pageHeight).coerceIn(0f, 1f),
            ),
            text = text,
            runs = runs,
            headingLevel = headingLevel,
        )
    }

    /** Join wrapped lines: no separator for CJK, a space between Latin words. */
    private fun joinLines(lines: List<Line>): String {
        val builder = StringBuilder()
        lines.forEachIndexed { index, line ->
            if (index > 0) {
                val left = builder.lastOrNull()
                val right = line.text.firstOrNull()
                if (left != null && right != null && left.code < 128 && right.code < 128) {
                    builder.append(' ')
                }
            }
            builder.append(line.text.trim())
        }
        return builder.toString().trim()
    }

    private fun buildRuns(lines: List<Line>, bodySize: Float): List<StyleRun> {
        val runs = ArrayList<StyleRun>()
        var start = 0
        var bold = false
        var italic = false
        var ratio = 1f
        var started = false
        var index = 0
        var previousChar: Char? = null

        fun push(end: Int) {
            if (!started || end <= start) return
            if (bold || italic || abs(ratio - 1f) > 0.03f) {
                runs.add(StyleRun(start, end, bold, italic, ratio))
            }
        }

        for (line in lines) {
            val first = line.text.firstOrNull()
            if (previousChar != null && first != null && previousChar.code < 128 && first.code < 128) {
                index++
            }
            for (ch in line.chars) {
                if (ch.isSpace) {
                    index++
                    continue
                }
                val charRatio = (ch.size / bodySize).coerceIn(0.6f, 2.6f)
                if (!started) {
                    started = true
                    bold = ch.bold
                    italic = ch.italic
                    ratio = charRatio
                    start = index
                } else if (bold != ch.bold || italic != ch.italic || abs(ratio - charRatio) > 0.03f) {
                    push(index)
                    bold = ch.bold
                    italic = ch.italic
                    ratio = charRatio
                    start = index
                }
                index++
            }
            previousChar = line.text.lastOrNull() ?: previousChar
        }
        push(index)
        return runs
    }

    private fun medianSize(lines: List<Line>): Float {
        val sizes = lines.map { it.fontSize }.sorted()
        return if (sizes.isEmpty()) 12f else sizes[sizes.size / 2]
    }

    data class PageAnalysis(val blocks: List<Block>, val rawText: String)

    private class CharStyle(
        val bold: Boolean,
        val italic: Boolean,
        val size: Float,
        val isSpace: Boolean,
    )

    private class Line(
        val text: String,
        val top: Float,
        val bottom: Float,
        val left: Float,
        val right: Float,
        val fontSize: Float,
        val chars: List<CharStyle>,
    ) {
        val height: Float get() = (bottom - top).coerceAtLeast(0.001f)
    }

    private class LineCollector : PDFTextStripper() {
        val lines = ArrayList<RawLine>()

        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            val positions = textPositions.orEmpty().filter { it.unicode?.isNotBlank() == true }
            if (positions.isEmpty()) return
            lines.add(RawLine(positions))
        }
    }

    private class RawLine(val positions: List<TextPosition>) {
        fun toLine(): Line {
            val text = StringBuilder()
            val styles = ArrayList<CharStyle>()
            var top = Float.MAX_VALUE
            var bottom = 0f
            var left = Float.MAX_VALUE
            var right = 0f
            var maxSize = 0f
            val lineHeight = positions.maxOfOrNull { it.heightDir } ?: 1f
            var previous: TextPosition? = null
            var previousIsSpace = true

            for (position in positions) {
                val char = position.unicode ?: continue
                if (char.isEmpty()) continue

                // Justified text often positions spaces instead of emitting a space
                // glyph; rebuild the word separator from the horizontal gap.
                val prev = previous
                if (prev != null && !previousIsSpace && !char[0].isWhitespace()) {
                    val gap = position.xDirAdj - (prev.xDirAdj + prev.widthDirAdj)
                    val spaceWidth = position.widthOfSpace.takeIf { it > 0.1f }
                        ?: (position.fontSizeInPt * 0.25f)
                    if (gap > spaceWidth * 0.45f) {
                        text.append(' ')
                        styles.add(
                            CharStyle(
                                bold = false,
                                italic = false,
                                size = position.fontSizeInPt,
                                isSpace = true,
                            ),
                        )
                    }
                }

                text.append(char)
                val fontName = position.font?.name.orEmpty()
                val size = position.fontSizeInPt
                maxSize = max(maxSize, size)
                styles.add(
                    CharStyle(
                        bold = BOLD.containsMatchIn(fontName),
                        italic = ITALIC.containsMatchIn(fontName),
                        size = size,
                        isSpace = char[0].isWhitespace(),
                    ),
                )
                top = min(top, position.yDirAdj - lineHeight)
                bottom = max(bottom, position.yDirAdj)
                left = min(left, position.xDirAdj)
                right = max(right, position.xDirAdj + position.widthDirAdj)
                previous = position
                previousIsSpace = char[0].isWhitespace()
            }
            if (text.isEmpty()) top = 0f
            return Line(
                text = text.toString(),
                top = top,
                bottom = bottom,
                left = left,
                right = right,
                fontSize = maxSize.coerceAtLeast(1f),
                chars = styles,
            )
        }
    }

    private companion object {
        val PAGE_NUMBER = Regex("^[-–—\\s]*(\\d{1,4}|[ivxlcdmIVXLCDM]{1,6})[-–—\\s]*$")
        val BOLD = Regex("bold|black|heavy|semibold|demi", RegexOption.IGNORE_CASE)
        val ITALIC = Regex("italic|oblique", RegexOption.IGNORE_CASE)

        /**
         * A line that opens a numbered entry ("7.2.1 使用 while 循环", "第8章 函数",
         * "A. 附录") always starts a new block: in a table of contents these lines
         * are separate entries even when the previous line happened to be full width.
         */
        val TOC_ENTRY = Regex("^(\\d+(\\.\\d+)+|第[\\d一二三四五六七八九十百千]+[章节]|[A-Z]\\.)\\s*\\S")

        /** "7.1.1编写" → group 1 = "7.1.1", followed by a non-space, non-digit. */
        val NUMBER_PREFIX = Regex("^(\\d+(?:\\.\\d+)+)(?=[^\\s\\d.])")

        /** "第7章用户输入" / "第八章函数" → group 1 = the chapter label. */
        val CHAPTER_PREFIX = Regex("^(第[\\d一二三四五六七八九十百千]+[章节])(?=[^\\s])")

        /** Dot leaders of a contents entry: "......" / ". . . ." / "…………". */
        val LEADER_DOTS = Regex("[.．。·…]{3,}|(?:\\s*[.．·]\\s*){3,}")

        /** "……3一、一个不同的国家?" → the page number belongs to the previous entry. */
        val GLUED_ENTRY = Regex("(?<=\\d{1,4})(?=[一二三四五六七八九十百]{1,3}、|附\\s*录)")

        /** "译者的话3" → title and page number, when the leader was dropped. */
        val TRAILING_PAGE = Regex("([\\u4E00-\\u9FFF）)】])(?:[.．·…\\s]*)(\\d{1,4})$")

        /** A contents line that begins a new entry: "（一）…", "五、…", "附录", "12 长征". */
        val CONTENTS_START =
            Regex("^[（(]?[一二三四五六七八九十百\\d]{1,3}[)）、.．]|^第[\\d一二三四五六七八九十百]+[章节]|^附\\s*录|^\\d{1,3}[、.]")
    }
}
