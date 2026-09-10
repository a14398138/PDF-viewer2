package com.example.renamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NameFormatterTest {
    @Test fun formatsMultipleAuthors() {
        val result = NameFormatter.format(WorkMetadata("A useful: paper?", "佐藤/Smith", 2024, 3))
        assertEquals("2024_佐藤_Smith_et_al_A_useful_paper.pdf", result)
    }
    @Test fun capsLongNames() {
        val result = NameFormatter.format(WorkMetadata("x".repeat(500), "Sato", 2024, 1))
        assertFalse(result.length > 184)
    }
}
