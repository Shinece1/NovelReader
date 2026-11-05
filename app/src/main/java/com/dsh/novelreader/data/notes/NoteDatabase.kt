package com.dsh.novelreader.data.notes

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [BookmarkEntity::class, AnnotationEntity::class, PageNoteEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class NoteDatabase : RoomDatabase() {

    abstract fun noteDao(): NoteDao

    companion object {
        @Volatile
        private var instance: NoteDatabase? = null

        fun get(context: Context): NoteDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                NoteDatabase::class.java,
                "notes.db",
            ).fallbackToDestructiveMigration().build().also { instance = it }
        }
    }
}
