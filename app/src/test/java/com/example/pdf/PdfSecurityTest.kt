package com.example.pdf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.util.PDFBoxResourceLoader
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfSecurityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun document(password: String? = null, bits: Int = 128): File {
        PDFBoxResourceLoader.init(context)
        val file = File.createTempFile("security_test_", ".pdf", context.cacheDir)
        PDDocument().use { pdf ->
            pdf.addPage(PDPage())
            pdf.addPage(PDPage())
            pdf.documentInformation.title = "Preserved title"
            if (password != null) {
                val policy = StandardProtectionPolicy("owner-secret", password, AccessPermission())
                policy.encryptionKeyLength = bits
                policy.setPreferAES(true)
                pdf.protect(policy)
            }
            pdf.save(file)
        }
        return file
    }

    @Test
    fun encryptedPdfRequiresCorrectPasswordAndExportsLosslessly() {
        for (bits in listOf(128, 256)) {
            val source = document("test-password", bits)
            val originalBytes = source.readBytes()
            for (password in listOf("", "incorrect")) {
                try {
                    PdfSecurity.pageCount(context, source, password)
                    fail("A missing or incorrect password must be rejected")
                } catch (_: InvalidPasswordException) { }
            }
            assertEquals(2, PdfSecurity.pageCount(context, source, "test-password"))
            val unlocked = PdfSecurity.prepareForRendering(context, source, "test-password")
            val exported = File.createTempFile("export_test_", ".pdf", context.cacheDir)
            try {
                assertNotEquals(source, unlocked)
                PdfSecurity.saveWithoutPassword(context, unlocked, exported)
                PDDocument.load(exported).use {
                    assertFalse(it.isEncrypted)
                    assertEquals(2, it.numberOfPages)
                    assertEquals("Preserved title", it.documentInformation.title)
                }
                assertArrayEquals(originalBytes, source.readBytes())
                PDDocument.load(source, "test-password").use { assertTrue(it.isEncrypted) }
            } finally {
                unlocked.delete()
                exported.delete()
                source.delete()
            }
        }
    }

    @Test
    fun ordinaryPdfRemainsUsable() {
        val source = document()
        try {
            assertEquals(2, PdfSecurity.pageCount(context, source, ""))
            assertEquals(source, PdfSecurity.prepareForRendering(context, source, ""))
        } finally { source.delete() }
    }

    @Test
    fun emptyUserPasswordIsSupported() {
        val source = document("")
        val unlocked = PdfSecurity.prepareForRendering(context, source, "")
        try {
            PDDocument.load(unlocked).use { assertFalse(it.isEncrypted) }
        } finally { unlocked.delete(); source.delete() }
    }
}
