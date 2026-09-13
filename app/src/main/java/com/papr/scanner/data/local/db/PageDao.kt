package com.papr.scanner.data.local.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PageDao {
    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY pageIndex ASC")
    fun getForDocument(documentId: Long): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages")
    fun getAll(): Flow<List<PageEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(page: PageEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(pages: List<PageEntity>): List<Long>

    @Update
    suspend fun update(page: PageEntity)

    @Delete
    suspend fun delete(page: PageEntity)
}
