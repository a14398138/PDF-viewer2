package com.example.renamer

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RenamerScreen(onBack: () -> Unit, onOpenPdf: (Uri) -> Unit,
                  model: RenamerViewModel = viewModel()) {
    var confirm by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {
        it?.let(model::selectFolder)
    }
    BackHandler { if (!model.busy) onBack() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("論文PDFリネーム") }, navigationIcon = {
            TextButton(onClick = onBack, enabled = !model.busy) { Text("戻る") }
        })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("出版年_筆頭著者_et_al_タイトル.pdf", style = MaterialTheme.typography.titleSmall)
                Text("著者1名ではet_alなし。選択フォルダ直下のみ解析します。PDFは端末内で処理し、DOIだけをCrossrefへ送信します。")
                Button(onClick = { picker.launch(model.directory?.uri) }, enabled = !model.busy) {
                    Text("フォルダを選択して解析")
                }
                Text(model.directory?.uri?.toString() ?: "フォルダ未選択")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { model.scan() },
                        enabled = !model.busy && model.directory != null) { Text("増分再スキャン") }
                    OutlinedButton(onClick = { model.scan(true) },
                        enabled = !model.busy && model.directory != null) { Text("全件再解析") }
                }
                Text("増分: 未変更の候補を再利用、処理済みは省略。全件: キャッシュを使わず再解析。")
                if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(model.status)
                Button(onClick = { confirm = true },
                    enabled = !model.busy && model.items.any { it.selected && it.newName != null }) {
                    Text("選択した${model.items.count { it.selected && it.newName != null }}件をリネーム")
                }
            }
            itemsIndexed(model.items, key = { _, item -> item.file.uri.toString() }) { index, item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            Checkbox(checked = item.selected,
                                onCheckedChange = { model.select(index, it) },
                                enabled = !model.busy && item.newName != null)
                            Column(Modifier.weight(1f)) {
                                Text(item.originalName)
                                Text(item.newName?.let { "→ $it" } ?: "変更しません")
                                Text(item.error ?: "DOI: ${item.doi.orEmpty()}")
                            }
                        }
                        TextButton(onClick = { onOpenPdf(item.file.uri) }, enabled = !model.busy) {
                            Text("PDFを開いて確認")
                        }
                    }
                }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("選択したPDFをリネームしますか？") },
        text = { Text("端末内の実ファイル名を変更します。元に戻す操作はありません。同名ファイルがある場合はスキップします。閲覧履歴のメモとページ位置は保持します。") },
        confirmButton = { TextButton(onClick = { confirm = false; model.renameSelected() }) { Text("実行") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("キャンセル") } })
}
