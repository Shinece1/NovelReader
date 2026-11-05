package com.dsh.novelreader.data

import android.content.Context
import android.graphics.RectF
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * File-backed content store. Every page unit lives in its own JSON file
 * (`books/<id>/sections/000001.json`) holding its blocks in reading order:
 *
 *  - a scanned page is one `PageImageBlock`,
 *  - a text page is `TextBlock`s (with style runs) and `ImageBlock`s.
 *
 * Writing a page is atomic (temp file + rename) and happens the moment OCR
 * finishes it, so a restart never re-recognises a page. A torn write can only
 * damage the page being written.
 */
class BookStore(context: Context) {

    private val root: File = File(context.filesDir, "books")

    fun bookDir(bookId: String): File = File(root, bookId).apply { mkdirs() }

    fun sourceFile(book: Book): File =
        File(bookDir(book.id), if (book.type == BookType.PDF) "source.pdf" else "source.txt")

    private fun sectionsDir(bookId: String): File =
        File(bookDir(bookId), "sections").apply { mkdirs() }

    private fun sectionFile(bookId: String, index: Int): File =
        File(sectionsDir(bookId), String.format(Locale.US, "%06d.json", index))

    fun writeSection(bookId: String, section: Section) {
        val json = JSONObject().apply {
            put("version", 2)
            put("index", section.index)
            put("state", section.state.name)
            put("source", section.source)
            put("error", section.error ?: "")
            put("blocks", JSONArray().apply {
                section.blocks.forEach { put(encodeBlock(it)) }
            })
        }
        val target = sectionFile(bookId, section.index)
        val tmp = File(target.absolutePath + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(target)) {
            target.writeText(json.toString())
            tmp.delete()
        }
    }

    fun readSection(bookId: String, index: Int): Section? {
        val file = sectionFile(bookId, index)
        if (!file.exists()) return null
        return runCatching {
            val o = JSONObject(file.readText())
            val state = runCatching { SectionState.valueOf(o.optString("state", "PENDING")) }
                .getOrDefault(SectionState.PENDING)
            val source = o.optString("source", "")
            val error = o.optString("error", "").ifEmpty { null }
            val array = o.optJSONArray("blocks")
            val blocks = if (array == null) {
                // Version 1 schema: one plain text payload.
                val legacy = o.optString("text", "")
                if (legacy.isEmpty()) emptyList() else listOf(
                    TextBlock("p${index}b0", index, null, legacy),
                )
            } else {
                (0 until array.length()).mapNotNull { i ->
                    array.optJSONObject(i)?.let { decodeBlock(it) }
                }
            }
            Section(index, blocks, state, source, error)
        }.getOrNull()
    }

    fun deleteBook(bookId: String) {
        runCatching { File(root, bookId).deleteRecursively() }
    }

    // ------------------------------------------------------------- outline

    private fun outlineFile(bookId: String): File = File(bookDir(bookId), "outline.json")

    /** Caches the PDF's bookmark tree so the reader does not re-parse it. */
    fun saveOutline(bookId: String, items: List<TocItem>) {
        val array = JSONArray().apply {
            items.forEach { item ->
                put(
                    JSONObject().apply {
                        put("t", item.title)
                        put("p", item.pageIndex)
                        put("l", item.level)
                    },
                )
            }
        }
        runCatching {
            outlineFile(bookId).writeText(JSONObject().apply { put("items", array) }.toString())
        }
    }

    fun readOutline(bookId: String): List<TocItem> {
        val file = outlineFile(bookId)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONObject(file.readText()).optJSONArray("items") ?: return emptyList()
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { item ->
                    TocItem(
                        title = item.optString("t", ""),
                        pageIndex = item.optInt("p", 0),
                        level = item.optInt("l", 0),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------- encoding

    private fun encodeBlock(block: Block): JSONObject = JSONObject().apply {
        put("id", block.id)
        put("page", block.pageIndex)
        block.bbox?.let { put("bbox", JSONArray().apply { put(it.left.toDouble()); put(it.top.toDouble()); put(it.right.toDouble()); put(it.bottom.toDouble()) }) }
        when (block) {
            is TextBlock -> {
                put("type", "text")
                put("text", block.text)
                put("heading", block.headingLevel)
                put("runs", JSONArray().apply {
                    block.runs.forEach { run ->
                        put(JSONObject().apply {
                            put("s", run.start)
                            put("e", run.end)
                            put("b", run.bold)
                            put("i", run.italic)
                            put("z", run.sizeRatio.toDouble())
                        })
                    }
                })
            }
            is ImageBlock -> {
                put("type", "figure")
                put("caption", block.caption ?: "")
            }
            is PageImageBlock -> put("type", "pageimage")
        }
    }

    private fun decodeBlock(o: JSONObject): Block? {
        val id = o.optString("id", "")
        val page = o.optInt("page", 0)
        val bbox = o.optJSONArray("bbox")?.let {
            if (it.length() == 4) {
                RectF(
                    it.optDouble(0).toFloat(),
                    it.optDouble(1).toFloat(),
                    it.optDouble(2).toFloat(),
                    it.optDouble(3).toFloat(),
                )
            } else {
                null
            }
        }
        return when (o.optString("type", "text")) {
            "pageimage" -> PageImageBlock(id.ifEmpty { "p${page}b0" }, page, bbox)
            "figure" -> ImageBlock(
                id = id.ifEmpty { "p${page}b0" },
                pageIndex = page,
                bbox = bbox,
                caption = o.optString("caption", "").ifEmpty { null },
            )
            else -> {
                val runs = ArrayList<StyleRun>()
                o.optJSONArray("runs")?.let { array ->
                    for (i in 0 until array.length()) {
                        array.optJSONObject(i)?.let { r ->
                            runs.add(
                                StyleRun(
                                    start = r.optInt("s", 0),
                                    end = r.optInt("e", 0),
                                    bold = r.optBoolean("b", false),
                                    italic = r.optBoolean("i", false),
                                    sizeRatio = r.optDouble("z", 1.0).toFloat(),
                                ),
                            )
                        }
                    }
                }
                TextBlock(
                    id = id.ifEmpty { "p${page}b0" },
                    pageIndex = page,
                    bbox = bbox,
                    text = o.optString("text", ""),
                    runs = runs,
                    headingLevel = o.optInt("heading", 0),
                )
            }
        }
    }
}
