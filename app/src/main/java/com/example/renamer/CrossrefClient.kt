package com.example.renamer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class CrossrefClient {
    suspend fun lookup(doi: String): WorkMetadata = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(doi, Charsets.UTF_8.name())
        val connection = (URL("https://api.crossref.org/works/$encoded").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "PaperRenamer/1.0 (Android; https://github.com)")
        }
        try {
            if (connection.responseCode !in 200..299) error("Crossref HTTP ${connection.responseCode}")
            val root = connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            parse(root.getJSONObject("message"))
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(message: JSONObject): WorkMetadata {
        val title = message.optJSONArray("title")?.optString(0).orEmpty()
        val authors = message.optJSONArray("author")
        val firstAuthor = authors?.optJSONObject(0)?.let {
            it.optString("family").ifBlank { it.optString("name") }
        }.orEmpty()
        val year = listOf("published-print", "published-online", "issued", "created")
            .firstNotNullOfOrNull { key ->
                message.optJSONObject(key)?.optJSONArray("date-parts")
                    ?.optJSONArray(0)?.optInt(0)?.takeIf { it > 0 }
            } ?: 0
        require(title.isNotBlank()) { "タイトルが見つかりません" }
        return WorkMetadata(title, firstAuthor, year, authors?.length() ?: 0)
    }
}
