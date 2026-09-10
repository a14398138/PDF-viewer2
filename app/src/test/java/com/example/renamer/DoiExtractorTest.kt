package com.example.renamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoiExtractorTest {
    @Test fun extractsAndNormalizesDoi() {
        assertEquals("10.1038/s41586-020-2649-2", DoiExtractor.from("DOI: 10.1038/S41586-020-2649-2."))
    }
    @Test fun returnsNullWhenAbsent() = assertNull(DoiExtractor.from("No identifier here"))
}
