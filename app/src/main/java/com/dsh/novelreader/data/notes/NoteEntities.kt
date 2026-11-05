package com.dsh.novelreader.data.notes

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A bookmark is anchored to the ORIGINAL page plus a stable block id and a
 * character offset inside that block — never to a re-flowed page number, which
 * changes with font size, rotation or OCR arriving later.
 */
@Entity(tableName = "bookmark")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    /** Original PDF page (or TXT section) index. */
    val pageIndex: Int,
    val blockId: String,
    val charOffset: Int,
    val preview: String,
    val createdAt: Long,
)

/** A highlighted range plus an optional note. */
@Entity(tableName = "annotation")
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val pageIndex: Int,
    val blockId: String,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val note: String = "",
    /** ARGB highlight colour. */
    val color: Int = 0x66FFE082,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A whole-page note, used for image pages that carry no selectable text. */
@Entity(tableName = "page_note")
data class PageNoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val pageIndex: Int,
    val note: String,
    val createdAt: Long,
    val updatedAt: Long,
)
