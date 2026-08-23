package com.example.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

object SamplePdfGenerator {

    fun getOrCreateSamplePdf(context: Context): File {
        val sampleDir = File(context.filesDir, "samples")
        if (!sampleDir.exists()) sampleDir.mkdirs()
        val sampleFile = File(sampleDir, "Sample_Guide.pdf")
        if (sampleFile.exists() && sampleFile.length() > 0) {
            return sampleFile
        }

        createSamplePdf(sampleFile)
        return sampleFile
    }

    private fun createSamplePdf(destinationFile: File) {
        val document = PdfDocument()

        val pageWidth = 595 // A4 standard width (points)
        val pageHeight = 842 // A4 standard height (points)

        val titlePaint = Paint().apply {
            color = Color.rgb(183, 28, 28) // Deep Crimson
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val headingPaint = Paint().apply {
            color = Color.rgb(33, 33, 33)
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val bodyPaint = Paint().apply {
            color = Color.rgb(66, 66, 66)
            textSize = 12f
            typeface = Typeface.DEFAULT
            isAntiAlias = true
        }

        val accentPaint = Paint().apply {
            color = Color.rgb(229, 57, 53)
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }

        val linePaint = Paint().apply {
            color = Color.rgb(224, 224, 224)
            strokeWidth = 1.5f
            isAntiAlias = true
        }

        val boxPaint = Paint().apply {
            color = Color.rgb(250, 240, 240)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val boxBorderPaint = Paint().apply {
            color = Color.rgb(239, 154, 154)
            style = Paint.Style.STROKE
            strokeWidth = 1f
            isAntiAlias = true
        }

        // --- PAGE 1: Welcome & Overview ---
        val pageInfo1 = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page1 = document.startPage(pageInfo1)
        val canvas1: Canvas = page1.canvas

        // Header decorative strip
        val headerBarPaint = Paint().apply { color = Color.rgb(198, 40, 40) }
        canvas1.drawRect(0f, 0f, pageWidth.toFloat(), 12f, headerBarPaint)

        var y = 60f
        canvas1.drawText("PDF Viewer - ユーザーガイド", 50f, y, titlePaint)
        y += 10f
        canvas1.drawLine(50f, y, (pageWidth - 50).toFloat(), y, linePaint)
        y += 30f

        canvas1.drawText("1. アプリの基本概要", 50f, y, headingPaint)
        y += 24f
        canvas1.drawText("本アプリは、Android端末上で快適にドキュメントを閲覧・管理できる", 50f, y, bodyPaint)
        y += 18f
        canvas1.drawText("シンプルかつ高機能なPDFビューアです。", 50f, y, bodyPaint)
        y += 30f

        // Feature Highlight Box
        canvas1.drawRoundRect(50f, y, (pageWidth - 50).toFloat(), y + 130f, 12f, 12f, boxPaint)
        canvas1.drawRoundRect(50f, y, (pageWidth - 50).toFloat(), y + 130f, 12f, 12f, boxBorderPaint)

        val boxY = y + 26f
        canvas1.drawText("■ 主な機能ハイライト", 65f, boxY, accentPaint)
        canvas1.drawText("・ 閲覧履歴と1ページ目サムネイル自動生成", 65f, boxY + 22f, bodyPaint)
        canvas1.drawText("・ PDFごとのメモ作成・永続化保存（Room DB）", 65f, boxY + 44f, bodyPaint)
        canvas1.drawText("・ 全文検索＆ハイライトジャンプ機能", 65f, boxY + 66f, bodyPaint)
        canvas1.drawText("・ 画面回転（縦/横）切り替えと外部アプリ共有", 65f, boxY + 88f, bodyPaint)

        y += 160f
        canvas1.drawText("2. 他アプリからの起動とマルチタスク", 50f, y, headingPaint)
        y += 24f
        canvas1.drawText("ファイルマネージャーやメール添付、ブラウザからの「共有」や「開く」", 50f, y, bodyPaint)
        y += 18f
        canvas1.drawText("（ACTION_VIEW / ACTION_SEND）に完全対応しています。", 50f, y, bodyPaint)
        y += 18f
        canvas1.drawText("複数のPDFファイルを個別の独立したタスクとして同時に開くことができます。", 50f, y, bodyPaint)

        y += 40f
        canvas1.drawText("3. テキスト選択とクイックアクション", 50f, y, headingPaint)
        y += 24f
        canvas1.drawText("PDF内のテキストをドラッグ選択すると、以下のメニューが表示されます：", 50f, y, bodyPaint)
        y += 20f
        canvas1.drawText("・ コピー (Copy) : クリップボードへ瞬時にコピー", 65f, y, bodyPaint)
        y += 18f
        canvas1.drawText("・ 共有 (Share) : 選択したテキストを他アプリへ共有", 65f, y, bodyPaint)
        y += 18f
        canvas1.drawText("・ Web検索 (Search) : ブラウザで選択キーワードを即座にGoogle検索", 65f, y, bodyPaint)

        // Footer
        val footerPaint = Paint().apply {
            color = Color.rgb(150, 150, 150)
            textSize = 10f
            isAntiAlias = true
        }
        canvas1.drawText("Page 1 of 3 - PDF Viewer Official Guide", 50f, (pageHeight - 30).toFloat(), footerPaint)
        document.finishPage(page1)

        // --- PAGE 2: Advanced Features & Notes ---
        val pageInfo2 = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 2).create()
        val page2 = document.startPage(pageInfo2)
        val canvas2: Canvas = page2.canvas
        canvas2.drawRect(0f, 0f, pageWidth.toFloat(), 12f, headerBarPaint)

        y = 60f
        canvas2.drawText("PDF Viewer - メモ機能と検索仕様", 50f, y, titlePaint)
        y += 10f
        canvas2.drawLine(50f, y, (pageWidth - 50).toFloat(), y, linePaint)
        y += 30f

        canvas2.drawText("4. ドキュメント連携メモ", 50f, y, headingPaint)
        y += 24f
        canvas2.drawText("上部ツールバーのメニュー「…」から「メモを書く」を選択すると、", 50f, y, bodyPaint)
        y += 18f
        canvas2.drawText("このPDFファイル専用のテキストメモを作成・編集できます。", 50f, y, bodyPaint)
        y += 18f
        canvas2.drawText("保存されたメモはアプリ内のRoomデータベースに永続化され、", 50f, y, bodyPaint)
        y += 18f
        canvas2.drawText("次回同じPDFを開いた際にも自動的に復元されます。", 50f, y, bodyPaint)

        y += 40f
        canvas2.drawText("5. インテリジェント検索バー", 50f, y, headingPaint)
        y += 24f
        canvas2.drawText("下部ツールバーの「検索」アイコンをタップすると、検索入力バーが展開されます。", 50f, y, bodyPaint)
        y += 18f
        canvas2.drawText("キーワードを入力するとドキュメント内の該当ページと位置を検出し、", 50f, y, bodyPaint)
        y += 18f
        canvas2.drawText("前後の検索結果へワンタップでジャンプすることができます。", 50f, y, bodyPaint)

        y += 40f
        canvas2.drawText("6. ファイル名の変更", 50f, y, headingPaint)
        y += 24f
        canvas2.drawText("メニュー「…」の「ファイル名を変更する」から、表示タイトルを編集可能です。", 50f, y, bodyPaint)
        y += 18f
        canvas2.drawText("整理しやすい名前にリネームして閲覧履歴を綺麗に管理できます。", 50f, y, bodyPaint)

        canvas2.drawText("Page 2 of 3 - PDF Viewer Official Guide", 50f, (pageHeight - 30).toFloat(), footerPaint)
        document.finishPage(page2)

        // --- PAGE 3: Gestures & Immersive Reading ---
        val pageInfo3 = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 3).create()
        val page3 = document.startPage(pageInfo3)
        val canvas3: Canvas = page3.canvas
        canvas3.drawRect(0f, 0f, pageWidth.toFloat(), 12f, headerBarPaint)

        y = 60f
        canvas3.drawText("PDF Viewer - 操作方法と没入モード", 50f, y, titlePaint)
        y += 10f
        canvas3.drawLine(50f, y, (pageWidth - 50).toFloat(), y, linePaint)
        y += 30f

        canvas3.drawText("7. 没入型リーディング (Immersive UI)", 50f, y, headingPaint)
        y += 24f
        canvas3.drawText("スクロールやタップによって、上部バーおよび下部ツールバー、", 50f, y, bodyPaint)
        y += 18f
        canvas3.drawText("ページ数インジケーターがスムーズに非表示になります。", 50f, y, bodyPaint)
        y += 18f
        canvas3.drawText("画面のどこかをタップすると再度ツールバーが表示されます。", 50f, y, bodyPaint)

        y += 40f
        canvas3.drawText("8. ピンチズームとジェスチャー", 50f, y, headingPaint)
        y += 24f
        canvas3.drawText("2本指でのピンチ操作により、ページを拡大・縮小して細部まで読めます。", 50f, y, bodyPaint)
        y += 18f
        canvas3.drawText("ダブルタップによるクイック拡大リセットにも対応しています。", 50f, y, bodyPaint)

        y += 40f
        canvas3.drawText("9. お問い合わせ・サポート", 50f, y, headingPaint)
        y += 24f
        canvas3.drawText("本アプリはGoogle Material Design 3に準拠したデザインを採用しています。", 50f, y, bodyPaint)
        y += 18f
        canvas3.drawText("快適なPDF閲覧体験をお楽しみください！", 50f, y, accentPaint)

        canvas3.drawText("Page 3 of 3 - PDF Viewer Official Guide", 50f, (pageHeight - 30).toFloat(), footerPaint)
        document.finishPage(page3)

        try {
            FileOutputStream(destinationFile).use { out ->
                document.writeTo(out)
            }
        } finally {
            document.close()
        }
    }
}
