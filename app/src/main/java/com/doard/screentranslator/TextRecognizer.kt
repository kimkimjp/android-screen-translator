package com.doard.screentranslator

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await

data class OcrBlock(val text: String, val box: Rect, val lineCount: Int)

/**
 * 中国語・韓国語の認識モデル（どちらもラテン文字も認識できる）を並列実行し、
 * 領域ごとに文字体系に合う方の結果を採用する。
 */
class TextRecognizer : AutoCloseable {
    private val chinese = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val korean = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    /** @param excludeTop この y 座標より上（ステータスバー）に収まるブロックは除外する */
    suspend fun recognize(bitmap: Bitmap, excludeTop: Int): List<OcrBlock> = coroutineScope {
        val image = InputImage.fromBitmap(bitmap, 0)
        val zh = async { chinese.process(image).await() }
        val ko = async { korean.process(image).await() }
        val merged = TextUtils.mergeRecognitions(
            toBlocks(zh.await()),
            toBlocks(ko.await()),
            text = { it.text },
            overlaps = ::mostlyOverlaps,
        )
        merged.filter { TextUtils.isTranslatable(it.text) && it.box.bottom > excludeTop }
    }

    private fun toBlocks(result: Text): List<OcrBlock> = result.textBlocks.mapNotNull { block ->
        val box = block.boundingBox ?: return@mapNotNull null
        OcrBlock(TextUtils.joinLines(block.lines.map { it.text }), box, block.lines.size.coerceAtLeast(1))
    }

    /** 小さい方のブロックの半分以上が重なっていれば同じ領域とみなす */
    private fun mostlyOverlaps(a: OcrBlock, b: OcrBlock): Boolean {
        val intersection = Rect()
        if (!intersection.setIntersect(a.box, b.box)) return false
        val smaller = minOf(a.box.width() * a.box.height(), b.box.width() * b.box.height())
        return smaller > 0 && intersection.width() * intersection.height() * 2 >= smaller
    }

    override fun close() {
        chinese.close()
        korean.close()
    }
}
