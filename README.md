# PDF Viewer + Paper Renamer

PDFの閲覧・検索・メモ・共有に、Paper Renamer v1.0.2の論文PDF整理機能を統合しています。

## 📱 APKダウンロード (解凍不要)
ZIP 解凍なしで直接インストールできる最新の `.apk` ファイルです：
- ⬇️ **[最新版 APK を直接ダウンロード (app-debug.apk)](https://github.com/a14398138/PDF-viewer2/releases/download/latest/app-debug.apk)**
- 📦 [Releases ページで確認する](https://github.com/a14398138/PDF-viewer2/releases)


## 論文PDFリネーム

1. ホーム右上の「論文PDFリネーム」（星アイコン）を開きます。
2. フォルダを選択し、読み書きアクセスを許可します。
3. 元の名前・新しい名前・DOIを確認します。「PDFを開いて確認」で内蔵ビューアへ移動し、戻ると候補一覧へ戻れます。
4. 変更する候補を選び、「リネーム」→確認ダイアログの「実行」を押します。

- PDFの文書メタデータと先頭3ページからDOIを検出し、Crossrefから書誌情報を取得します。
- 名前は「出版年_筆頭著者_et_al_タイトル.pdf」。著者1名ではet_alなし。
- DOI未検出・取得失敗・同名衝突はスキップします。サブフォルダは走査しません。
- 選択フォルダと処理済み結果を記憶します。増分再スキャンは未変更の候補を再利用し、処理済みPDFを省略します。全件再解析はキャッシュを無視します。
- 実ファイルの名前を変更します。自動的な取り消し機能はありません。
- 対応する閲覧履歴のファイル名・URIを更新し、メモ・閲覧位置・既存キャッシュを保持します。
- 処理中は戻る・フォルダ変更・重複実行を無効にします。画面の再生成にはViewModelで対応します。アプリ強制終了後の処理の自動再開は行いません。
- 既存ビューアの手動リネームは従来どおり履歴上の表示名変更です。

## プライバシーと移行

PDFは端末外に送信しません。抽出したDOIのみHTTPSでCrossrefへ送信します。
Storage Access Frameworkを使用し、広範なストレージ権限は要求しません。
旧Paper Renamerアプリの設定はAndroidのアプリ分離のため自動移行されません。最初にフォルダを再選択してください。
PDF ViewerのアプリID・既存データベースは変更しません。

## 検証

JDK 17、Gradle 9.3.1、プロジェクト指定のAndroid SDKを使用します。
既存のGitHub Actionsがデバッグ署名を準備し、単体テストとAPKビルドを実行します。

```sh
gradle :app:testDebugUnitTest :app:assembleDebug
```

実機ではフォルダ権限の再許可、候補確認、名前衝突、再起動後の増分処理、
全件再解析、リネーム前後のメモ・ページ位置、機内モード時の失敗表示を確認してください。

## Attribution

Paper Renamer由来のコードはApache-2.0です。
原典: https://github.com/a14398138/paper-renamer/tree/v1.0.2
ライセンス表記は THIRD_PARTY_NOTICES.md を参照してください。
