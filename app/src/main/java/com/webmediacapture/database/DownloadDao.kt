package com.webmediacapture.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(download: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: String): DownloadEntity?

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("UPDATE downloads SET state = :state, bytesDownloaded = :bytes, totalBytes = :total, speedBps = :speedBps, progressPercent = :progressPercent, outputPath = :path, error = :error, updatedAt = :now WHERE id = :id")
    suspend fun updateState(
        id: String,
        state: DownloadState,
        bytes: Long,
        total: Long?,
        path: String?,
        error: String?,
        speedBps: Long = 0,
        progressPercent: Double = 0.0,
        now: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE downloads SET state = :state, error = :error, updatedAt = :now WHERE id = :id")
    suspend fun setState(
        id: String,
        state: DownloadState,
        error: String? = null,
        now: Long = System.currentTimeMillis(),
    )

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String)
}
