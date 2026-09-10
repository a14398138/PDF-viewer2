package com.example.renamer

import java.text.Normalizer

object NameFormatter {
    private const val MAX_BASE_LENGTH = 180

    fun format(metadata: WorkMetadata): String {
        val year = metadata.year.takeIf { it in 1000..2999 }?.toString() ?: "n.d."
        val author = sanitize(metadata.firstAuthor).ifBlank { "Unknown" }
        val authorPart = if (metadata.authorCount > 1) "${author}_et_al" else author
        val title = sanitize(metadata.title).ifBlank { "Untitled" }
        val prefix = "${year}_${authorPart}_"
        val available = (MAX_BASE_LENGTH - prefix.length).coerceAtLeast(20)
        return prefix + title.take(available).trimEnd('_') + ".pdf"
    }

    private fun sanitize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]+"), " ")
        .replace(Regex("\\s+"), "_")
        .replace(Regex("_+"), "_")
        .trim(' ', '_', '.')
}
