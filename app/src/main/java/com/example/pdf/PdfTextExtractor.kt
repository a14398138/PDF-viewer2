package com.example.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.StringWriter
import kotlin.math.max
import kotlin.math.min

data class TextWord(
    val index: Int,
    val text: String,
    val relX: Float, // Normalized left (0.0 .. 1.0)
    val relY: Float, // Normalized top (0.0 .. 1.0)
    val relWidth: Float, // Normalized width (0.0 .. 1.0)
    val relHeight: Float, // Normalized height (0.0 .. 1.0)
    val lineIndex: Int
)

data class TextLine(
    val lineIndex: Int,
    val text: String,
    val relX: Float, // Normalized left (0.0 .. 1.0)
    val relY: Float, // Normalized top (0.0 .. 1.0)
    val relWidth: Float, // Normalized width (0.0 .. 1.0)
    val relHeight: Float, // Normalized height (0.0 .. 1.0)
    val isBold: Boolean = false,
    val words: List<TextWord> = emptyList()
)

data class PageText(
    val pageIndex: Int,
    val fullText: String,
    val paragraphs: List<String> = emptyList(),
    val lines: List<TextLine> = emptyList(),
    val words: List<TextWord> = emptyList(),
    val isNativeTextLayer: Boolean = false,
    val lowerText: String = fullText.lowercase()
)

data class SearchMatch(
    val pageIndex: Int,
    val snippet: String,
    val charOffset: Int
)

/**
 * An AutoCloseable session that keeps the PDDocument open across multiple page extractions
 * avoiding repeated full PDF file parsing.
 */
class PdfTextExtractionSession(
    val file: File,
    private val doc: PDDocument?
) : AutoCloseable {
    val numberOfPages: Int = doc?.numberOfPages ?: 0

    fun extractNativeText(pageIndex: Int): PageText? {
        val document = doc ?: return null
        return PdfTextExtractor.extractNativeTextFromDoc(document, pageIndex)
    }

    override fun close() {
        try {
            doc?.close()
        } catch (e: Exception) {
            Log.w("PdfExtractionSession", "Error closing PDDocument: ${e.message}")
        }
    }
}

object PdfTextExtractor {
    private const val TAG = "PdfTextExtractor"
    private var isPdfBoxInitialized = false

    var onOcrModelStatusListener: ((isPreparing: Boolean, message: String?) -> Unit)? = null

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun init(context: Context) {
        if (!isPdfBoxInitialized) {
            try {
                PDFBoxResourceLoader.init(context.applicationContext)
                isPdfBoxInitialized = true
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing PDFBox: ${e.message}")
            }
        }
    }

    /**
     * Creates an AutoCloseable extraction session with a single loaded PDDocument instance.
     */
    fun openSession(file: File): PdfTextExtractionSession {
        if (!file.exists()) return PdfTextExtractionSession(file, null)
        return try {
            val doc = PDDocument.load(file)
            PdfTextExtractionSession(file, doc)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load PDDocument for session: ${e.message}")
            PdfTextExtractionSession(file, null)
        }
    }

