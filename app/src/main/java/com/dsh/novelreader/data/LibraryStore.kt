package com.dsh.novelreader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persistent "recently read" library, stored as JSON in SharedPreferences. */
class LibraryStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("library", Context.MODE_PRIVATE)

    fun books(): List<Book> {
        val raw = prefs.getString(KEY_BOOKS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                Book(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    type = runCatching { BookType.valueOf(o.optString("type", "TXT")) }
                        .getOrDefault(BookType.TXT),
                    totalSections = o.optInt("total", 1),
                    lastSection = o.optInt("lastSection", 0),
                    lastPage = o.optInt("lastPage", 0),
                    lastPercent = o.optDouble("percent", 0.0).toFloat(),
                    updatedAt = o.optLong("updatedAt", 0L),
                    lastBlockId = o.optString("lastBlockId", ""),
                    lastBlockOffset = o.optInt("lastBlockOffset", 0),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun get(id: String): Book? = books().firstOrNull { it.id == id }

    fun upsert(book: Book) {
        val list = books().filterNot { it.id == book.id } + book
        save(list)
    }

    /**
     * Stores the reading position. `blockId`/`offset` are the stable anchor; the
     * page numbers are only a fast path and are re-derived from the anchor when
     * the layout changes (font size, rotation, OCR arriving).
     */
    fun updateProgress(
        id: String,
        section: Int,
        page: Int,
        percent: Float,
        blockId: String = "",
        blockOffset: Int = 0,
    ) {
        val current = books()
        val existing = current.firstOrNull { it.id == id } ?: return
        val updated = existing.copy(
            lastSection = section,
            lastPage = page,
            lastPercent = percent.coerceIn(0f, 1f),
            updatedAt = System.currentTimeMillis(),
            lastBlockId = blockId,
            lastBlockOffset = blockOffset,
        )
        save(current.filterNot { it.id == id } + updated)
    }

    fun delete(id: String) {
        save(books().filterNot { it.id == id })
    }

    private fun save(list: List<Book>) {
        val array = JSONArray()
        list.sortedByDescending { it.updatedAt }.forEach { b ->
            array.put(JSONObject().apply {
                put("id", b.id)
                put("title", b.title)
                put("type", b.type.name)
                put("total", b.totalSections)
                put("lastSection", b.lastSection)
                put("lastPage", b.lastPage)
                put("percent", b.lastPercent.toDouble())
                put("updatedAt", b.updatedAt)
                put("lastBlockId", b.lastBlockId)
                put("lastBlockOffset", b.lastBlockOffset)
            })
        }
        prefs.edit().putString(KEY_BOOKS, array.toString()).apply()
    }

    private companion object {
        const val KEY_BOOKS = "books_json"
    }
}
