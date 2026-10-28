package com.vieneu.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Logic emotion-cue của front-end G2P (backend giả — lõi Rust được test ở :sdk-g2p). */
class G2pFrontEndTest {

    /**
     * normalize = trim + lowercase (normalizer thật cũng bỏ khoảng trắng đầu/cuối — được
     * kiểm bằng parity với Python ở :sdk-g2p); phonemize = "‹text|pn›" để thấy cờ punc_norm.
     */
    private class FakeBackend : G2pBackend {
        val calls = ArrayList<String>()
        override fun normalize(text: String, puncNorm: Boolean): String {
            calls.add("n:$text:$puncNorm"); return text.trim().lowercase()
        }
        override fun phonemize(text: String, puncNorm: Boolean): String {
            calls.add("p:$text:$puncNorm"); return "‹${text.trim()}|${if (puncNorm) 1 else 0}›"
        }
        override fun puncNorm(text: String): String = "$text!"
    }

    @Test
    fun splitKeepingCuesMatchesPythonReSplit() {
        assertEquals(listOf("a ", "[cười]", " b"), EmotionCues.splitKeepingCues("a [cười] b"))
        assertEquals(listOf("", "[sigh]", ""), EmotionCues.splitKeepingCues("[sigh]"))
        assertEquals(listOf("x", "<|emotion_2|>", "y", "[ok]", "z"), EmotionCues.splitKeepingCues("x<|emotion_2|>y[ok]z"))
        assertEquals(listOf("không cue"), EmotionCues.splitKeepingCues("không cue"))
    }

    @Test
    fun tagTokenMapping() {
        assertEquals("<|emotion_1|>", EmotionCues.tagToken("[cười]"))
        assertEquals("<|emotion_2|>", EmotionCues.tagToken("[ Thở Dài ]"))
        assertEquals("<|emotion_3|>", EmotionCues.tagToken("[clear throat]"))
        assertEquals("<|emotion_7|>", EmotionCues.tagToken("<|emotion_7|>"))
        assertNull(EmotionCues.tagToken("[ghi chú]"))
    }

    @Test
    fun plainChunkIsOnePhonemizeWithPuncNorm() {
        val b = FakeBackend()
        assertEquals("‹xin chào.|1›", ChunkPhonemizer(b).phonemize("xin chào."))
        assertEquals(listOf("p:xin chào.:true"), b.calls)
    }

    @Test
    fun cueChunkKeepsTokensAttachesPunctAndPuncNormsOnce() {
        val b = FakeBackend()
        val out = ChunkPhonemizer(b).phonemize("xin chào [cười] bạn khỏe không?")
        assertEquals("‹xin chào|0› <|emotion_1|> ‹bạn khỏe không?|0›!", out)
        assertEquals(listOf("p:xin chào :false", "p: bạn khỏe không?:false"), b.calls)
    }

    @Test
    fun cueAtEndAndUnknownTagFallThroughAsText() {
        val b = FakeBackend()
        assertEquals("‹a|0› <|emotion_2|>!", ChunkPhonemizer(b).phonemize("a [sigh]"))
        val b2 = FakeBackend()
        assertEquals("‹a|0› ‹[ghi chú]|0› ‹b|0›!", ChunkPhonemizer(b2).phonemize("a [ghi chú] b"))
    }

    @Test
    fun attachingPunctuationGluesToPreviousToken() {
        val b = object : G2pBackend {
            override fun normalize(text: String, puncNorm: Boolean) = text
            override fun phonemize(text: String, puncNorm: Boolean) = text.trim()
            override fun puncNorm(text: String) = text
        }
        assertEquals("<|emotion_1|>. tiếp", ChunkPhonemizer(b).phonemize("[cười]. tiếp"))
    }

    @Test
    fun normalizerKeepsCuesAndNeverUsesPuncNormPerSentence() {
        val b = FakeBackend()
        val n = SeaTextNormalizer(b)
        assertEquals("xin chào", n.normalize("Xin Chào"))
        assertEquals("a <|emotion_1|> b", n.normalizeKeepCues("A [cười] B"))
        assertEquals("<|emotion_3|> a", n.normalizeKeepCues("[hắng giọng] A"))
        assertEquals(true, b.calls.all { it.endsWith(":false") })
    }
}
