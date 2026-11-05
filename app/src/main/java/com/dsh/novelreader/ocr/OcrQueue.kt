package com.dsh.novelreader.ocr

import android.content.Context
import android.graphics.Bitmap
import com.dsh.novelreader.data.Book
import com.dsh.novelreader.data.BookStore
import com.dsh.novelreader.data.PageImageBlock
import com.dsh.novelreader.data.Section
import com.dsh.novelreader.data.SectionState
import com.dsh.novelreader.data.TextBlock
import com.dsh.novelreader.pdf.PdfPageRenderer
import com.dsh.novelreader.util.TextUtil
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Background OCR queue for scanned PDF pages.
 *
 * Design notes:
 *  - A plain app-scoped coroutine job is the "cancellable task queue" the spec
 *    allows; it is not a foreground service, so a cancelled import stops
 *    immediately and leaves nothing running.
 *  - Every finished page is written to [BookStore] before the next page starts,
 *    so an app restart resumes with only the still-PENDING pages.
 *  - One [PdfPageRenderer] and one ML Kit recognizer are reused for the run;
 *    each bitmap is recycled as soon as recognition returns.
 */
object OcrQueue {

    data class Progress(
        val bookId: String,
        val done: Int,
        val total: Int,
        val running: Boolean,
        val failed: Int = 0,
        val lastError: String? = null,
        val cancelled: Boolean = false,
    ) {
        val percent: Int get() = if (total <= 0) 0 else (done * 100 / total)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _progress = MutableStateFlow<Progress?>(null)
    private val _sectionReady = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 128)

    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    /** Emits (bookId, sectionIndex) whenever one section becomes readable. */
    val sectionReady: SharedFlow<Pair<String, Int>> = _sectionReady.asSharedFlow()

    private var job: Job? = null

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Starts (or resumes) recognition for a book.
     *
     * [focusSection] is the page the reader is on: targets are processed in order
     * of distance from it, so the visible page and its neighbours become readable
     * first and the reader can keep turning pages while the rest runs.
     * [onlyFailed] retries pages that previously failed instead of untouched ones.
     */
    fun start(
        context: Context,
        book: Book,
        store: BookStore,
        focusSection: Int = 0,
        onlyFailed: Boolean = false,
    ) {
        if (isRunning) return
        job = scope.launch {
            val targets = (0 until book.totalSections).filter { index ->
                val section = store.readSection(book.id, index) ?: return@filter false
                when {
                    section.state == SectionState.PENDING -> !onlyFailed
                    section.state == SectionState.FAILED -> onlyFailed
                    else -> false
                }
            }.sortedBy { kotlin.math.abs(it - focusSection) }
            if (targets.isEmpty()) {
                _progress.value = null
                return@launch
            }
            _progress.value = Progress(book.id, 0, targets.size, running = true)

            val renderer = runCatching { PdfPageRenderer(store.sourceFile(book)) }.getOrNull()
            if (renderer == null) {
                _progress.value = Progress(book.id, 0, targets.size, running = false, lastError = "无法读取 PDF 源文件")
                return@launch
            }
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            var done = 0
            var failed = 0
            var lastError: String? = null
            try {
                for (pageIndex in targets) {
                    if (!currentCoroutineContext().isActive) break
                    val section = store.readSection(book.id, pageIndex) ?: continue
                    try {
                        val bitmap = renderWithFallback(renderer, pageIndex)
                            ?: error("第 ${pageIndex + 1} 页渲染失败（已尝试 1600/1100/800px）")
                        val width = bitmap.width
                        val height = bitmap.height
                        val ink = inkRatio(bitmap)
                        val result = try {
                            recognizer.recognizeSuspend(bitmap)
                        } finally {
                            bitmap.recycle()
                        }
                        val ocrBlocks = OcrBlocks.toBlocks(result, width, height, pageIndex)
                        val merged = ocrBlocks.filterIsInstance<TextBlock>().joinToString("\n\n") { it.text }
                        // Keep the page image so the reader can still show the original page.
                        val pageImages = section.blocks.filterIsInstance<PageImageBlock>()
                        if (merged.isBlank()) {
                            if (ink < 0.002f) {
                                // A genuinely blank page (chapter separator, blank verso):
                                // not a failure, just nothing to read here.
                                store.writeSection(
                                    book.id,
                                    section.copy(
                                        blocks = pageImages,
                                        state = SectionState.BLANK,
                                        source = "OCR",
                                        error = null,
                                    ),
                                )
                            } else {
                                error("本页有内容但未识别到文字（可能是扫描质量低、竖排或双栏）")
                            }
                        } else {
                            store.writeSection(
                                book.id,
                                section.copy(
                                    blocks = pageImages + ocrBlocks,
                                    state = SectionState.OCR,
                                    source = "OCR",
                                    error = null,
                                ),
                            )
                        }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        failed++
                        lastError = (t.message ?: "识别失败").take(160)
                        store.writeSection(
                            book.id,
                            section.copy(
                                state = SectionState.FAILED,
                                error = (t.message ?: "识别失败").take(160),
                            ),
                        )
                    }
                    done++
                    _progress.value = Progress(
                        bookId = book.id,
                        done = done,
                        total = targets.size,
                        running = true,
                        failed = failed,
                        lastError = lastError,
                    )
                    _sectionReady.tryEmit(book.id to pageIndex)
                }
            } finally {
                runCatching { recognizer.close() }
                runCatching { renderer.close() }
                val current = _progress.value
                _progress.value = current?.copy(
                    running = false,
                    cancelled = current.cancelled || !currentCoroutineContext().isActive,
                )
            }
        }
    }

    /** Cancels the queue; no further page is rendered or recognized. */
    fun cancel() {
        job?.cancel()
        _progress.value = _progress.value?.let { it.copy(running = false, cancelled = true) }
    }

    /** Clears a finished progress record (e.g. after leaving the book). */
    fun dismiss(bookId: String) {
        if (_progress.value?.bookId == bookId && !isRunning) _progress.value = null
    }

    /** Renders a page, stepping the width down when the platform renderer fails. */
    private fun renderWithFallback(renderer: PdfPageRenderer, pageIndex: Int): Bitmap? {
        for (width in intArrayOf(1600, 1100, 800)) {
            val bitmap = runCatching { renderer.render(pageIndex, width) }.getOrNull()
            if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) return bitmap
        }
        return null
    }

    /** Sampled fraction of dark pixels — separates a blank page from a failed scan. */
    private fun inkRatio(bitmap: Bitmap): Float {
        var dark = 0
        var total = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luminance = (r * 299 + g * 587 + b * 114) / 1000
                if (luminance < 180) dark++
                total++
                x += 4
            }
            y += 4
        }
        return if (total == 0) 0f else dark.toFloat() / total
    }

    private suspend fun TextRecognizer.recognizeSuspend(bitmap: Bitmap): Text =        suspendCancellableCoroutine { continuation ->
            process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result: Text -> continuation.resume(result) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
}
