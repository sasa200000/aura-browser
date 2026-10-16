package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val fileUrl: String,
    val filePath: String,
    val fileSize: Long,
    val mimeType: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String, // DOWNLOADING, COMPLETED, FAILED
    /** DownloadManager id, so the real completion broadcast can update this row. */
    val managerId: Long = 0,
    val downloadedBytes: Long = 0
)
