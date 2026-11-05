package com.dsh.novelreader.ocr

import android.graphics.RectF
import com.dsh.novelreader.data.Block
import com.dsh.novelreader.data.StyleRun
import com.dsh.novelreader.data.TextBlock
import com.google.mlkit.vision.text.Text
import kotlin.math.abs

/**
 * Converts an ML Kit result into the same block model the PDF text layer uses.
 *
 * Every recognised block keeps its bounding box (normalised to the rendered
 * bitmap) and its lines are merged back into a paragraph, so OCR pages get the
 * same block ids, reading order and heading hints as text pages.
 */
object OcrBlocks {

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
            val text = joinLines(lines)
            RawBlock(box, text, heights)
        }
        if (raw.isEmpty()) {
            val fallback = result.text.trim()
            return if (fallback.isEmpty()) {
                emptyList()
            } else {
                listOf(TextBlock("p${pageIndex}b1", pageIndex, null, fallback))
            }
        }
        val median = raw.map { it.maxHeight }.sorted().let {
            if (it.isEmpty()) 1f else it[it.size / 2].coerceAtLeast(1f)
        }
        return raw.mapIndexed { index, item ->
            val ratio = item.maxHeight / median
            val heading = when {
                ratio >= 1.45f && item.text.length <= 40 -> 1
                ratio >= 1.2f && item.text.length <= 60 -> 2
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
                text = item.text,
                runs = buildRuns(item.text, ratio),
                headingLevel = heading,
            )
        }
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

    private class RawBlock(val box: android.graphics.Rect, val text: String, val heights: List<Float>) {
        val maxHeight: Float = heights.maxOrNull() ?: 1f
    }
}
