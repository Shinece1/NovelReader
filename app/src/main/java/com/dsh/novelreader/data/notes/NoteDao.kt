package com.dsh.novelreader.data.notes

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    // ------------------------------------------------------------- bookmarks

    @Query("SELECT * FROM bookmark WHERE bookId = :bookId ORDER BY pageIndex, charOffset")
    fun bookmarks(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmark WHERE bookId = :bookId AND blockId = :blockId AND charOffset = :offset")
    suspend fun bookmarkAt(bookId: String, blockId: String, offset: Int): BookmarkEntity?

    @Insert
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    @Delete
    suspend fun deleteBookmark(bookmark: BookmarkEntity)

    // ----------------------------------------------------------- annotations

    @Query("SELECT * FROM annotation WHERE bookId = :bookId ORDER BY pageIndex, blockId, startOffset")
    fun annotations(bookId: String): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotation WHERE bookId = :bookId AND blockId IN (:blockIds)")
    suspend fun annotationsForBlocks(bookId: String, blockIds: List<String>): List<AnnotationEntity>

    @Query("SELECT * FROM annotation WHERE bookId = :bookId AND blockId = :blockId")
    suspend fun annotationsForBlock(bookId: String, blockId: String): List<AnnotationEntity>

    @Insert
    suspend fun insertAnnotation(annotation: AnnotationEntity): Long

    @Update
    suspend fun updateAnnotation(annotation: AnnotationEntity)

    @Delete
    suspend fun deleteAnnotation(annotation: AnnotationEntity)

    // ------------------------------------------------------------- page notes

    @Query("SELECT * FROM page_note WHERE bookId = :bookId ORDER BY pageIndex")
    fun pageNotes(bookId: String): Flow<List<PageNoteEntity>>

    @Query("SELECT * FROM page_note WHERE bookId = :bookId AND pageIndex = :page")
    suspend fun pageNote(bookId: String, page: Int): PageNoteEntity?

    // ------------------------------------------------------------ one-shot queries (export)

    @Query("SELECT * FROM bookmark WHERE bookId = :bookId ORDER BY pageIndex, charOffset")
    suspend fun bookmarksOnce(bookId: String): List<BookmarkEntity>

    @Query("SELECT * FROM annotation WHERE bookId = :bookId ORDER BY pageIndex, blockId, startOffset")
    suspend fun annotationsOnce(bookId: String): List<AnnotationEntity>

    @Query("SELECT * FROM page_note WHERE bookId = :bookId ORDER BY pageIndex")
    suspend fun pageNotesOnce(bookId: String): List<PageNoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPageNote(note: PageNoteEntity): Long

    @Delete
    suspend fun deletePageNote(note: PageNoteEntity)
}
