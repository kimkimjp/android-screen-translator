# Screen Translator TODO

プラン: C:\Users\doard\.claude\plans\ancient-coalescing-puddle.md

- [x] 1. JDK / Android SDK / Gradle 導入（C:\Users\doard\dev 配下）
- [x] 2. プロジェクト雛形（Gradle設定・Manifest・アイコン・文字列）
- [x] 3. CaptureActivity + CaptureService + ScreenGrabber
- [x] 4. FloatingButton + TranslateTileService
- [x] 5. TextRecognizer + Translator
- [x] 6. ResultOverlay
- [x] 7. MainActivity（セットアップ画面）
- [x] 8. ビルド・lint・ユニットテスト → APK 生成、README 作成
- [x] 9. Android 15 対応（edge-to-edge、画面ロック自動停止後の再開通知、16KB 確認）→ v1.1
- [x] 10. Android 15 エミュレータで E2E 確認 → 不具合修正して v1.2
- [ ] 11. 実機確認（ユーザー側）→ 報告に基づき修正（2026-09-19 に堅牢化を追加。下部のチェックリストも参照）

## Android 15 エミュレータ確認結果（v1.2 相当, 2026-09-14）
- [x] 設定画面 edge-to-edge 表示（ステータスバーに潜らない）
- [x] 権限付与後の状態表示、翻訳モデル（ja/zh/ko）ダウンロード
- [x] 共有ダイアログの既定が「Entire screen」
- [x] 前景サービス type=mediaProjection で起動、フローティングボタン表示
- [x] ボタン → キャプチャ（ボタンは写り込まない）→ OCR → 翻訳 → 位置合わせ表示（英・中・韓・独）
- [x] 未取得言語（独）のモデル自動ダウンロード
- [x] 長押しで原文表示／タップで閉じる／戻るで閉じる
- [x] 通知「翻訳」から翻訳（シェードの写り込みなし）
- [x] クイック設定タイルから翻訳（シェードの写り込みなし）
- [x] 横画面：VirtualDisplay リサイズ、左カットアウト分のオフセットも一致
- [x] システム側で共有停止（Cast タイル）→ サービス終了・ボタン撤去・再開通知 → タップで再開
- [x] 通知「停止」→ 再開通知は出ない
- [x] クラッシュ／ANR なし
- 未確認: 画面ロックでの自動停止（エミュレータイメージが 15 QPR1 未満のため発生せず。同じ onStop 経路は上で確認済み）

### エミュレータで見つけて直した不具合
1. 中国語と韓国語が同じ画面にあると韓国語が崩れる（"계정 설정"→"天皇"、本文未翻訳）→ OCR 結果をブロック単位で統合
2. ステータスバーの時計も翻訳対象 → ステータスバー領域を除外
3. トースト文言「ドイツ語 の」の余分な空白

## 実機確認チェックリスト
- [ ] セットアップ：重ね表示権限・通知権限・モデルDL
- [ ] 英語アプリ：ボタンから翻訳、訳文の位置が原文と一致
- [ ] 中国語アプリ／韓国語アプリで翻訳
- [ ] タイルから翻訳（シェードが写り込まない）
- [ ] 通知の「翻訳」「停止」
- [ ] 画面回転後の翻訳
- [ ] 長押しで原文表示、タップ／戻るで閉じる
- [ ] ステータスバーから共有停止 → ボタンが消え、「再開」通知が出る
- [ ] (Android 15) 設定画面がステータスバー・ナビバーに潜らず、アイコンが見える
- [ ] (Android 15) 画面ロック → 解除 → 「再開」通知から再開できる

## Review（2026-09-14）
- `gradlew assembleDebug testDebugUnitTest lintDebug`: BUILD SUCCESSFUL、テスト 12/12 成功、lint 0 errors
  （残り警告は Switch→SwitchCompat 推奨、依存の新版あり、dataExtractionRules の3種で、動作に影響なし）
- APK: 115MB → arm64-v8a 限定＋ネイティブライブラリ圧縮で 19.9MB
- 実機・エミュレータでの動作は未確認（このPCに端末なし）。特に座標合わせ・フレーム取得タイミングは実機で要確認
- v1.1（Android 15 対応）: ビルド成功、テスト 12/12、lint 0 errors。16KB アラインは ELF ヘッダを直接読んで確認。
  エミュレータはハイパーバイザードライバ未導入（管理者権限が必要）のため実行不可
- 設計上の注意: MediaProjection の同意取得アクティビティに noHistory を付けると結果が返らないため付けない

## 2026-09-19 実機で壊れやすい箇所の堅牢化（v1.3 → 未リリース）

マルチエージェント（Gemini / Claude Sonnet / GPT-OSS）に案を出させ、Claude がコードと公式ドキュメントで
裏を取ってから採用したもの。ブランチ `update/2026-09-19-robustness`。

- [x] 重ね表示の `addView` を try/catch（`FloatingButton` / `ResultOverlay`）。メーカー独自の制限で拒否されても
      サービスが道連れで落ちず、必要な権限をトーストで案内する
- [x] フローティングボタンの座標を `currentWindowMetrics` 基準に変更。Service の `resources.displayMetrics` は
      回転に追従せず、横画面でボタンが画面外に出ていた。`onConfigurationChanged` で位置を戻す
- [x] `ScreenGrabber.toBitmap` の DirectByteBuffer を使い回し（WQHD 級で1枚十数MBを毎回確保していた）
- [x] `isModelReady` の `getDownloadedModels()` をキャッシュ（1画面に3言語あれば3往復していた）
- [x] `TextUtils` のテストを4件追加（重なりゼロのマージ / 空入力 / 確信度不足の英語フォールバック / 英語候補が無い短文）
- [x] 依存の更新は**見送り**。理由は `app/build.gradle.kts` のコメント参照

### 検証（すべて clean ビルド）

| 項目 | 結果 |
|---|---|
| `clean assembleDebug testDebugUnitTest lintDebug` | BUILD SUCCESSFUL |
| ユニットテスト | **18件全パス**（既存14 + 追加4。旧記載の「12件」は誤り） |
| lint | **0 errors** / 9 warnings。警告は全て既存カテゴリで、変更した Kotlin には1件も出ていない |
| APK | 19,895,367 バイト（変更前 19,892,747 に対して **+2,620 バイト**） |

**APK サイズを比べるときは必ず `clean` を挟むこと。** 依存を上げ下げした直後の増分ビルドでは
20.3MB や 23.8MB といった当てにならない値が出た。

### 依存更新を見送った経緯（実測）

- 最新安定版 → AGP 8.9.1〜9.1.0 と compileSdk 36〜37 を要求され AAR metadata で弾かれる
- coroutines 1.10 以降 → Kotlin 2.2 でビルドされており Kotlin 2.0.21 では読めない
- AGP 8.7.3 で通る範囲（core-ktx 1.16.0 / activity-ktx 1.10.1 / lifecycle 2.9.4）→ ビルドは通るが
  APK が 20.3MB → 24.2MB に増え、lifecycle 同梱の `NonNullableMutableLiveDataDetector` が lint をクラッシュさせる

### 実機確認の追加項目（ユーザー作業）

- [ ] 横画面に回したあと、フローティングボタンが画面内に残りタップできる
- [ ] 連続で10回以上翻訳してもメモリ起因の不調が出ない
- [ ] 複数言語が混在する画面で、初回のモデル取得後は翻訳開始が速くなる
- [ ] （該当端末のみ）重ね表示を拒否するメーカー設定で、アプリが落ちずに案内トーストが出る
