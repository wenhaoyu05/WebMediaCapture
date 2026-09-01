package com.webmediacapture.database

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DownloadState { PENDING, PREPARING, DOWNLOADING, PAUSED, MERGING, COMPLETED, FAILED, CANCELLED }

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val mediaUrl: String,
    val pageUrl: String,
    val title: String?,
    val type: String,
    val selectedFormatId: String? = null,
    /** Non-sensitive request headers only; cookies and authorization are never stored here. */
    val requestHeaders: String = "{}",
    val state: DownloadState = DownloadState.PENDING,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long? = null,
    val speedBps: Long = 0,
    val progressPercent: Double = 0.0,
    val outputPath: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
