package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.PdfItem
import com.example.data.PdfRepository
import com.example.pdf.PageText
import com.example.pdf.PdfEngine
import com.example.pdf.PdfTextExtractor
import com.example.pdf.SearchMatch
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class PdfViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: PdfRepository
    private var currentEngine: PdfEngine? = null

    val historyList: StateFlow<List<PdfItem>>

    private val _activePdfItem = MutableStateFlow<PdfItem?>(null)
    val activePdfItem: StateFlow<PdfItem?> = _activePdfItem.asStateFlow()

    private val _activePagesText = MutableStateFlow<List<PageText>>(emptyList())
    val activePagesText: StateFlow<List<PageText>> = _activePagesText.asStateFlow()

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _isControlsVisible = MutableStateFlow(true)
    val isControlsVisible: StateFlow<Boolean> = _isControlsVisible.asStateFlow()

    // Search State
    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SearchMatch>>(emptyList())
    val searchResults: StateFlow<List<SearchMatch>> = _searchResults.asStateFlow()

    private val _currentMatchIndex = MutableStateFlow(0)
    val currentMatchIndex: StateFlow<Int> = _currentMatchIndex.asStateFlow()

    // Text Selection State
    private val _selectedText = MutableStateFlow<String?>(null)
    val selectedText: StateFlow<String?> = _selectedText.asStateFlow()

    private val _isTextSelectModalOpen = MutableStateFlow(false)
    val isTextSelectModalOpen: StateFlow<Boolean> = _isTextSelectModalOpen.asStateFlow()

    // Dialogs
    private val _isNoteDialogOpen = MutableStateFlow(false)
    val isNoteDialogOpen: StateFlow<Boolean> = _isNoteDialogOpen.asStateFlow()

    private val _isRenameDialogOpen = MutableStateFlow(false)
    val isRenameDialogOpen: StateFlow<Boolean> = _isRenameDialogOpen.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isPendingIntentOpen = MutableStateFlow(false)
    val isPendingIntentOpen: StateFlow<Boolean> = _isPendingIntentOpen.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isOcrPreparing = MutableStateFlow(false)
    val isOcrPreparing: StateFlow<Boolean> = _isOcrPreparing.asStateFlow()

    fun prepareIncomingIntent() {
        _isPendingIntentOpen.value = true
        _isLoading.value = true
    }

    init {
        PdfTextExtractor.init(application)
        PdfTextExtractor.onOcrModelStatusListener = { isPreparing, msg ->
            _isOcrPreparing.value = isPreparing
            if (isPreparing && msg != null && _errorMessage.value == null) {
                _errorMessage.value = msg
            }
        }
        val db = AppDatabase.getDatabase(application)
        repository = PdfRepository(application, db.pdfHistoryDao())
        historyList = repository.allHistory.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    }

    fun openPdf(uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                backgroundTextJob?.cancel()
                currentEngine?.close()
                currentEngine = null

                val (item, extractedPages) = repository.openAndRecordPdf(uri)
                _activePdfItem.value = item
                _activePagesText.value = extractedPages
                _currentPage.value = item.lastOpenedPage.coerceIn(0, (item.pageCount - 1).coerceAtLeast(0))
                _isControlsVisible.value = true
                closeSearch()
                _selectedText.value = null

                // Open engine instance for rendering
                val file = item.filePath?.let { File(it) }
                if (file != null && file.exists()) {
                    val engine = PdfEngine(getApplication(), file)
                    currentEngine = engine
                    startBackgroundTextExtraction(engine, file, item.pageCount, _currentPage.value)
                }
            } catch (e: Exception) {
                _errorMessage.value = "PDFを開けませんでした: ${e.localizedMessage}"
            } finally {
                _isPendingIntentOpen.value = false
                _isLoading.value = false
            }
        }
    }

    fun openPdfItem(historyItem: PdfItem) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                backgroundTextJob?.cancel()
                currentEngine?.close()
                currentEngine = null

                val (item, extractedPages) = repository.openFromHistory(historyItem)
                _activePdfItem.value = item
                _activePagesText.value = extractedPages
                _currentPage.value = item.lastOpenedPage.coerceIn(0, (item.pageCount - 1).coerceAtLeast(0))
                _isControlsVisible.value = true
                closeSearch()
                _selectedText.value = null

                val file = item.filePath?.let { File(it) }
                if (file != null && file.exists()) {
                    val engine = PdfEngine(getApplication(), file)
                    currentEngine = engine
                    startBackgroundTextExtraction(engine, file, item.pageCount, _currentPage.value)
                } else {
                    throw IllegalStateException("ファイルが見つかりません")
                }
            } catch (e: Exception) {
                _errorMessage.value = "PDFを開けませんでした: ${e.localizedMessage}"
            } finally {
                _isPendingIntentOpen.value = false
                _isLoading.value = false
            }
        }
    }

    fun openSamplePdf() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                backgroundTextJob?.cancel()
                currentEngine?.close()
                currentEngine = null

                val (item, extractedPages) = repository.openSamplePdf()
                _activePdfItem.value = item
                _activePagesText.value = extractedPages
                _currentPage.value = 0
                _isControlsVisible.value = true
                closeSearch()
                _selectedText.value = null

                val file = item.filePath?.let { File(it) }
                if (file != null && file.exists()) {
                    val engine = PdfEngine(getApplication(), file)
                    currentEngine = engine
                    startBackgroundTextExtraction(engine, file, item.pageCount, 0)
                }
            } catch (e: Exception) {
                _errorMessage.value = "サンプルPDFを開けませんでした: ${e.localizedMessage}"
            } finally {
                _isPendingIntentOpen.value = false
                _isLoading.value = false
            }
        }
    }

    private var backgroundTextJob: Job? = null

    private fun startBackgroundTextExtraction(
        engine: PdfEngine,
        file: File?,
        pageCount: Int,
        initialPage: Int = 0
    ) {
        backgroundTextJob?.cancel()
        if (pageCount <= 0) return

        backgroundTextJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val session = if (file != null && file.exists()) {
                PdfTextExtractor.openSession(file)
            } else {
                null
            }

            // Build priority order: current page and immediate neighbors first (±3 pages)
            val processedPages = BooleanArray(pageCount) { false }
            val priorityPages = mutableListOf<Int>()
            for (delta in 0..3) {
                val p1 = initialPage + delta
                if (p1 in 0 until pageCount && !processedPages[p1]) {
                    priorityPages.add(p1)
                    processedPages[p1] = true
                }
                val p2 = initialPage - delta
                if (p2 in 0 until pageCount && !processedPages[p2]) {
                    priorityPages.add(p2)
                    processedPages[p2] = true
                }
            }

            // Remaining pages in sequential order
            val remainingPages = (0 until pageCount).filter { !processedPages[it] }

            // Local mutable list initialized from current extracted state to avoid O(N^2) allocations
            val currentList = _activePagesText.value.toMutableList()
            while (currentList.size < pageCount) {
                currentList.add(PageText(pageIndex = currentList.size, fullText = ""))
            }

            try {
                session.use { activeSession ->
                    // 1. Process priority pages immediately
                    for (pageIdx in priorityPages) {
                        var pageText: PageText? = null
                        if (activeSession != null) {
                            pageText = activeSession.extractNativeText(pageIdx)
                        }

                        // Fall back to OCR only if no native text exists
                        if (pageText == null || pageText.words.isEmpty()) {
                            val bitmap = engine.renderPageBitmap(pageIdx, targetWidth = 1080)
                            if (bitmap != null) {
                                pageText = PdfTextExtractor.extractPageFromBitmap(bitmap, pageIdx)
                            }
                        }

                        if (pageText != null) {
                            currentList[pageIdx] = pageText
                            _activePagesText.value = currentList.toList()
                        }
                    }

                    // 2. Process remaining pages in batches with small yield to avoid thermal throttling
                    var pendingBatchCount = 0
                    for (pageIdx in remainingPages) {
                        var pageText: PageText? = null
                        if (activeSession != null) {
                            pageText = activeSession.extractNativeText(pageIdx)
                        }

                        if (pageText == null || pageText.words.isEmpty()) {
                            val bitmap = engine.renderPageBitmap(pageIdx, targetWidth = 1080)
                            if (bitmap != null) {
                                pageText = PdfTextExtractor.extractPageFromBitmap(bitmap, pageIdx)
                            }
                        }

                        if (pageText != null) {
                            currentList[pageIdx] = pageText
                            pendingBatchCount++
                            // Batch commit every 4 pages to minimize recompositions
                            if (pendingBatchCount >= 4) {
                                _activePagesText.value = currentList.toList()
                                pendingBatchCount = 0
                            }
                        }
                        kotlinx.coroutines.delay(15)
                    }

                    if (pendingBatchCount > 0) {
                        _activePagesText.value = currentList.toList()
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("PdfViewerViewModel", "Background text extraction interrupted: ${e.message}")
            }
        }
    }

    fun updatePageText(pageIndex: Int, pageText: PageText) {
        val current = _activePagesText.value.toMutableList()
        if (pageIndex < current.size) {
            current[pageIndex] = pageText
        } else {
            while (current.size < pageIndex) {
                current.add(PageText(pageIndex = current.size, fullText = ""))
            }
            current.add(pageText)
        }
        _activePagesText.value = current
    }

    fun getPdfEngine(): PdfEngine? = currentEngine

    fun closePdf() {
        val currentItem = _activePdfItem.value
        if (currentItem != null) {
            viewModelScope.launch {
                repository.updateLastPage(currentItem.uriString, _currentPage.value)
            }
        }
        currentEngine?.close()
        currentEngine = null
        _activePdfItem.value = null
        _activePagesText.value = emptyList()
        closeSearch()
        _selectedText.value = null
        _isControlsVisible.value = true
    }

    fun toggleControls() {
        _isControlsVisible.value = !_isControlsVisible.value
    }

    fun setControlsVisible(visible: Boolean) {
        _isControlsVisible.value = visible
    }

    fun onPageChanged(page: Int) {
        if (_currentPage.value != page) {
            _currentPage.value = page
            val currentItem = _activePdfItem.value
            if (currentItem != null) {
                viewModelScope.launch {
                    repository.updateLastPage(currentItem.uriString, page)
                }
            }
        }
    }

    fun jumpToPage(page: Int) {
        val maxPage = (_activePdfItem.value?.pageCount ?: 1) - 1
        _currentPage.value = page.coerceIn(0, maxPage.coerceAtLeast(0))
    }

    // Note operations
    fun openNoteDialog() {
        _isNoteDialogOpen.value = true
    }

    fun closeNoteDialog() {
        _isNoteDialogOpen.value = false
    }

    fun saveNote(note: String) {
        val item = _activePdfItem.value ?: return
        viewModelScope.launch {
            repository.updateNote(item.uriString, note)
            _activePdfItem.value = item.copy(noteContent = note)
            closeNoteDialog()
        }
    }

    // Rename operations
    fun openRenameDialog() {
        _isRenameDialogOpen.value = true
    }

    fun closeRenameDialog() {
        _isRenameDialogOpen.value = false
    }

    fun renameFile(newName: String) {
        val item = _activePdfItem.value ?: return
        if (newName.isBlank()) return
        viewModelScope.launch {
            repository.updateFileName(item.uriString, newName)
            val trimmed = newName.trim()
            val finalName = if (trimmed.endsWith(".pdf", ignoreCase = true)) trimmed else "$trimmed.pdf"
            _activePdfItem.value = item.copy(fileName = finalName)
            closeRenameDialog()
        }
    }

    // Search operations
    fun openSearch() {
        _isSearchActive.value = true
        _isControlsVisible.value = true
    }

    fun closeSearch() {
        _isSearchActive.value = false
        _searchQuery.value = ""
        _searchResults.value = emptyList()
        _currentMatchIndex.value = 0
    }

    private var searchJob: Job? = null
    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _currentMatchIndex.value = 0
            return
        }
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(250)
            val results = PdfTextExtractor.search(_activePagesText.value, query)
            _searchResults.value = results
            _currentMatchIndex.value = 0
            if (results.isNotEmpty()) {
                jumpToPage(results[0].pageIndex)
            }
        }
    }

    fun nextSearchMatch() {
        val results = _searchResults.value
        if (results.isEmpty()) return
        val nextIdx = (_currentMatchIndex.value + 1) % results.size
        _currentMatchIndex.value = nextIdx
        jumpToPage(results[nextIdx].pageIndex)
    }

    fun prevSearchMatch() {
        val results = _searchResults.value
        if (results.isEmpty()) return
        val prevIdx = if (_currentMatchIndex.value - 1 < 0) results.size - 1 else _currentMatchIndex.value - 1
        _currentMatchIndex.value = prevIdx
        jumpToPage(results[prevIdx].pageIndex)
    }

    // Text Selection
    fun openTextSelectModal() {
        _isTextSelectModalOpen.value = true
    }

    fun closeTextSelectModal() {
        _isTextSelectModalOpen.value = false
        _selectedText.value = null
    }

    fun onTextSelected(text: String?) {
        _selectedText.value = if (text.isNullOrBlank()) null else text.trim()
    }

    // History operations
    fun deleteHistoryItem(item: PdfItem) {
        viewModelScope.launch {
            repository.deleteHistoryItem(item)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            repository.clearAllHistory()
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        currentEngine?.close()
        currentEngine = null
    }
}
