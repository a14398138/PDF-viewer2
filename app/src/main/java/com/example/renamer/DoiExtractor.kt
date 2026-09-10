package com.example.renamer

object DoiExtractor {
    private val doiRegex = Regex(
        "10\\.\\d{4,9}/[-._;()/:A-Z0-9]+",
        setOf(RegexOption.IGNORE_CASE)
    )

    fun from(text: String): String? = doiRegex.find(text)
        ?.value
        ?.trimEnd('.', ',', ';', ':', ')', ']', '}')
        ?.lowercase()
}
