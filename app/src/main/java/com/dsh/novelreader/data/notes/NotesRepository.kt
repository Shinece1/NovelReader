package com.dsh.novelreader.data.notes

import android.content.Context
import com.dsh.novelreader.data.Book
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bookmarks, annotations and page notes. Everything is anchored to
 * `bookId + original page + blockId + character offset`, so changing the font,
 * rotating the screen or waiting for OCR never invalidates a saved position.
 * The app only ever reads/writes its own database — the PDF is never modified.
 */
class NotesRepository(context: Context) {

    private val dao = NoteDatabase.get(context).noteDao()

    fun bookmarks(bookId: String) = dao.bookmarks(bookId)
    fun annotations(bookId: String) = dao.annotations(bookId)
    fun pageNotes(bookId: String) = dao.pageNotes(bookId)

    suspend fun bookmarkAt(bookId: String, blockId: String, offset: Int): BookmarkEntity? =
        dao.bookmarkAt(bookId, blockId, offset)

    suspend fun addBookmark(bookId: String, pageIndex: Int, blockId: String, offset: Int, preview: String) {
        dao.insertBookmark(
            BookmarkEntity(
                bookId = bookId,
                pageIndex = pageIndex,
                blockId = blockId,
                charOffset = offset,
                preview = preview,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun removeBookmark(bookmark: BookmarkEntity) = dao.deleteBookmark(bookmark)

    suspend fun annotationsForBlocks(bookId: String, blockIds: List<String>): List<AnnotationEntity> =
        if (blockIds.isEmpty()) emptyList() else dao.annotationsForBlocks(bookId, blockIds)

    /** Every annotation in the book, for the whole-book list. */
    suspend fun annotationsAll(bookId: String): List<AnnotationEntity> = dao.annotationsOnce(bookId)

    suspend fun annotationsForBlock(bookId: String, blockId: String): List<AnnotationEntity> =
        dao.annotationsForBlock(bookId, blockId)

    suspend fun addAnnotation(
        bookId: String,
        pageIndex: Int,
        blockId: String,
        start: Int,
        end: Int,
        text: String,
        note: String,
        color: Int,
    ): Long = dao.insertAnnotation(
        AnnotationEntity(
            bookId = bookId,
            pageIndex = pageIndex,
            blockId = blockId,
            startOffset = start,
            endOffset = end,
            text = text,
            note = note,
            color = color,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        ),
    )

    suspend fun updateAnnotation(annotation: AnnotationEntity) =
        dao.updateAnnotation(annotation.copy(updatedAt = System.currentTimeMillis()))

    suspend fun deleteAnnotation(annotation: AnnotationEntity) = dao.deleteAnnotation(annotation)

    suspend fun pageNote(bookId: String, page: Int): PageNoteEntity? = dao.pageNote(bookId, page)

    suspend fun savePageNote(bookId: String, page: Int, note: String) {
        val now = System.currentTimeMillis()
        val existing = dao.pageNote(bookId, page)
        dao.upsertPageNote(
            PageNoteEntity(
                id = existing?.id ?: 0,
                bookId = bookId,
                pageIndex = page,
                note = note,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    /**
     * Writes Markdown + JSON next to the app's external files directory.
     * Returns the Markdown file (the JSON sits beside it).
     */
    suspend fun export(context: Context, book: Book): Pair<File, File> {
        val bookmarks = dao.bookmarksOnce(book.id)
        val annotations = dao.annotationsOnce(book.id)
        val notes = dao.pageNotesOnce(book.id)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "exports")
        dir.mkdirs()
        val safeTitle = book.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)
        val md = File(dir, "$safeTitle-$stamp.md")
        val json = File(dir, "$safeTitle-$stamp.json")

        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val builder = StringBuilder()
        builder.append("# 《${book.title}》批注导出\n\n")
        builder.append("- 导出时间：${time.format(Date())}\n")
        builder.append("- 书签：${bookmarks.size} 条 · 批注：${annotations.size} 条 · 整页笔记：${notes.size} 条\n\n")

        builder.append("## 书签\n\n")
        if (bookmarks.isEmpty()) builder.append("（无）\n\n")
        bookmarks.forEach {
            builder.append("- 第 ${it.pageIndex + 1} 页 · ${it.preview} · ${time.format(Date(it.createdAt))}\n")
        }
        builder.append("\n## 批注\n\n")
        if (annotations.isEmpty()) builder.append("（无）\n\n")
        annotations.forEach {
            builder.append("- 第 ${it.pageIndex + 1} 页 · 「${it.text}」\n")
            if (it.note.isNotBlank()) builder.append("  - 笔记：${it.note}\n")
        }
        builder.append("\n## 整页笔记\n\n")
        if (notes.isEmpty()) builder.append("（无）\n\n")
        notes.forEach {
            builder.append("- 第 ${it.pageIndex + 1} 页：${it.note}\n")
        }
        md.writeText(builder.toString())

        val root = JSONObject().apply {
            put("book", book.title)
            put("exportedAt", System.currentTimeMillis())
            put("bookmarks", JSONArray().apply {
                bookmarks.forEach { b ->
                    put(JSONObject().apply {
                        put("page", b.pageIndex + 1)
                        put("blockId", b.blockId)
                        put("offset", b.charOffset)
                        put("preview", b.preview)
                        put("createdAt", b.createdAt)
                    })
                }
            })
            put("annotations", JSONArray().apply {
                annotations.forEach { a ->
                    put(JSONObject().apply {
                        put("page", a.pageIndex + 1)
                        put("blockId", a.blockId)
                        put("start", a.startOffset)
                        put("end", a.endOffset)
                        put("text", a.text)
                        put("note", a.note)
                        put("color", a.color)
                    })
                }
            })
            put("pageNotes", JSONArray().apply {
                notes.forEach { n ->
                    put(JSONObject().apply {
                        put("page", n.pageIndex + 1)
                        put("note", n.note)
                    })
                }
            })
        }
        json.writeText(root.toString(2))
        return md to json
    }
}
