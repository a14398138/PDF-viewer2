package com.example.renamer

import androidx.documentfile.provider.DocumentFile

data class PaperCandidate(
    val file: DocumentFile,
    val originalName: String,
    val doi: String? = null,
    val newName: String? = null,
    val error: String? = null,
    var selected: Boolean = newName != null,
)

data class WorkMetadata(
    val title: String,
    val firstAuthor: String,
    val year: Int,
    val authorCount: Int,
)
