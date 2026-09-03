package com.example

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.HomeScreen
import com.example.ui.PdfViewerScreen
import com.example.ui.PdfViewerViewModel
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {

    private val viewModel: PdfViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (isPdfIntent(intent)) {
            viewModel.prepareIncomingIntent()
        }

        handleIntent(intent)

        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val isDark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            MyApplicationTheme(darkTheme = isDark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PdfApp(
                        viewModel = viewModel,
                        themeMode = themeMode,
                        onThemeModeChanged = { viewModel.setThemeMode(it) }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (isPdfIntent(intent)) {
            viewModel.prepareIncomingIntent()
        }
        handleIntent(intent)
    }

    private fun isPdfIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        val action = intent.action
        val type = intent.type
        return action == Intent.ACTION_VIEW && intent.data != null ||
                (action == Intent.ACTION_SEND && type != null && (type == "application/pdf" || type.startsWith("application/")))
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        val type = intent.type

        when (action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    viewModel.openPdf(uri)
                }
            }
            Intent.ACTION_SEND -> {
                if (type != null && (type == "application/pdf" || type.startsWith("application/"))) {
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    } ?: intent.clipData?.getItemAt(0)?.uri ?: intent.data

                    uri?.let { viewModel.openPdf(it) }
                }
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.example.pdf.PdfEngine.handleTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        com.example.pdf.PdfEngine.handleLowMemory()
    }
}

@Composable
fun PdfApp(
    viewModel: PdfViewerViewModel,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChanged: (ThemeMode) -> Unit = {}
) {
    val historyList by viewModel.historyList.collectAsStateWithLifecycle()
    val activePdfItem by viewModel.activePdfItem.collectAsStateWithLifecycle()
    val isPendingIntentOpen by viewModel.isPendingIntentOpen.collectAsStateWithLifecycle()
    val activePagesText by viewModel.activePagesText.collectAsStateWithLifecycle()
    val currentPage by viewModel.currentPage.collectAsStateWithLifecycle()
    val isControlsVisible by viewModel.isControlsVisible.collectAsStateWithLifecycle()
    val isSearchActive by viewModel.isSearchActive.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val currentMatchIndex by viewModel.currentMatchIndex.collectAsStateWithLifecycle()
    val isNoteDialogOpen by viewModel.isNoteDialogOpen.collectAsStateWithLifecycle()
    val isRenameDialogOpen by viewModel.isRenameDialogOpen.collectAsStateWithLifecycle()
    val isTextSelectModalOpen by viewModel.isTextSelectModalOpen.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val recentSharedApps by viewModel.recentSharedApps.collectAsStateWithLifecycle()

    // Back handler: if PDF is open, close it and return to history
    BackHandler(enabled = activePdfItem != null) {
        if (isSearchActive) {
            viewModel.closeSearch()
        } else {
            viewModel.closePdf()
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    // Show error toast if any
    LaunchedEffect(errorMessage) {
        errorMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearError()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = when {
                activePdfItem != null -> "viewer"
                isPendingIntentOpen -> "intent_loading"
                else -> "home"
            },
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "ScreenTransition"
        ) { screenState ->
            when (screenState) {
                "viewer" -> {
                    val currentItem = activePdfItem
                    if (currentItem != null) {
                        PdfViewerScreen(
                            pdfItem = currentItem,
                            pdfEngine = viewModel.getPdfEngine(),
                            pageTexts = activePagesText,
                            currentPage = currentPage,
                            isControlsVisible = isControlsVisible,
                            isSearchActive = isSearchActive,
                            searchQuery = searchQuery,
                            searchResults = searchResults,
                            currentMatchIndex = currentMatchIndex,
                            isNoteDialogOpen = isNoteDialogOpen,
                            isRenameDialogOpen = isRenameDialogOpen,
                            themeMode = themeMode,
                            onThemeModeChanged = onThemeModeChanged,
                            onBack = { viewModel.closePdf() },
                            onToggleControls = { viewModel.toggleControls() },
                            onSetControlsVisible = { viewModel.setControlsVisible(it) },
                            onPageChanged = { viewModel.onPageChanged(it) },
                            onOpenNoteDialog = { viewModel.openNoteDialog() },
                            onCloseNoteDialog = { viewModel.closeNoteDialog() },
                            onSaveNote = { viewModel.saveNote(it) },
                            onOpenRenameDialog = { viewModel.openRenameDialog() },
                            onCloseRenameDialog = { viewModel.closeRenameDialog() },
                            onRenameFile = { viewModel.renameFile(it) },
                            onOpenSearch = { viewModel.openSearch() },
                            onCloseSearch = { viewModel.closeSearch() },
                            onSearchQueryChanged = { viewModel.onSearchQueryChanged(it) },
                            onNextMatch = { viewModel.nextSearchMatch() },
                            onPrevMatch = { viewModel.prevSearchMatch() },
                            onPageTextRecognized = { pageIdx, text -> viewModel.updatePageText(pageIdx, text) },
                            availableShareApps = recentSharedApps
                        )
                    }
                }
                "intent_loading" -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
                else -> {
                    HomeScreen(
                        historyList = historyList,
                        themeMode = themeMode,
                        onThemeModeChanged = onThemeModeChanged,
                        onOpenPdfUri = { uri -> viewModel.openPdf(uri) },
                        onOpenHistoryItem = { item -> viewModel.openPdfItem(item) },
                        onOpenSamplePdf = { viewModel.openSamplePdf() },
                        onDeleteHistoryItem = { item -> viewModel.deleteHistoryItem(item) },
                        onClearAllHistory = { viewModel.clearAllHistory() }
                    )
                }
            }
        }

        if (isLoading && !isPendingIntentOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.size(80.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
        }
    }
}