    /**
     * Extracts text and exact word bounding boxes natively from an already opened PDDocument instance.
     */
    fun extractNativeTextFromDoc(doc: PDDocument, pageIndex: Int): PageText? {
        return try {
            if (pageIndex < 0 || pageIndex >= doc.numberOfPages) return null
            val page = doc.getPage(pageIndex)
            val cropBox = page.cropBox ?: page.mediaBox ?: return null
            val pWidth = cropBox.width.coerceAtLeast(1f)
            val pHeight = cropBox.height.coerceAtLeast(1f)

            val words = mutableListOf<TextWord>()
            val lines = mutableListOf<TextLine>()
            val fullTextSb = StringBuilder()
            var wordGlobalIdx = 0
            var lineGlobalIdx = 0

            val stripper = object : PDFTextStripper() {
                init {
                    startPage = pageIndex + 1
                    endPage = pageIndex + 1
                    sortByPosition = true
                }

                override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                    if (text.isBlank() || textPositions.isEmpty()) return

                    fullTextSb.append(text).append("\n")

                    var curWordSb = StringBuilder()
                    var curWordLeft = Float.MAX_VALUE
                    var curWordTop = Float.MAX_VALUE
                    var curWordRight = Float.MIN_VALUE
                    var curWordBottom = Float.MIN_VALUE

                    val lineWords = mutableListOf<TextWord>()

                    fun flushWord() {
                        if (curWordSb.isNotEmpty() && curWordLeft < curWordRight && curWordTop < curWordBottom) {
                            val relX = (curWordLeft / pWidth).coerceIn(0f, 1f)
                            val relY = (curWordTop / pHeight).coerceIn(0f, 1f)
                            val relW = ((curWordRight - curWordLeft) / pWidth).coerceIn(0.005f, 1f)
                            val relH = ((curWordBottom - curWordTop) / pHeight).coerceIn(0.005f, 1f)

                            val word = TextWord(
                                index = wordGlobalIdx++,
                                text = curWordSb.toString(),
                                relX = relX,
                                relY = relY,
                                relWidth = relW,
                                relHeight = relH,
                                lineIndex = lineGlobalIdx
                            )
                            lineWords.add(word)
                            words.add(word)
                        }
                        curWordSb = StringBuilder()
                        curWordLeft = Float.MAX_VALUE
                        curWordTop = Float.MAX_VALUE
                        curWordRight = Float.MIN_VALUE
                        curWordBottom = Float.MIN_VALUE
                    }

                    for (tp in textPositions) {
                        val str = tp.unicode ?: ""
                        for (ch in str) {
                            if (ch.isWhitespace()) {
                                flushWord()
                            } else if (isCjkChar(ch)) {
                                // Flush any preceding non-CJK accumulated word
                                flushWord()

                                // Create individual 1-character TextWord for Japanese/CJK
                                val x = tp.xDirAdj
                                val top = tp.yDirAdj - tp.heightDir
                                val right = tp.xDirAdj + tp.widthDirAdj
                                val bottom = tp.yDirAdj

                                val relX = (x / pWidth).coerceIn(0f, 1f)
                                val relY = (top / pHeight).coerceIn(0f, 1f)
                                val relW = ((right - x) / pWidth).coerceIn(0.005f, 1f)
                                val relH = ((bottom - top) / pHeight).coerceIn(0.005f, 1f)

                                val word = TextWord(
                                    index = wordGlobalIdx++,
                                    text = ch.toString(),
                                    relX = relX,
                                    relY = relY,
                                    relWidth = relW,
                                    relHeight = relH,
                                    lineIndex = lineGlobalIdx
                                )
                                lineWords.add(word)
                                words.add(word)
                            } else {
                                // Accumulate English / alphanumeric words
                                curWordSb.append(ch)
                                val x = tp.xDirAdj
                                val top = tp.yDirAdj - tp.heightDir
                                val right = tp.xDirAdj + tp.widthDirAdj
                                val bottom = tp.yDirAdj

                                curWordLeft = min(curWordLeft, x)
                                curWordTop = min(curWordTop, top)
                                curWordRight = max(curWordRight, right)
                                curWordBottom = max(curWordBottom, bottom)
                            }
                        }
                    }
                    flushWord()

                    if (lineWords.isNotEmpty()) {
                        val lineLeft = lineWords.minOf { it.relX }
                        val lineTop = lineWords.minOf { it.relY }
                        val lineRight = lineWords.maxOf { it.relX + it.relWidth }
                        val lineBottom = lineWords.maxOf { it.relY + it.relHeight }

                        lines.add(
                            TextLine(
                                lineIndex = lineGlobalIdx,
                                text = text,
                                relX = lineLeft,
                                relY = lineTop,
                                relWidth = lineRight - lineLeft,
                                relHeight = lineBottom - lineTop,
                                words = lineWords
                            )
                        )
                        lineGlobalIdx++
                    }
                }
            }

            stripper.writeText(doc, StringWriter())

            val fullText = fullTextSb.toString().trim()
            if (words.isNotEmpty() && fullText.isNotBlank()) {
                PageText(
                    pageIndex = pageIndex,
                    fullText = fullText,
                    paragraphs = listOf(fullText),
                    lines = lines,
                    words = words,
                    isNativeTextLayer = true
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Native text extraction skipped or failed for page $pageIndex: ${e.message}")
            null
        }
    }

    /**
     * Extracts text and exact word bounding boxes natively from PDF file (single page convenience).
     */
    fun extractNativeTextFromPdf(file: File, pageIndex: Int): PageText? {
        if (!file.exists()) return null
        return try {
            PDDocument.load(file).use { doc ->
                extractNativeTextFromDoc(doc, pageIndex)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Native text extraction skipped or failed for page $pageIndex: ${e.message}")
            null
        }
    }

    /**
     * Primary extraction method:
     * 1. Uses embedded vector text layer if available (fast & 100% accurate)
     * 2. Falls back to ML Kit OCR if the page has no native text (scanned / image)
     */
    suspend fun extractPageText(
        file: File?,
        bitmap: Bitmap?,
        pageIndex: Int
    ): PageText = withContext(Dispatchers.Default) {
        // Step 1: Check native text layer
        if (file != null && file.exists()) {
            val nativeResult = extractNativeTextFromPdf(file, pageIndex)
            if (nativeResult != null && nativeResult.words.isNotEmpty()) {
                return@withContext nativeResult
            }
        }

        // Step 2: Fall back to ML Kit OCR
        if (bitmap != null) {
            return@withContext extractPageFromBitmap(bitmap, pageIndex)
        }

        PageText(pageIndex = pageIndex, fullText = "", paragraphs = emptyList(), lines = emptyList(), words = emptyList())
    }

    suspend fun extractPageFromBitmap(bitmap: Bitmap, pageIndex: Int): PageText = withContext(Dispatchers.Default) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            // Non-blocking coroutine await using kotlinx-coroutines-play-services
            val result = recognizer.process(image).await()

            val lines = mutableListOf<TextLine>()
            val allWords = mutableListOf<TextWord>()
            val paragraphs = mutableListOf<String>()
            val bWidth = bitmap.width.toFloat().coerceAtLeast(1f)
            val bHeight = bitmap.height.toFloat().coerceAtLeast(1f)

            var wordGlobalIndex = 0
            var lineGlobalIndex = 0

            for (block in result.textBlocks) {
                if (block.text.isNotBlank()) {
                    paragraphs.add(block.text)
                }
                for (line in block.lines) {
                    val rect = line.boundingBox ?: Rect(0, 0, 0, 0)
                    val relX = (rect.left.toFloat() / bWidth).coerceIn(0f, 1f)
                    val relY = (rect.top.toFloat() / bHeight).coerceIn(0f, 1f)
                    val relWidth = (rect.width().toFloat() / bWidth).coerceIn(0f, 1f)
                    val relHeight = (rect.height().toFloat() / bHeight).coerceIn(0f, 1f)

                    val lineWords = mutableListOf<TextWord>()
                    for (element in line.elements) {
                        val elemRect = element.boundingBox ?: Rect(0, 0, 0, 0)
                        val elemRelX = (elemRect.left.toFloat() / bWidth).coerceIn(0f, 1f)
                        val elemRelY = (elemRect.top.toFloat() / bHeight).coerceIn(0f, 1f)
                        val elemRelW = (elemRect.width().toFloat() / bWidth).coerceIn(0f, 1f)
                        val elemRelH = (elemRect.height().toFloat() / bHeight).coerceIn(0f, 1f)

                        val elemText = element.text
                        val hasCjk = elemText.any { isCjkChar(it) }

                        if (hasCjk && elemText.length > 1) {
                            // Split Japanese/CJK element into individual character words with proportional widths
                            val charCount = elemText.length
                            val charWidth = elemRelW / charCount.toFloat()
                            for (cIdx in 0 until charCount) {
                                val ch = elemText[cIdx]
                                val word = TextWord(
                                    index = wordGlobalIndex++,
                                    text = ch.toString(),
                                    relX = (elemRelX + cIdx * charWidth).coerceIn(0f, 1f),
                                    relY = elemRelY,
                                    relWidth = charWidth.coerceIn(0.005f, 1f),
                                    relHeight = elemRelH,
                                    lineIndex = lineGlobalIndex
                                )
                                lineWords.add(word)
                                allWords.add(word)
                            }
                        } else {
                            val word = TextWord(
                                index = wordGlobalIndex++,
                                text = elemText,
                                relX = elemRelX,
                                relY = elemRelY,
                                relWidth = elemRelW,
                                relHeight = elemRelH,
                                lineIndex = lineGlobalIndex
                            )
                            lineWords.add(word)
                            allWords.add(word)
                        }
                    }

                    if (lineWords.isEmpty() && line.text.isNotBlank()) {
                        val lineText = line.text
                        val hasCjk = lineText.any { isCjkChar(it) }
                        if (hasCjk && lineText.length > 1) {
                            val charCount = lineText.length
                            val charWidth = relWidth / charCount.toFloat()
                            for (cIdx in 0 until charCount) {
                                val ch = lineText[cIdx]
                                val word = TextWord(
                                    index = wordGlobalIndex++,
                                    text = ch.toString(),
                                    relX = (relX + cIdx * charWidth).coerceIn(0f, 1f),
                                    relY = relY,
                                    relWidth = charWidth.coerceIn(0.005f, 1f),
                                    relHeight = relHeight,
                                    lineIndex = lineGlobalIndex
                                )
                                lineWords.add(word)
                                allWords.add(word)
                            }
                        } else {
                            val word = TextWord(
                                index = wordGlobalIndex++,
                                text = lineText,
                                relX = relX,
                                relY = relY,
                                relWidth = relWidth,
                                relHeight = relHeight,
                                lineIndex = lineGlobalIndex
                            )
                            lineWords.add(word)
                            allWords.add(word)
                        }
                    }

                    lines.add(
                        TextLine(
                            lineIndex = lineGlobalIndex,
                            text = line.text,
                            relX = relX,
                            relY = relY,
                            relWidth = relWidth,
                            relHeight = relHeight,
                            words = lineWords
                        )
                    )
                    lineGlobalIndex++
                }
            }

            PageText(
                pageIndex = pageIndex,
                fullText = result.text,
                paragraphs = paragraphs,
                lines = lines,
                words = allWords,
                isNativeTextLayer = false
            )
        } catch (e: Exception) {
            val msg = e.message ?: ""
            val isDownloading = msg.contains("download", ignoreCase = true) ||
                    msg.contains("waiting", ignoreCase = true) ||
                    msg.contains("unavailable", ignoreCase = true) ||
                    e.javaClass.name.contains("MlKitException", ignoreCase = true)

            if (isDownloading) {
                Log.w(TAG, "ML Kit OCR model is preparing or downloading in background: ${e.message}", e)
                onOcrModelStatusListener?.invoke(true, "OCRの準備中です")
            } else {
                Log.e(TAG, "ML Kit recognition failed on page $pageIndex: ${e.message}", e)
            }
            PageText(
                pageIndex = pageIndex,
                fullText = "",
                paragraphs = emptyList(),
                lines = emptyList(),
                words = emptyList()
            )
        }
    }

