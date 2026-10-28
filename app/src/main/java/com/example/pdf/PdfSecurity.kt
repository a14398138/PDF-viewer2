package com.example.pdf

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import java.io.File

/** Keeps the original encrypted document intact; passwords are never persisted. */
object PdfSecurity {
    // Load via a scratch temp file instead of main memory so that very large
    // PDFs do not exhaust the heap (default main-memory loading OOM-crashes on big files).
    private val memSettings: MemoryUsageSetting get() = MemoryUsageSetting.setupTempFileOnly()

    fun pageCount(context: Context, source: File, password: String): Int {
        PDFBoxResourceLoader.init(context.applicationContext)
        return PDDocument.load(source, password, memSettings).use { it.numberOfPages }
    }

    /** Result of a single-pass document preparation: page count plus the file to render. */
    data class PreparedPdf(val pageCount: Int, val renderFile: File)

    /**
     * Loads the document ONCE (scratch-file backed) to obtain the page count and,
     * for encrypted documents, produce the unlocked copy that PdfRenderer needs.
     * Prefer this over calling pageCount() + prepareForRendering() separately:
     * each extra full parse of a large file adds tens of MB of live heap.
     */
    fun prepareDocument(context: Context, source: File, password: String): PreparedPdf {
        PDFBoxResourceLoader.init(context.applicationContext)
        return PDDocument.load(source, password, memSettings).use { document ->
            val count = document.numberOfPages
            if (!document.isEncrypted) return@use PreparedPdf(count, source)
            val temporary = File.createTempFile("unlocked_", ".pdf", context.cacheDir)
            try {
                document.setAllSecurityToBeRemoved(true)
                document.save(temporary)
                PreparedPdf(count, temporary)
            } catch (error: Throwable) {
                temporary.delete()
                throw error
            }
        }
    }

    fun prepareForRendering(context: Context, source: File, password: String): File {
        PDFBoxResourceLoader.init(context.applicationContext)
        return PDDocument.load(source, password, memSettings).use { document ->
            if (!document.isEncrypted) return@use source
            val temporary = File.createTempFile("unlocked_", ".pdf", context.cacheDir)
            try {
                document.setAllSecurityToBeRemoved(true)
                document.save(temporary)
                temporary
            } catch (error: Throwable) {
                temporary.delete()
                throw error
            }
        }
    }

    fun saveWithoutPassword(context: Context, source: File, destination: File) {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(source, memSettings).use { document ->
            document.setAllSecurityToBeRemoved(true)
            document.save(destination)
        }
    }
}
