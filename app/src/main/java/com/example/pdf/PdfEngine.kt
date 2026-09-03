package com.example.pdf

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
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

/**
 * Type-safe, structured cache key for rendered PDF page bitmaps.
 * Avoids fragile string concatenation and delimiter parsing.
 */
data class PageCacheKey(
    val filePath: String,
    val pageIndex: Int,
    val targetWidth: Int
)

class PdfEngine(
    private val context: Context,
    private val localPdfFile: File
) : AutoCloseable {

    companion object {
        private const val TAG = "PdfEngine"

        // Dynamic memory-aware LRU Cache
        private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        // Default to 1/6th of available memory or max 128MB
        private val defaultCacheCapacityKb = (maxMemoryKb / 6).coerceIn(16 * 1024, 128 * 1024)

        private val bitmapCache = object : LruCache<PageCacheKey, Bitmap>(defaultCacheCapacityKb) {
            override fun sizeOf(key: PageCacheKey, bitmap: Bitmap): Int {
                return (bitmap.byteCount / 1024).coerceAtLeast(1)
            }

            override fun entryRemoved(
                evicted: Boolean,
                key: PageCacheKey,
                oldValue: Bitmap,
                newValue: Bitmap?
            ) {
                super.entryRemoved(evicted, key, oldValue, newValue)
                if (evicted) {
                    Log.d(TAG, "LRU Evicted page bitmap from cache: page ${key.pageIndex} of ${key.filePath} (size: ${oldValue.byteCount / 1024} KB)")
                }
            }
        }

        private var isComponentCallbacksRegistered = false

        /**
         * Initializes automatic memory pressure callbacks to release off-screen/cached bitmaps.
         */
        fun initMemoryPressureCallbacks(appContext: Context) {
            if (isComponentCallbacksRegistered) return
            synchronized(this) {
                if (isComponentCallbacksRegistered) return
                val app = appContext.applicationContext
                app.registerComponentCallbacks(object : ComponentCallbacks2 {
                    override fun onTrimMemory(level: Int) {
                        handleTrimMemory(level)
                    }

                    override fun onConfigurationChanged(newConfig: Configuration) {}

                    override fun onLowMemory() {
                        handleLowMemory()
                    }
                })
                isComponentCallbacksRegistered = true
                Log.d(TAG, "Initialized PdfEngine memory pressure callbacks (Cache Capacity: ${defaultCacheCapacityKb / 1024}MB)")
            }
        }

        fun handleTrimMemory(level: Int) {
            Log.i(TAG, "onTrimMemory triggered with level: $level (current cache size: ${bitmapCache.size() / 1024}MB)")
            when {
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                    // Extreme memory pressure - evict all non-critical cached pages immediately
                    bitmapCache.evictAll()
                    Log.w(TAG, "Evicted all PDF bitmap caches due to TRIM_MEMORY_RUNNING_CRITICAL")
                }
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                    // Moderate-high pressure: trim cache to 25%
                    bitmapCache.trimToSize(defaultCacheCapacityKb / 4)
                    Log.i(TAG, "Trimmed PDF bitmap cache to 25% (${bitmapCache.size() / 1024}MB)")
                }
                level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE ||
                level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE ||
                level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                    // App backgrounded or moderate pressure: trim cache to 50%
                    bitmapCache.trimToSize(defaultCacheCapacityKb / 2)
                    Log.i(TAG, "Trimmed PDF bitmap cache to 50% (${bitmapCache.size() / 1024}MB)")
                }
            }
        }

        fun handleLowMemory() {
            Log.w(TAG, "onLowMemory triggered: clearing entire PDF bitmap LRU cache")
            bitmapCache.evictAll()
        }

        /**
         * Evicts cached bitmaps for pages that are not in the provided set of visible/nearby pages.
         */
        fun evictOffScreenBitmaps(filePath: String, keepPages: Set<Int>) {
            val snapshot = bitmapCache.snapshot()
            for ((key, _) in snapshot) {
                if (key.filePath == filePath && key.pageIndex !in keepPages) {
                    bitmapCache.remove(key)
                }
            }
        }

        /**
         * Clears all cached bitmaps for a specific PDF file.
         */
        fun clearCacheForFile(filePath: String) {
            val snapshot = bitmapCache.snapshot()
            for ((key, _) in snapshot) {
                if (key.filePath == filePath) {
                    bitmapCache.remove(key)
                }
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
        val cacheKey = PageCacheKey(localPdfFile.absolutePath, pageIndex, targetWidth)
        val cached = bitmapCache.get(cacheKey)
        return if (cached != null && !cached.isRecycled) cached else null
    }

    suspend fun renderPageBitmap(
        pageIndex: Int,
        targetWidth: Int = 1080,
        densityDpi: Float = 2.0f
    ): Bitmap? = withContext(Dispatchers.Default) {
        if (pageIndex < 0 || pageIndex >= pageCount) return@withContext null
        val cacheKey = PageCacheKey(localPdfFile.absolutePath, pageIndex, targetWidth)

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
