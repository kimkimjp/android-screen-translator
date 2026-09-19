package com.doard.screentranslator

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

data class TranslatedBlock(val source: OcrBlock, val translation: String)

/** OCR ブロックを言語判定し、ML Kit オンデバイス翻訳で日本語にする。 */
class ScreenTranslator : AutoCloseable {
    private val languageId = LanguageIdentification.getClient()
    private val translators = HashMap<String, Translator>()

    /**
     * @param onModelDownload 未ダウンロードの言語モデルを取得し始めるときに呼ばれる（言語コード）
     */
    suspend fun translate(blocks: List<OcrBlock>, onModelDownload: (String) -> Unit): List<TranslatedBlock> {
        val bySource = LinkedHashMap<String, MutableList<OcrBlock>>()
        for (block in blocks) {
            val lang = resolveLanguage(block.text) ?: continue
            bySource.getOrPut(lang) { mutableListOf() }.add(block)
        }

        val results = HashMap<OcrBlock, String>()
        for ((lang, group) in bySource) {
            val translator = translators.getOrPut(lang) {
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(lang)
                        .setTargetLanguage(TARGET)
                        .build()
                )
            }
            if (!isModelReady(lang)) {
                onModelDownload(lang)
                // Wi-Fi 必須にはしない。モバイル回線だとダウンロードが始まらず、
                // 翻訳が理由の分からないまま失敗する。代わりに呼び出し側がトーストで知らせる。
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                downloadedModels = null
            }
            for (block in group) {
                results[block] = translator.translate(block.text).await()
            }
        }
        // 元の並び順を保つ
        return blocks.mapNotNull { block -> results[block]?.let { TranslatedBlock(block, it) } }
    }

    private suspend fun resolveLanguage(text: String): String? {
        val candidates = try {
            languageId.identifyPossibleLanguages(text).await().map { it.languageTag to it.confidence }
        } catch (e: Exception) {
            emptyList()
        }
        return TextUtils.resolveSourceLanguage(text, candidates) {
            TranslateLanguage.fromLanguageTag(it) != null
        }.also { Log.d(TAG, "lang=$it candidates=${candidates.take(3)} text=${text.take(40)}") }
    }

    /**
     * 取得済みモデルの一覧。1画面に複数言語があると言語ごとに問い合わせが走るので覚えておく。
     * ダウンロード後は null に戻して取り直す。
     */
    private var downloadedModels: Set<String>? = null

    private suspend fun downloadedModels(): Set<String> =
        downloadedModels ?: RemoteModelManager.getInstance()
            .getDownloadedModels(TranslateRemoteModel::class.java).await()
            .map { it.language }
            .toSet()
            .also { downloadedModels = it }

    private suspend fun isModelReady(lang: String): Boolean {
        // 英語モデルは ML Kit に内蔵されていて個別のダウンロードは要らない（公式ドキュメント）。
        val downloaded = downloadedModels()
        return (lang == TranslateLanguage.ENGLISH || lang in downloaded) && TARGET in downloaded
    }

    override fun close() {
        languageId.close()
        translators.values.forEach { it.close() }
        translators.clear()
        downloadedModels = null
    }

    companion object {
        private const val TAG = "ScreenTranslator"
        const val TARGET = TranslateLanguage.JAPANESE

        /** セットアップ画面で事前にダウンロードするモデル（英語は端末に常駐） */
        val PRELOAD_LANGUAGES = listOf(TranslateLanguage.JAPANESE, TranslateLanguage.CHINESE, TranslateLanguage.KOREAN)
    }
}
