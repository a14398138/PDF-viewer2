package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pdf.PageText
import com.example.util.LastShareAppManager
import com.example.util.LastSharedApp
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfTextSelectionSheet(
    currentPage: Int,
    pageTexts: List<PageText>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val pageText = pageTexts.getOrNull(currentPage)?.fullText.orEmpty()
    var manualSelection by remember { mutableStateOf("") }
    val displayContent = if (pageText.isNotBlank()) pageText else "このページから抽出されたテキストはありません。"

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .testTag("text_selection_sheet")
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.TextFields,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "テキスト選択・抽出",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "P.${currentPage + 1} のテキストをドラッグ選択して操作できます",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("text_selection_close_button")
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Bar (Copy, Share, Last App, Web Search)
            val lastApp = remember { LastShareAppManager.getLastSharedApp(context) }
            val selText = if (manualSelection.isNotBlank()) manualSelection else displayContent

            PdfTextActionButtonsBar(
                selectedText = selText,
                lastSharedApp = lastApp,
                onCopy = { text ->
                    clipboardManager.setText(AnnotatedString(text))
                    Toast.makeText(context, "テキストをコピーしました", Toast.LENGTH_SHORT).show()
                },
                onShare = { text ->
                    try {
                        val chooser = LastShareAppManager.createShareChooserIntent(context, text)
                        context.startActivity(chooser)
                    } catch (e: Exception) {
                        Toast.makeText(context, "共有できませんでした", Toast.LENGTH_SHORT).show()
                    }
                },
                onDirectShare = { text, targetApp ->
                    val success = LastShareAppManager.directShare(context, text, targetApp)
                    if (!success) {
                        try {
                            val chooser = LastShareAppManager.createShareChooserIntent(context, text)
                            context.startActivity(chooser)
                        } catch (e: Exception) {
                            Toast.makeText(context, "共有できませんでした", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onWebSearch = { text ->
                    try {
                        val encoded = URLEncoder.encode(text, "UTF-8")
                        val webIntent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://www.google.com/search?q=$encoded")
                        )
                        context.startActivity(webIntent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "検索を開けませんでした", Toast.LENGTH_SHORT).show()
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Quick Selection Input & Reader Area
            Text(
                text = "長押しやドラッグでテキストを選択するか、下のテキストボックスから一部を選択できます：",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = if (manualSelection.isEmpty()) displayContent else manualSelection,
                onValueChange = { manualSelection = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .testTag("selectable_text_box"),
                shape = RoundedCornerShape(16.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                supportingText = {
                    Text(
                        text = if (manualSelection.isNotBlank() && manualSelection != displayContent)
                            "選択中: ${manualSelection.length} 文字"
                        else
                            "全テキスト: ${displayContent.length} 文字"
                    )
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun PdfTextActionButtonsBar(
    selectedText: String,
    lastSharedApp: LastSharedApp?,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onDirectShare: (String, LastSharedApp) -> Unit,
    onWebSearch: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("pdf_text_actions_card"),
        color = Color(0xFF211F26),
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp, horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. コピー (Copy)
            SelectionActionButton(
                icon = Icons.Default.ContentCopy,
                label = "コピー",
                testTag = "action_copy_button",
                onClick = { onCopy(selectedText) }
            )

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(24.dp)
                    .background(Color.White.copy(alpha = 0.15f))
            )

            // 2. 共有 (Share)
            SelectionActionButton(
                icon = Icons.Default.Share,
                label = "共有",
                testTag = "action_share_button",
                onClick = { onShare(selectedText) }
            )

            // 2.5 前回送信アプリアイコン (Last Shared App Direct Send)
            if (lastSharedApp != null && lastSharedApp.iconBitmap != null) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(Color.White.copy(alpha = 0.15f))
                )

                Surface(
                    onClick = { onDirectShare(selectedText, lastSharedApp) },
                    shape = RoundedCornerShape(8.dp),
                    color = Color.Transparent,
                    modifier = Modifier.testTag("action_share_last_app_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Image(
                            bitmap = lastSharedApp.iconBitmap.asImageBitmap(),
                            contentDescription = "${lastSharedApp.appName} に送信",
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = lastSharedApp.appName,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                            maxLines = 1
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(24.dp)
                    .background(Color.White.copy(alpha = 0.15f))
            )

            // 3. Web検索 (Search)
            SelectionActionButton(
                icon = Icons.Default.Public,
                label = "Web検索",
                testTag = "action_web_search_button",
                onClick = { onWebSearch(selectedText) }
            )
        }
    }
}

@Composable
private fun SelectionActionButton(
    icon: ImageVector,
    label: String,
    testTag: String,
    onClick: () -> Unit
) {
    androidx.compose.material3.TextButton(
        onClick = onClick,
        modifier = Modifier.testTag(testTag),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = Color.White
        )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = Color.White
        )
    }
}
