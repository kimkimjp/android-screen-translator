package com.doard.screentranslator

import java.lang.Character.UnicodeScript

/** OCR 結果の整形と言語判定の純粋ロジック（Android 非依存でユニットテスト可能）。 */
object TextUtils {

    private fun count(text: String, predicate: (UnicodeScript) -> Boolean): Int =
        text.codePoints().filter { predicate(UnicodeScript.of(it)) }.count().toInt()

    fun countHan(text: String) = count(text) { it == UnicodeScript.HAN }
    fun countHangul(text: String) = count(text) { it == UnicodeScript.HANGUL }
    fun hasKana(text: String) =
        count(text) { it == UnicodeScript.HIRAGANA || it == UnicodeScript.KATAKANA } > 0

    private fun isNoSpaceScript(c: Char): Boolean {
        val script = UnicodeScript.of(c.code)
        if (script == UnicodeScript.HAN || script == UnicodeScript.HIRAGANA || script == UnicodeScript.KATAKANA) {
            return true
        }
        val block = Character.UnicodeBlock.of(c)
        return block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
    }

    /** ブロック内の行を1文に連結する。中国語は詰めて、それ以外は空白で、英単語のハイフン改行は結合。 */
    fun joinLines(lines: List<String>): String {
        val sb = StringBuilder()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (sb.isEmpty()) {
                sb.append(line)
                continue
            }
            val prev = sb[sb.length - 1]
            val next = line[0]
            when {
                prev == '-' && sb.length >= 2 && sb[sb.length - 2].isLetter() && next.isLowerCase() -> {
                    sb.setLength(sb.length - 1)
                    sb.append(line)
                }
                prev == '-' -> sb.append(line)
                isNoSpaceScript(prev) && isNoSpaceScript(next) -> sb.append(line)
                else -> sb.append(' ').append(line)
            }
        }
        return sb.toString()
    }

    /** 数字・記号だけのブロックや1文字だけのアルファベット（アイコン等）は翻訳しない。 */
    fun isTranslatable(text: String): Boolean {
        if (countHan(text) + countHangul(text) > 0) return true
        return text.count { it.isLetter() } >= 2
    }

    /**
     * 中国語モデルと韓国語モデルの OCR 結果をブロック単位で統合する。
     * 中国語と韓国語が同じ画面に混在していても、領域ごとに文字体系に合う方を採用する。
     * 韓国語側のハングルを含むブロックが、重なる中国語側ブロックの漢字数より多くのハングルを持つなら韓国語側を採用。
     */
    fun <T> mergeRecognitions(
        chinese: List<T>,
        korean: List<T>,
        text: (T) -> String,
        overlaps: (T, T) -> Boolean,
    ): List<T> {
        val removed = HashSet<Int>()
        val chosenKorean = ArrayList<T>()
        for (ko in korean) {
            val hangul = countHangul(text(ko))
            if (hangul == 0) continue
            val overlapping = chinese.indices.filter { overlaps(chinese[it], ko) }
            val han = overlapping.sumOf { countHan(text(chinese[it])) }
            if (hangul > han) {
                chosenKorean.add(ko)
                removed.addAll(overlapping)
            }
        }
        return chinese.filterIndexed { i, _ -> i !in removed } + chosenKorean
    }

    /**
     * 翻訳元の言語コードを決める。翻訳不要（日本語）なら null。
     * @param candidates ML Kit Language ID の候補 (言語タグ, 信頼度)
     * @param isSupported ML Kit 翻訳が対応している言語か
     */
    fun resolveSourceLanguage(
        text: String,
        candidates: List<Pair<String, Float>>,
        isSupported: (String) -> Boolean,
    ): String? {
        if (hasKana(text)) return null
        if (countHangul(text) > 0) return "ko"
        if (countHan(text) > 0) return "zh"

        // ラテン文字のみ。ローマ字表記の判定結果（zh-Latn 等）は除外する
        val latinCandidates = candidates
            .map { (tag, confidence) -> tag.substringBefore('-') to confidence }
            .filter { (lang, _) -> lang !in NON_LATIN_LANGS && isSupported(lang) }

        // UI の短いラベル（"Settings" 等）は判定がぶれやすいので英語を優先
        if (text.length <= SHORT_TEXT_LENGTH && latinCandidates.any { it.first == "en" && it.second >= 0.1f }) {
            return "en"
        }
        val top = latinCandidates.maxByOrNull { it.second }
        return if (top != null && top.second >= 0.5f) top.first else "en"
    }

    private val NON_LATIN_LANGS = setOf("und", "ja", "zh", "ko")
    private const val SHORT_TEXT_LENGTH = 24
}
