package com.dsh.novelreader.pdf

import android.content.Context
import android.net.Uri
import com.dsh.novelreader.data.Book
import com.dsh.novelreader.data.BookStore
import com.dsh.novelreader.data.BookType
import com.dsh.novelreader.data.PageImageBlock
import com.dsh.novelreader.data.Section
import com.dsh.novelreader.data.SectionState
import com.dsh.novelreader.data.TextBlock
import com.dsh.novelreader.util.TextUtil
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Imports a picked PDF/TXT into the library.
 *
 * PDF pages are classified per page:
 *  - a page with a real text layer becomes [TextBlock]s (extracted by
 *    [PageLayoutAnalyzer]) and is readable immediately;
 *  - a page with no usable text layer becomes a single [PageImageBlock], which
 *    is displayed as the original page image and additionally queued for OCR.
 *
 * Nothing is rendered here: the page bitmap is produced lazily by the reader, so
 * importing a 500-page scan never allocates a bitmap.
 */
class Importer(context: Context, private val store: BookStore) {

    private val appContext = context.applicationContext

    suspend fun importPdf(
        uri: Uri,
        id: String,
        title: String,
        onProgress: (done: Int, total: Int) -> Unit,
    ): Book = withContext(Dispatchers.IO) {
        val dest = File(store.bookDir(id), "source.pdf")
        copyUri(uri, dest)

        val document = PDDocument.load(dest)
        try {
            val total = document.numberOfPages
            onProgress(0, total)
            val analyzer = PageLayoutAnalyzer()
            for (pageIndex in 0 until total) {
                coroutineContext.ensureActive()
                store.writeSection(id, buildSection(analyzer, document, pageIndex))
                onProgress(pageIndex + 1, total)
            }
            // Bookmark tree is separate from page layout: cache it once here.
            runCatching { store.saveOutline(id, PdfOutline.read(dest)) }
            Book(
                id = id,
                title = title,
                type = BookType.PDF,
                totalSections = total,
                updatedAt = System.currentTimeMillis(),
            )
        } finally {
            runCatching { document.close() }
        }
    }

    /**
     * Re-runs layout analysis on an already imported PDF, keeping OCR results.
     * Used by the reader's "重新解析版面" action so a book imported with an older
     * build (e.g. one that glued TOC lines together) does not need re-picking.
     */
    suspend fun rebuildLayout(book: Book, onProgress: (done: Int, total: Int) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val dest = store.sourceFile(book)
            if (!dest.exists()) error("找不到原始 PDF，请重新导入")
            val document = PDDocument.load(dest)
            try {
                val analyzer = PageLayoutAnalyzer()
                val total = document.numberOfPages
                onProgress(0, total)
                for (pageIndex in 0 until total) {
                    coroutineContext.ensureActive()
                    val existing = store.readSection(book.id, pageIndex)
                    // OCR text and confirmed blank pages are final; never throw them away.
                    if (existing?.state != SectionState.OCR && existing?.state != SectionState.BLANK) {
                        store.writeSection(book.id, buildSection(analyzer, document, pageIndex))
                    }
                    onProgress(pageIndex + 1, total)
                }
                runCatching { store.saveOutline(book.id, PdfOutline.read(dest)) }
                total
            } finally {
                runCatching { document.close() }
            }
        }

    private fun buildSection(
        analyzer: PageLayoutAnalyzer,
        document: PDDocument,
        pageIndex: Int,
    ): Section {
        val page = runCatching { analyzer.analyze(document, pageIndex) }.getOrNull()
        val blocks = page?.blocks.orEmpty()
        val text = blocks.filterIsInstance<TextBlock>().joinToString("\n") { it.text }
        return if (TextUtil.looksLikeTextLayer(text)) {
            Section(pageIndex, blocks, SectionState.TEXT, "TEXT")
        } else {
            Section(
                index = pageIndex,
                blocks = listOf(PageImageBlock("p${pageIndex}b0", pageIndex)) +
                    blocks.filterIsInstance<TextBlock>(),
                state = SectionState.PENDING,
                source = "",
            )
        }
    }

    suspend fun importTxt(uri: Uri, id: String, title: String): Book = withContext(Dispatchers.IO) {
        val dest = File(store.bookDir(id), "source.txt")
        copyUri(uri, dest)
        val text = TextUtil.decode(dest.readBytes())
        val chunks = TextUtil.splitIntoSections(text)
        if (chunks.isEmpty()) {
            store.writeSection(
                id,
                Section(0, listOf(TextBlock("p0b0", 0, null, "")), SectionState.TEXT, "TEXT"),
            )
        } else {
            chunks.forEachIndexed { index, chunk ->
                coroutineContext.ensureActive()
                store.writeSection(
                    id,
                    Section(index, listOf(TextBlock("p${index}b0", index, null, chunk)), SectionState.TEXT, "TEXT"),
                )
            }
        }
        Book(
            id = id,
            title = title,
            type = BookType.TXT,
            totalSections = maxOf(chunks.size, 1),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** Copies a content:// stream into app-private storage (the only copy we keep). */
    private fun copyUri(uri: Uri, dest: File) {
        dest.parentFile?.mkdirs()
        val input = appContext.contentResolver.openInputStream(uri)
            ?: error("无法打开所选文件（权限可能已失效，请重新导入）")
        input.use { source ->
            dest.outputStream().use { target -> source.copyTo(target, DEFAULT_BUFFER_SIZE) }
        }
    }
}

/** Placeholder kept for the first extraction pass; replaced by PageLayoutAnalyzer. */
internal class LegacyTextExtractor {
    fun extractPage(document: PDDocument, pageIndex: Int): String {
        val stripper = PDFTextStripper().apply {
            startPage = pageIndex + 1
            endPage = pageIndex + 1
            sortByPosition = true
        }
        return stripper.getText(document)
    }
}
