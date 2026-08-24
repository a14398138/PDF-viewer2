package com.example.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class PdfEngine(
    private val context: Context,
    private val localPdfFile: File
) : AutoCloseable {

    companion object {
        private const val TAG = "PdfEngine"

        // Global memory cache for rendered page bitmaps
        private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        private val cacheSize = maxMemory / 6 // Use 1/6th of available runtime memory
        private val bitmapCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
        }

        suspend fun copyUriToLocalCache(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
            val savedDir = File(context.filesDir, "saved_pdfs")
            if (!savedDir.exists()) savedDir.mkdirs()

            val hash = md5(uri.toString())
            val targetFile = File(savedDir, "pdf_$hash.pdf")

            // If file already exists and is non-empty, return it directly
            if (targetFile.exists() && targetFile.length() > 0L) {
                return@withContext targetFile
            }

            // Also check legacy cacheDir
            val legacyCacheDir = File(context.cacheDir, "opened_pdfs")
            val legacyFile = File(legacyCacheDir, "pdf_$hash.pdf")
            if (legacyFile.exists() && legacyFile.length() > 0L) {
                try {
                    legacyFile.copyTo(targetFile, overwrite = true)
                    return@withContext targetFile
                } catch (_: Exception) {
                    return@withContext legacyFile
                }
            }

            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output, bufferSize = 65536)
                }
            } ?: throw IllegalStateException("Could not open input stream from URI: $uri")

            targetFile
        }

        private fun md5(input: String): String {
            val md = MessageDigest.getInstance("MD5")
            return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null

    val file: File = localPdfFile
    val pageCount: Int

    init {
        fileDescriptor = ParcelFileDescriptor.open(localPdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        pdfRenderer = PdfRenderer(fileDescriptor!!)
        pageCount = pdfRenderer?.pageCount ?: 0
    }

    fun getPageDimensions(pageIndex: Int): Pair<Int, Int> {
        val renderer = pdfRenderer ?: return Pair(595, 842)
        if (pageIndex < 0 || pageIndex >= pageCount) return Pair(595, 842)

        return synchronized(this) {
            try {
                renderer.openPage(pageIndex).use { page ->
                    Pair(page.width, page.height)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting page dimensions: ${e.message}")
                Pair(595, 842)
            }
        }
    }

    fun getCachedBitmap(pageIndex: Int, targetWidth: Int = 1080): Bitmap? {
        val cacheKey = "${localPdfFile.absolutePath}_p${pageIndex}_w$targetWidth"
        val cached = bitmapCache.get(cacheKey)
        return if (cached != null && !cached.isRecycled) cached else null
    }

    suspend fun renderPageBitmap(
        pageIndex: Int,
        targetWidth: Int = 1080,
        densityDpi: Float = 2.0f
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (pageIndex < 0 || pageIndex >= pageCount) return@withContext null
        val cacheKey = "${localPdfFile.absolutePath}_p${pageIndex}_w$targetWidth"

        bitmapCache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled) return@withContext cached
        }

        val renderer = pdfRenderer ?: return@withContext null

        synchronized(this@PdfEngine) {
            try {
                renderer.openPage(pageIndex).use { page ->
                    val originalWidth = page.width
                    val originalHeight = page.height

                    val scale = (targetWidth.toFloat() / originalWidth.toFloat()).coerceIn(1.0f, 3.5f)
                    val width = (originalWidth * scale).toInt().coerceAtLeast(1)
                    val height = (originalHeight * scale).toInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    // Pre-fill with clean white background
                    bitmap.eraseColor(Color.WHITE)

                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    bitmapCache.put(cacheKey, bitmap)
                    bitmap
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error rendering page $pageIndex: ${e.message}", e)
                null
            }
        }
    }

    suspend fun generateAndSaveThumbnail(): String? = withContext(Dispatchers.IO) {
        if (pageCount <= 0) return@withContext null

        val thumbDir = File(context.filesDir, "thumbnails")
        if (!thumbDir.exists()) thumbDir.mkdirs()

        val thumbFile = File(thumbDir, "thumb_${md5(localPdfFile.absolutePath)}.png")
        if (thumbFile.exists() && thumbFile.length() > 0) {
            return@withContext thumbFile.absolutePath
        }

        val renderer = pdfRenderer ?: return@withContext null
        synchronized(this@PdfEngine) {
            try {
                renderer.openPage(0).use { page ->
                    val thumbWidth = 240
                    val scale = thumbWidth.toFloat() / page.width.toFloat()
                    val thumbHeight = (page.height * scale).toInt().coerceAtLeast(1)

                    val bitmap = Bitmap.createBitmap(thumbWidth, thumbHeight, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                    FileOutputStream(thumbFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                    }
                    thumbFile.absolutePath
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating thumbnail: ${e.message}", e)
                null
            }
        }
    }

    override fun close() {
        try {
            pdfRenderer?.close()
            pdfRenderer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing PdfRenderer: ${e.message}")
        }
        try {
            fileDescriptor?.close()
            fileDescriptor = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing ParcelFileDescriptor: ${e.message}")
        }
    }
}
