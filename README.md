# 画面翻訳（Screen Translator）

どのアプリの画面でも、ワンタップで文字を読み取り、日本語訳を元の位置に重ねて表示する Android アプリです。
翻訳は Google ML Kit のオンデバイス翻訳を使うため、無料・API キー不要で、モデル取得後はオフラインでも動きます。

- 対応する元言語: 英語などラテン文字の言語、中国語（簡体・繁体）、韓国語 → 日本語
- 起動方法: フローティングボタン／クイック設定タイル「画面を翻訳」／通知の「翻訳」
- 対象: Android 14 / 15 以降（arm64 端末）。targetSdk 35 (Android 15)

## Android 15 対応状況
| Android 15 の変更 | 対応 |
|---|---|
| edge-to-edge 強制（targetSdk 35） | `enableEdgeToEdge()` ＋インセットを自前でパディング |
| 16KB ページサイズ | 同梱ネイティブライブラリ3種すべて LOAD セグメント 16KB アラインを確認済み |
| 画面ロックで画面共有が自動停止（15 QPR1〜） | 停止を検知してボタンを片付け、「タップで再開」通知を表示 |
| ステータスバーの共有チップから停止 | 同上 |
| SYSTEM_ALERT_WINDOW でのバックグラウンドからの前景サービス開始制限 | 前景サービスは常に画面表示中のアクティビティから開始するため影響なし |
| `startActivityAndCollapse(Intent)` 廃止 | API 34+ は PendingIntent 版を使用 |
| 前景サービスの 6 時間制限 | `dataSync` / `mediaProcessing` のみが対象で、`mediaProjection` は対象外 |

## インストール
1. [Releases](../../releases/latest) から `ScreenTranslator-1.3.apk` をスマホのブラウザでダウンロード
   （または PC でダウンロードして Google ドライブや USB でスマホへ転送）
2. スマホでAPKを開き、「この提供元のアプリを許可」をオンにしてインストール
   （Play プロテクトの警告が出たら「詳細」→「インストールする」）

## 初回セットアップ（アプリを開くと順に案内）
1. **他のアプリの上に重ねて表示** を許可（必須）
2. **通知** を許可（推奨）
3. **翻訳モデル** をダウンロード（日本語・中国語・韓国語 各約30MB、Wi-Fi推奨。英語は内蔵済みでダウンロード不要）
4. **開始** → 画面共有の確認で「開始」

## 使い方
- 翻訳したいアプリを開いて丸いボタンをタップ → 数秒で訳文が重なります
- 訳文は **タップ** または **戻る** で閉じます。**長押し中** は原文が見えます
- ボタンはドラッグで移動でき、離すと画面端に寄ります
- 通知シェードの編集（鉛筆アイコン）から「画面を翻訳」タイルを追加すると、ボタンを出さずに使えます
  （設定画面で「フローティングボタンを表示」をオフ）
- 使い終わったら通知の「停止」、またはアプリの「停止」

## 制約・既知の注意点
- Android の仕様で、画面キャプチャは **開始のたびに確認** が必要です（再起動後や停止後）
- Android 15 QPR1 以降は **画面ロックで共有が自動停止** します。ロック解除後は「タップで再開」通知・タイル・アプリから再開してください
- 共有確認で「1つのアプリ」が選べる端末では **「画面全体」** を選んでください（1つのアプリだと他のアプリが翻訳できません）
- セッション中はステータスバーに画面共有中のアイコンが表示されます
- 銀行アプリなど画面キャプチャを禁止しているアプリ（FLAG_SECURE）は真っ黒に写るため翻訳できません
- Android の「設定」アプリなど、タップジャッキング対策で他アプリの重ね表示を隠す画面では、ボタンと訳文が表示されません
  （その画面ではタイルや通知から翻訳しても訳文が隠されます）
- 初めての言語（ドイツ語など）は、モデルのダウンロードが終わるまで訳文が表示されません
- 翻訳精度は Google 翻訳（Web）よりやや劣ります。UI の短いラベルは訳されずに原文のまま返ることがあります（例: "Cancel subscription"）
- ラテン文字は言語を自動判定します（ドイツ語・フランス語などは初回に該当モデルを自動ダウンロード）

## ビルド方法（開発者向け）
```
JAVA_HOME=C:\Users\doard\dev\jdk-17
local.properties: sdk.dir=C:/Users/doard/dev/android-sdk
gradlew assembleDebug testDebugUnitTest lintDebug
```
出力: `app/build/outputs/apk/debug/app-debug.apk`

### Android 15 エミュレータでの確認
AEHD ドライバ導入済み。AVD `Pixel_API35`（Android 15 / API 35, x86_64）。
```
emulator -avd Pixel_API35 -no-window -no-audio -gpu swiftshader_indirect
gradlew assembleDebug -Pabis=x86_64   # エミュレータ用に x86_64 を含める
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
重ね表示のテストには「設定」アプリではなく Chrome などを使う（設定アプリは重ね表示を隠す）。

### 構成
| ファイル | 役割 |
|---|---|
| `MainActivity` | セットアップ画面（権限・モデル・開始/停止） |
| `CaptureActivity` | 画面キャプチャ同意の取得とキャプチャ要求の中継（透明） |
| `CaptureService` | 前景サービス。キャプチャ → OCR → 翻訳 → 表示 の制御 |
| `ScreenGrabber` | VirtualDisplay を保持し最新フレームを Bitmap 化 |
| `TextRecognizer` | ML Kit OCR（中国語・韓国語モデルを並列実行し文字体系で選択） |
| `Translator` (`ScreenTranslator`) | 言語判定と ML Kit 翻訳 |
| `TextUtils` | 行連結・言語判定の純粋ロジック（ユニットテストあり） |
| `FloatingButton` / `ResultOverlay` | 重ね表示の UI |
| `TranslateTileService` | クイック設定タイル |

## ライセンス
MIT License（[LICENSE](LICENSE)）。アイコンは Material Icons（Apache License 2.0）を使用しています。