    fun isCjkChar(c: Char): Boolean {
        val ub = Character.UnicodeBlock.of(c)
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
                ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                ub == Character.UnicodeBlock.HIRAGANA ||
                ub == Character.UnicodeBlock.KATAKANA ||
                ub == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ||
                ub == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                ub == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }

    fun formatSelectedWords(words: List<TextWord>, indices: Set<Int>): String {
        val sorted = indices.sorted().mapNotNull { words.getOrNull(it) }
        if (sorted.isEmpty()) return ""
        val sb = StringBuilder()
        for (i in sorted.indices) {
            val cur = sorted[i]
            if (i > 0) {
                val prev = sorted[i - 1]
                if (cur.lineIndex != prev.lineIndex) {
                    sb.append("\n")
                } else {
                    val prevLastChar = prev.text.lastOrNull()
                    val curFirstChar = cur.text.firstOrNull()
                    val isPrevCjk = prevLastChar != null && isCjkChar(prevLastChar)
                    val isCurCjk = curFirstChar != null && isCjkChar(curFirstChar)
                    // Insert space only between non-CJK (English / alphanumeric) words
                    if (!isPrevCjk && !isCurCjk) {
                        sb.append(" ")
                    }
                }
            }
            sb.append(cur.text)
        }
        return sb.toString()
    }

    /**
     * Highly optimized search that uses the pre-cached `lowerText` of each PageText.
     */
    fun search(pages: List<PageText>, query: String): List<SearchMatch> {
        if (query.isBlank()) return emptyList()
        val lowerQuery = query.lowercase().trim()
        if (lowerQuery.isEmpty()) return emptyList()
        val matches = mutableListOf<SearchMatch>()

        for (page in pages) {
            val lowerText = page.lowerText
            if (!lowerText.contains(lowerQuery)) continue

            val originalText = page.fullText
            var startIndex = 0
            while (true) {
                val found = lowerText.indexOf(lowerQuery, startIndex)
                if (found == -1) break

                val snippetStart = (found - 30).coerceAtLeast(0)
                val snippetEnd = (found + query.length + 30).coerceAtMost(originalText.length)
                var snippet = originalText.substring(snippetStart, snippetEnd).trim()
                if (snippetStart > 0) snippet = "...$snippet"
                if (snippetEnd < originalText.length) snippet = "$snippet..."

                matches.add(
                    SearchMatch(
                        pageIndex = page.pageIndex,
                        snippet = snippet,
                        charOffset = found
                    )
                )
                startIndex = found + 1
            }
        }
        return matches
    }
}
