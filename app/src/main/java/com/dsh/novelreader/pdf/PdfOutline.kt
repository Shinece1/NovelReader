package com.dsh.novelreader.pdf

import com.dsh.novelreader.data.TocItem
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.File

/**
 * Reads the PDF's own bookmark tree (outline) instead of guessing chapters from
 * the text. Entries point straight at a page, so the reader can jump without
 * running layout analysis again.
 */
object PdfOutline {

    private const val MAX_ITEMS = 2000
    private const val MAX_LEVEL = 4

    fun read(file: File): List<TocItem> {
        if (!file.exists()) return emptyList()
        val document = runCatching { PDDocument.load(file) }.getOrNull() ?: return emptyList()
        return document.use { doc ->
            val root = runCatching { doc.documentCatalog.documentOutline }.getOrNull()
                ?: return@use emptyList()
            val items = ArrayList<TocItem>()
            walk(doc, runCatching { root.firstChild }.getOrNull(), 0, items)
            items
        }
    }

    private fun walk(doc: PDDocument, start: PDOutlineItem?, level: Int, out: MutableList<TocItem>) {
        var item = start
        while (item != null && out.size < MAX_ITEMS) {
            val current: PDOutlineItem = item
            val title = runCatching { current.title }.getOrNull()?.trim().orEmpty()
            val page = pageIndexOf(doc, current)
            if (title.isNotEmpty() && page >= 0) {
                out.add(TocItem(title = title, pageIndex = page, level = level.coerceAtMost(MAX_LEVEL)))
            }
            walk(doc, runCatching { current.firstChild }.getOrNull(), level + 1, out)
            item = runCatching { current.nextSibling }.getOrNull()
        }
    }

    private fun pageIndexOf(doc: PDDocument, item: PDOutlineItem): Int {
        val destination = runCatching { item.destination }.getOrNull() as? PDPageDestination
            ?: return -1
        val page = runCatching { destination.page }.getOrNull()
        if (page != null) {
            // Page wrappers are re-created per lookup, so compare by COS object
            // instead of by Java identity.
            val direct = runCatching { doc.pages.indexOf(page) }.getOrDefault(-1)
            if (direct >= 0) return direct
            for (index in 0 until doc.numberOfPages) {
                val candidate = runCatching { doc.pages[index] }.getOrNull() ?: continue
                if (candidate.cosObject == page.cosObject) return index
            }
        }
        // Some writers only store the page number; trust it when it is in range.
        val number = runCatching { destination.pageNumber }.getOrDefault(-1)
        return if (number in 0 until doc.numberOfPages) number else -1
    }
}
