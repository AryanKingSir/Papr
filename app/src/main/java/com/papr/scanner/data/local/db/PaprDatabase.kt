package com.papr.scanner.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [DocumentEntity::class, PageEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class PaprDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun pageDao(): PageDao

    companion object {
        const val NAME = "papr.db"
    }
}
