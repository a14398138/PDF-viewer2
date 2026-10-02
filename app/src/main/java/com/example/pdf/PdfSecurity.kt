package com.example.pdf

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.util.PDFBoxResourceLoader
import java.io.File

/** Keeps the original encrypted document intact; passwords are never persisted. */
object PdfSecurity {
    fun pageCount(context: Context, source: File, password: String): Int {
        PDFBoxResourceLoader.init(context.applicationContext)
        return PDDocument.load(source, password).use { it.numberOfPages }
    }

    fun prepareForRendering(context: Context, source: File, password: String): File {
        PDFBoxResourceLoader.init(context.applicationContext)
        return PDDocument.load(source, password).use { document ->
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
        PDDocument.load(source).use { document ->
            document.setAllSecurityToBeRemoved(true)
            document.save(destination)
        }
    }
}
