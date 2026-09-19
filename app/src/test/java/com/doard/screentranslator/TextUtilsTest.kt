package com.doard.screentranslator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUtilsTest {

    private val allSupported: (String) -> Boolean = { true }

    @Test
    fun joinLines_latinUsesSpaces() {
        assertEquals("Hello world again", TextUtils.joinLines(listOf("Hello", " world ", "again")))
    }

    @Test
    fun joinLines_mergesHyphenatedWord() {
        assertEquals("translation example", TextUtils.joinLines(listOf("transla-", "tion example")))
    }

    @Test
    fun joinLines_keepsHyphenBeforeCapital() {
        assertEquals("Wi-Fi",TextUtils.joinLines(listOf("Wi-", "Fi")))
    }

    @Test
    fun joinLines_chineseHasNoSpaces() {
        assertEquals("这是一个测试，句子", TextUtils.joinLines(listOf("这是一个测试，", "句子")))
    }

    @Test
    fun joinLines_koreanUsesSpaces() {
        assertEquals("안녕하세요 반갑습니다", TextUtils.joinLines(listOf("안녕하세요", "반갑습니다")))
    }

    @Test
    fun joinLines_skipsBlankLines() {
        assertEquals("a b", TextUtils.joinLines(listOf("a", "  ", "b")))
    }

    @Test
    fun isTranslatable() {
        assertFalse(TextUtils.isTranslatable("12:45"))
        assertFalse(TextUtils.isTranslatable("X"))
        assertFalse(TextUtils.isTranslatable("+ 100%"))
        assertTrue(TextUtils.isTranslatable("OK"))
        assertTrue(TextUtils.isTranslatable("中"))
        assertTrue(TextUtils.isTranslatable("한"))
    }

    /** 縦方向の位置 [top, bottom) だけを持つテスト用ブロック */
    private data class B(val text: String, val top: Int, val bottom: Int)

    private fun merge(zh: List<B>, ko: List<B>) =
        TextUtils.mergeRecognitions(zh, ko, { it.text }) { a, b -> a.top < b.bottom && b.top < a.bottom }

    @Test
    fun merge_mixedChineseAndKoreanScreen() {
        val zh = listOf(B("Account settings", 0, 10), B("账户设置", 20, 30), B("天皇", 40, 50))
        val ko = listOf(B("Account settings", 0, 10), B("챵호설치", 20, 30), B("계정 설정", 40, 50), B("구독은 갱신됩니다", 60, 70))
        val result = merge(zh, ko).map { it.text }
        assertEquals(listOf("Account settings", "账户设置", "계정 설정", "구독은 갱신됩니다"), result)
    }

    @Test
    fun merge_latinOnlyKeepsChineseResult() {
        val zh = listOf(B("Hello", 0, 10))
        val ko = listOf(B("Hello", 0, 10))
        assertEquals(zh, merge(zh, ko))
    }

    @Test
    fun merge_chineseWinsWhenKoreanIsGarbage() {
        val zh = listOf(B("您的订阅将自动续订", 0, 10))
        val ko = listOf(B("의 가", 0, 10))
        assertEquals(zh, merge(zh, ko))
        assertTrue(TextUtils.isTranslatable(zh[0].text))
        assertFalse(TextUtils.hasKana(zh[0].text))
    }

    @Test
    fun resolve_scripts() {
        assertEquals("ko", TextUtils.resolveSourceLanguage("설정 Settings", emptyList(), allSupported))
        assertEquals("zh", TextUtils.resolveSourceLanguage("设置", listOf("ja" to 0.9f), allSupported))
        assertNull(TextUtils.resolveSourceLanguage("設定する", emptyList(), allSupported))
    }

    @Test
    fun resolve_shortLatinPrefersEnglish() {
        val candidates = listOf("de" to 0.6f, "en" to 0.3f)
        assertEquals("en", TextUtils.resolveSourceLanguage("Profile", candidates, allSupported))
    }

    @Test
    fun resolve_longLatinUsesConfidentCandidate() {
        val text = "Bitte geben Sie Ihr Passwort erneut ein"
        assertEquals("de", TextUtils.resolveSourceLanguage(text, listOf("de" to 0.95f, "en" to 0.02f), allSupported))
    }

    @Test
    fun resolve_fallsBackToEnglish() {
        val text = "some ambiguous long text here ok"
        assertEquals("en", TextUtils.resolveSourceLanguage(text, listOf("und" to 1f), allSupported))
        assertEquals("en", TextUtils.resolveSourceLanguage(text, listOf("zh-Latn" to 0.9f), allSupported))
        assertEquals("en", TextUtils.resolveSourceLanguage(text, listOf("xx" to 0.9f)) { it == "en" })
    }

    @Test
    fun merge_keepsKoreanBlockThatOverlapsNothing() {
        // 中国語側に対応する領域が無いハングルのブロックは、何も消さずに残る
        val zh = listOf(B("你好", 0, 10))
        val ko = listOf(B("안녕하세요", 20, 30))
        assertEquals(listOf("你好", "안녕하세요"), merge(zh, ko).map { it.text })
    }

    @Test
    fun merge_handlesEmptyInput() {
        assertEquals(emptyList<B>(), merge(emptyList(), emptyList()))
        val zh = listOf(B("设置", 0, 10))
        assertEquals(zh, merge(zh, emptyList()))
    }

    @Test
    fun resolve_longTextWithLowConfidenceFallsBackToEnglish() {
        // 対応言語だが確信度が足りないときは、推測せず英語として扱う
        val text = "this line is long enough to skip the short label rule"
        assertEquals("en", TextUtils.resolveSourceLanguage(text, listOf("de" to 0.3f), allSupported))
    }

    @Test
    fun resolve_shortTextWithoutEnglishCandidateUsesTopCandidate() {
        // 短文の英語優先は「英語の候補があるとき」だけ。無ければ確信度で決める
        assertEquals("de", TextUtils.resolveSourceLanguage("Profil", listOf("de" to 0.9f), allSupported))
    }
}
