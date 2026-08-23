package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pdf_history",
    indices = [Index(value = ["uriString"], unique = true)]
)
data class PdfItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val uriString: String,
    val fileName: String,
    val filePath: String? = null,
    val lastViewedTimestamp: Long = System.currentTimeMillis(),
    val pageCount: Int = 1,
    val lastOpenedPage: Int = 0,
    val thumbnailPath: String? = null,
    val noteContent: String = "",
    val fileSizeBytes: Long = 0L
)
