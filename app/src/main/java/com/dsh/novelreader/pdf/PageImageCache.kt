package com.dsh.novelreader.pdf

import android.graphics.Bitmap
import android.util.LruCache
import com.dsh.novelreader.data.Book
import com.dsh.novelreader.data.BookStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Renders and caches page images.
 *
 * The cache key is `bookId:page:width` — the screen width is part of the key,
 * so rotating the device re-renders at the new size instead of up-scaling a
 * stale bitmap. At most a couple of pages are kept in memory and the renderer
 * only ever holds one page open, so a 500-page scan never materialises whole.
 */
class PageImageCache(private val store: BookStore) {

    private val memory = LruCache<String, Bitmap>(2)
    private val mutex = Mutex()
    private var renderer: PdfPageRenderer? = null
    private var rendererBook: String? = null

    suspend fun load(book: Book, pageIndex: Int, targetWidth: Int): Bitmap? =
        withContext(Dispatchers.IO) {
            if (book.type != com.dsh.novelreader.data.BookType.PDF) return@withContext null
            val width = targetWidth.coerceIn(320, 2400)
            val key = String.format(Locale.US, "%s:%d:%d", book.id, pageIndex, width)
            memory.get(key)?.let { return@withContext it }
            mutex.withLock {
                memory.get(key)?.let { return@withLock it }
                runCatching {
                    if (renderer == null || rendererBook != book.id) {
                        closeRenderer()
                        renderer = PdfPageRenderer(store.sourceFile(book))
                        rendererBook = book.id
                    }
                    renderer?.render(pageIndex, width)
                }.getOrNull()?.also { memory.put(key, it) }
            }
        }

    fun clear() {
        memory.evictAll()
        closeRenderer()
    }

    private fun closeRenderer() {
        runCatching { renderer?.close() }
        renderer = null
        rendererBook = null
    }
}
