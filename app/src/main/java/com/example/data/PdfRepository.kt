package com.example.data

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import com.example.pdf.PageText
import com.example.pdf.PdfEngine
import com.example.pdf.PdfTextExtractor
import com.example.pdf.SamplePdfGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PdfRepository(
    private val context: Context,
    private val dao: PdfHistoryDao
) {
    companion object {
        private const val TAG = "PdfRepository"
    }

    val allHistory: Flow<List<PdfItem>> = dao.getAllHistory()

    fun getHistoryByUri(uriString: String): Flow<PdfItem?> = dao.getByUri(uriString)

    /** Result of opening a PDF: the history item, blank per-page text slots, and the file the renderer should open. */
    data class OpenedPdf(
        val item: PdfItem,
        val pages: List<PageText>,
        val renderFile: File
    )

    suspend fun openAndRecordPdf(uri: Uri, overrideFileName: String? = null, password: String = ""): OpenedPdf = withContext(Dispatchers.IO) {
        val uriString = uri.toString()
        val existingItem = dao.getByUriSync(uriString)

        val localFile: File = when (uri.scheme) {
            "file" -> {
                val f = File(uri.path ?: "")
                if (f.exists() && f.length() > 0L) f else PdfEngine.copyUriToLocalCache(context, uri)
            }
            else -> {
                val existingFile = existingItem?.filePath?.let { File(it) }
                if (existingFile != null && existingFile.exists() && existingFile.length() > 0L) {
                    existingFile
                } else {
                    PdfEngine.copyUriToLocalCache(context, uri)
                }
            }
        }

        val resolvedFileName = overrideFileName
            ?: existingItem?.fileName
            ?: getFileNameFromUri(uri)
            ?: localFile.name
            ?: "Document.pdf"

        val fileSize = localFile.length()

        // Page count + render source with as little work as possible:
        // on the common path (no password), probe with the native PdfRenderer
        // (fast, low memory, no full document parse). Only when a password is
        // supplied, or the renderer rejects the file (e.g. it is encrypted),
        // fall back to ONE scratch-backed PDFBox pass that authenticates and,
        // for encrypted documents, produces the unlocked render copy.
        var renderFile = localFile
        val pageCount = if (password.isEmpty()) {
            probePageCountWithRenderer(localFile) ?: run {
                val prepared = com.example.pdf.PdfSecurity.prepareDocument(context, localFile, password)
                renderFile = prepared.renderFile
                prepared.pageCount
            }
        } else {
            val prepared = com.example.pdf.PdfSecurity.prepareDocument(context, localFile, password)
            renderFile = prepared.renderFile
            prepared.pageCount
        }
        val thumbPath = existingItem?.thumbnailPath

        val initialPages = (0 until pageCount).map { i ->
            PageText(pageIndex = i, fullText = "", paragraphs = emptyList(), lines = emptyList())
        }

        val itemToSave = (existingItem ?: PdfItem(
            uriString = uriString,
            fileName = resolvedFileName,
            filePath = localFile.absolutePath,
            pageCount = pageCount,
            thumbnailPath = thumbPath,
            fileSizeBytes = fileSize,
            noteContent = ""
        )).copy(
            fileName = resolvedFileName,
            filePath = localFile.absolutePath,
            lastViewedTimestamp = System.currentTimeMillis(),
            pageCount = pageCount,
            thumbnailPath = thumbPath,
            fileSizeBytes = fileSize
        )

        dao.insert(itemToSave)

        // Thumbnail generation is intentionally NOT launched here: it used to
        // spin up a second PdfEngine (another full PDFBox parse + renderer)
        // concurrently with the open, which is exactly the memory spike that
        // made large files fail to open. The ViewModel generates the thumbnail
        // afterwards, reusing the already-open engine.
        OpenedPdf(itemToSave, initialPages, renderFile)
    }

    /**
     * Returns the page count using only the native renderer, or null when the
     * file cannot be opened by it (encrypted, corrupt, unsupported).
     */
    private fun probePageCountWithRenderer(file: File): Int? {
        var pfd: ParcelFileDescriptor? = null
        var renderer: android.graphics.pdf.PdfRenderer? = null
        return try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = android.graphics.pdf.PdfRenderer(pfd)
            renderer.pageCount
        } catch (e: Throwable) {
            Log.d(TAG, "PdfRenderer probe failed; falling back to PDFBox: ${e.message}")
            null
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
    }

    suspend fun updateThumbnail(uriString: String, thumbnailPath: String) = withContext(Dispatchers.IO) {
        dao.updateThumbnail(uriString, thumbnailPath)
    }

    suspend fun openFromHistory(item: PdfItem): Pair<PdfItem, List<PageText>> = withContext(Dispatchers.IO) {
        var localFile: File? = item.filePath?.let { File(it) }

        if (localFile == null || !localFile.exists() || localFile.length() == 0L) {
            val uri = Uri.parse(item.uriString)
            localFile = try {
                when (uri.scheme) {
                    "file" -> File(uri.path ?: "")
                    else -> PdfEngine.copyUriToLocalCache(context, uri)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore file from URI ${item.uriString}: ${e.message}")
                localFile
            }
        }

        if (localFile == null || !localFile.exists()) {
            throw IllegalStateException("PDFファイル「${item.fileName}」を開けませんでした。ファイルが移動または削除された可能性があります。")
        }

        var pageCount = item.pageCount
        var thumbPath = item.thumbnailPath

        if (pageCount <= 0) {
            try {
                val pfd = ParcelFileDescriptor.open(localFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = android.graphics.pdf.PdfRenderer(pfd)
                pageCount = renderer.pageCount
                renderer.close()
                pfd.close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to probe pageCount in openFromHistory: ${e.message}", e)
                pageCount = 1
            }
        }

        val initialPages = (0 until pageCount).map { i ->
            PageText(pageIndex = i, fullText = "", paragraphs = emptyList(), lines = emptyList())
        }

        val updatedItem = item.copy(
            filePath = localFile.absolutePath,
            lastViewedTimestamp = System.currentTimeMillis(),
            pageCount = pageCount,
            thumbnailPath = thumbPath,
            fileSizeBytes = localFile.length()
        )

        dao.insert(updatedItem)

        // Generate thumbnail in background if missing
        if (thumbPath.isNullOrEmpty() || !File(thumbPath).exists()) {
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    val engine = PdfEngine(context, localFile)
                    val generatedThumb = engine.generateAndSaveThumbnail()
                    engine.close()
                    if (!generatedThumb.isNullOrEmpty()) {
                        dao.updateThumbnail(updatedItem.uriString, generatedThumb)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Async thumbnail generation in openFromHistory failed: ${e.message}")
                } catch (e: OutOfMemoryError) {
                    Log.w(TAG, "Async thumbnail generation in openFromHistory ran out of memory: ${e.message}")
                }
            }
        }

        Pair(updatedItem, initialPages)
    }

    suspend fun openSamplePdf(): OpenedPdf = withContext(Dispatchers.IO) {
        val sampleFile = SamplePdfGenerator.getOrCreateSamplePdf(context)
        val uri = Uri.fromFile(sampleFile)
        openAndRecordPdf(uri, overrideFileName = "Sample_Guide.pdf")
    }

    suspend fun updateNote(uriString: String, note: String) = withContext(Dispatchers.IO) {
        dao.updateNote(uriString, note)
    }

    suspend fun updateFileName(uriString: String, newName: String) = withContext(Dispatchers.IO) {
        val trimmed = newName.trim()
        val finalName = if (trimmed.endsWith(".pdf", ignoreCase = true)) trimmed else "$trimmed.pdf"
        dao.updateFileName(uriString, finalName)
    }

    suspend fun updateLastPage(uriString: String, page: Int) = withContext(Dispatchers.IO) {
        dao.updateLastPage(uriString, page, System.currentTimeMillis())
    }

    suspend fun deleteHistoryItem(item: PdfItem) = withContext(Dispatchers.IO) {
        dao.deleteById(item.id)
        item.thumbnailPath?.let { path ->
            try {
                val f = File(path)
                if (f.exists()) f.delete()
            } catch (_: Exception) {}
        }
    }

    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        dao.clearAll()
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        return cursor.getString(nameIndex)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting filename from URI: ${e.message}")
            }
        }
        val path = uri.path ?: return null
        val cut = path.lastIndexOf('/')
        return if (cut != -1) path.substring(cut + 1) else path
    }
}
