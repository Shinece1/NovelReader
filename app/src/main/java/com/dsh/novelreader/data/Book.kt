package com.dsh.novelreader.data

/** Kind of the imported source document. */
enum class BookType { PDF, TXT }

/**
 * One book in the local library. `totalSections` is the number of page units:
 * one PDF page for PDFs, one ~3000-char chunk for TXT. Pagination is computed
 * per unit so pages never shift when later pages finish OCR, and the saved
 * position additionally keeps a block anchor so re-flow cannot lose it.
 */
data class Book(
    val id: String,
    val title: String,
    val type: BookType,
    val totalSections: Int,
    val lastSection: Int = 0,
    val lastPage: Int = 0,
    val lastPercent: Float = 0f,
    val updatedAt: Long = 0L,
    /** Stable anchor of the last reading position. */
    val lastBlockId: String = "",
    val lastBlockOffset: Int = 0,
)

/** Where a section's content came from. */
enum class SectionState { TEXT, OCR, PENDING, FAILED, BLANK }

/**
 * A page unit (one PDF page, or one ~3000-char TXT chunk) and its blocks in
 * reading order. A scanned page is a single [PageImageBlock]; a text page is a
 * list of [TextBlock]s plus any embedded [ImageBlock]s.
 */
data class Section(
    val index: Int,
    val blocks: List<Block>,
    val state: SectionState,
    val source: String,
    val error: String? = null,
) {
    val isReadable: Boolean get() = state == SectionState.TEXT || state == SectionState.OCR

    /** Flattened text, used for previews and the table of contents. */
    val text: String
        get() = blocks.filterIsInstance<TextBlock>().joinToString("\n\n") { it.text }

    /** True when the page has no text and must be displayed as an image. */
    val isImagePage: Boolean
        get() = blocks.isNotEmpty() &&
            blocks.all { it is PageImageBlock } &&
            state != SectionState.OCR
}
