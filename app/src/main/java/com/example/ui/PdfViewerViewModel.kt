package com.example.ui

import android.app.Application
import android.content.Context
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
import com.example.ui.theme.ThemeMode
import com.example.util.LastShareAppManager
import com.example.util.LastSharedApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PdfViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: PdfRepository
    private var currentEngine: PdfEngine? = null

    private val prefs = application.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(loadSavedThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    private fun loadSavedThemeMode(): ThemeMode {
        val name = prefs.getString("theme_mode", ThemeMode.SYSTEM.name)
        return try {
            ThemeMode.valueOf(name ?: ThemeMode.SYSTEM.name)
        } catch (_: Exception) {
            ThemeMode.SYSTEM
        }
    }

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

    // Quick-share apps loaded asynchronously on IO thread to prevent UI thread jank
    private val _recentSharedApps = MutableStateFlow<List<LastSharedApp>>(emptyList())
    val recentSharedApps: StateFlow<List<LastSharedApp>> = _recentSharedApps.asStateFlow()

    fun prepareIncomingIntent() {
        _isPendingIntentOpen.value = true
        _isLoading.value = true
    }

    init {
        PdfEngine.initMemoryPressureCallbacks(application)
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
        loadRecentSharedApps()
    }

    fun loadRecentSharedApps() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apps = LastShareAppManager.getRecentSharedApps(getApplication())
                _recentSharedApps.value = apps
            } catch (e: Exception) {
                // Ignore failure
            }
        }
    }

    private var pendingPdfUri: Uri? = null
    private val _passwordRequired = MutableStateFlow(false)
    val passwordRequired = _passwordRequired.asStateFlow()
    private val _passwordError = MutableStateFlow<String?>(null)
    val passwordError = _passwordError.asStateFlow()
    private var openJob: Job? = null

    fun cancelPassword() {
        pendingPdfUri = null
        _passwordRequired.value = false
        _passwordError.value = null
    }

    fun submitPassword(password: String) {
        pendingPdfUri?.let { openPdf(it, password) }
    }

    fun openPdf(uri: Uri, password: String? = null) {
        if (_isLoading.value && openJob?.isActive == true) return
        if (password == null) cancelPassword()
        openJob = viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            // Tracks where an OutOfMemoryError happened so the error message can name the phase
            var phase = "ファイル読み込み"
            try {
                val opened = repository.openAndRecordPdf(uri, password = password ?: "")
                val item = opened.item
                val extractedPages = opened.pages
                val source = File(requireNotNull(item.filePath))
                phase = "ビューア準備"
                val engine = withContext(Dispatchers.IO) {
                    PdfEngine(getApplication(), source, password ?: "", preparedFile = opened.renderFile)
                }
                backgroundTextJob?.cancel()
                backgroundTextJob?.join()
                currentEngine?.close()
                currentEngine = engine
                val targetPage = item.lastOpenedPage.coerceIn(0, (item.pageCount - 1).coerceAtLeast(0))
                phase = "最初のページ描画"
                engine.renderPageBitmap(targetPage)
                _activePdfItem.value = item
                _activePagesText.value = extractedPages
                _currentPage.value = targetPage
                _isControlsVisible.value = true
                closeSearch()
                _selectedText.value = null
                cancelPassword()
                startBackgroundTextExtraction(engine, engine.file, item.pageCount, targetPage)
                maybeGenerateThumbnail(engine, item)
            } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
                pendingPdfUri = uri
                _passwordRequired.value = true
                _passwordError.value = if (password == null) null else "パスワードが違います。もう一度入力してください。"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                // Large files can exhaust the heap while loading/rendering; report instead of crashing
                android.util.Log.e("PdfViewerViewModel", "Out of memory while opening PDF (phase: $phase)", e)
                cancelPassword()
                _errorMessage.value = "ファイルが大きすぎてメモリが不足しました（段階: $phase）。他のアプリを閉じてから再度お試しください。"
            } catch (e: Exception) {
                cancelPassword()
                _errorMessage.value = "PDFを開けませんでした: ${e.localizedMessage}"
            } finally {
                _isPendingIntentOpen.value = false
                _isLoading.value = false
            }
        }
    }

    fun openPdfItem(historyItem: PdfItem) {
        openPdf(Uri.parse(historyItem.uriString))
    }

    /**
     * Generates the history thumbnail with the already-open engine, after the
     * viewer is up. (This used to run inside the repository during open with a
     * second engine — a concurrent full parse that starved large-file opens.)
     */
    private fun maybeGenerateThumbnail(engine: PdfEngine, item: PdfItem) {
        val existing = item.thumbnailPath
        if (!existing.isNullOrEmpty() && File(existing).exists()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val generated = engine.generateAndSaveThumbnail()
                if (!generated.isNullOrEmpty()) {
                    repository.updateThumbnail(item.uriString, generated)
                }
            } catch (e: Exception) {
                android.util.Log.w("PdfViewerViewModel", "Thumbnail generation failed: ${e.message}")
            } catch (e: OutOfMemoryError) {
                android.util.Log.w("PdfViewerViewModel", "Thumbnail generation ran out of memory")
            }
        }
    }

    fun saveWithoutPassword(destination: Uri) {
        val engine = currentEngine ?: return
        val item = _activePdfItem.value ?: return
        if (destination.toString() == item.uriString) {
            _errorMessage.value = "元のPDFとは別のファイル名で保存してください。"
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            try {
                withContext(Dispatchers.IO) {
                    val context = getApplication<Application>()
                    val temporary = File.createTempFile("export_", ".pdf", context.cacheDir)
                    try {
                        com.example.pdf.PdfSecurity.saveWithoutPassword(context, engine.file, temporary)
                        val output = context.contentResolver.openOutputStream(destination, "wt")
                            ?: throw java.io.IOException("保存先を開けません")
                        output.use { out -> temporary.inputStream().use { it.copyTo(out) } }
                    } finally {
                        temporary.delete()
                    }
                }
                _errorMessage.value = "パスワードなしのPDFを保存しました"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _errorMessage.value = "保存できませんでした: ${e.localizedMessage}"
            } finally {
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

                val opened = repository.openSamplePdf()
                val item = opened.item
                val extractedPages = opened.pages
                val file = item.filePath?.let { File(it) }
                if (file != null && file.exists()) {
                    val engine = PdfEngine(getApplication(), file, preparedFile = opened.renderFile)
                    currentEngine = engine

                    // Pre-render page 0 immediately
                    engine.renderPageBitmap(0)

                    _activePdfItem.value = item
                    _activePagesText.value = extractedPages
                    _currentPage.value = 0
                    _isControlsVisible.value = true
                    closeSearch()
                    _selectedText.value = null

                    startBackgroundTextExtraction(engine, file, item.pageCount, 0)
                    maybeGenerateThumbnail(engine, item)
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
            // Give UI immediate rendering priority for the first frame
            kotlinx.coroutines.delay(100)

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
            } catch (e: OutOfMemoryError) {
                // Never let background OCR/extraction of a huge file crash the app
                android.util.Log.w("PdfViewerViewModel", "Background text extraction stopped: out of memory")
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
        if (_isLoading.value) return
        backgroundTextJob?.cancel()
        cancelPassword()
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
