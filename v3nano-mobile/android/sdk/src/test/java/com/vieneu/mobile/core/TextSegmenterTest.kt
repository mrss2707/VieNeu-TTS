package com.vieneu.mobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TextSegmenter vs golden structural (`chunk_split_structural.json`) —
 * identity normalize, khớp component Python (split/pack/punc_norm/classify/merge).
 * Full pipeline (sea-g2p normalize) lock bằng Python contract tests.
 */
class TextSegmenterTest {

    private fun golden() = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader?.getResource("golden/chunk_split_structural.json")).readText()
    ).jsonObject

    @Test
    fun structuralChunksMatchGolden() {
        val cases = golden()["cases"]!!.jsonArray
        assertTrue(cases.isNotEmpty())
        for (el in cases) {
            val c = el.jsonObject
            val id = c["case_id"]!!.jsonPrimitive.content
            val text = c["text"]!!.jsonPrimitive.content
            val expChunks = c["structural_chunks"]!!.jsonArray.map { it.jsonPrimitive.content }
            val expGaps = c["structural_gaps"]!!.jsonArray.map { it.jsonPrimitive.content }
            val expSents = c["sentences"]!!.jsonArray.map { it.jsonPrimitive.content }

            assertEquals("$id sentences", expSents, TextSegmenter.splitIntoSentences(text))

            val got = TextSegmenter.segmentWithGaps(
                text,
                maxChars = golden()["max_chars"]!!.jsonPrimitive.content.toInt(),
                minChunkChars = golden()["min_chunk_chars"]!!.jsonPrimitive.content.toInt(),
                normalizer = TextNormalizer.identity,
            )
            assertEquals("$id n_chunks", expChunks.size, got.chunks.size)
            assertEquals("$id chunks", expChunks, got.chunks)
            assertEquals("$id gaps", expGaps, got.gaps)
        }
    }

    @Test
    fun sentenceScanIsQuoteAware() {
        // `...` là ranh giới câu; `?` trong nháy KHÔNG cắt — khớp split_into_sentences Python
        val text = """Có phải ... kiểu như: "Rồi sao nữa? Mình phải làm đến bao giờ?", đúng không anh?"""
        val sents = TextSegmenter.splitIntoSentences(text)
        assertEquals(2, sents.size)
        assertEquals("Có phải ...", sents[0])
        assertTrue(sents[1].contains("đúng không anh"))
        assertTrue(sents[1].contains("Rồi sao nữa?"))
    }

    @Test
    fun sentenceScanDoesNotSplitDecimalNumbers() {
        val sents = TextSegmenter.splitIntoSentences("Giá SP500 hôm nay là 4.200,5 điểm.")
        assertEquals(1, sents.size)
    }

    @Test
    fun puncNormForcesPeriodOnShortSentences() {
        // punc_norm không lowercase (lowercase thuộc normalize)
        assertEquals("Ôi, thật tuyệt vời.", TextSegmenter.puncNorm("Ôi, thật tuyệt vời!"))
        assertEquals("bạn đã ăn cơm chưa?", TextSegmenter.puncNorm("bạn đã ăn cơm chưa?"))
        assertEquals("thêm dấu chấm.", TextSegmenter.puncNorm("thêm dấu chấm"))
    }

    @Test
    fun classifyGapByTrailingPunct() {
        assertEquals("sentence", TextSegmenter.classifyGap("xin chào."))
        assertEquals("sentence", TextSegmenter.classifyGap("thật à?"))
        assertEquals("minor", TextSegmenter.classifyGap("giữa câu,"))
        assertEquals("minor", TextSegmenter.classifyGap("cắt cưỡng bức"))
    }

    @Test
    fun gapTypesFollowParagraphAndPunctuation() {
        // 2 đoạn đủ dài (không bị mergeShort) → gap "para" giữ nguyên
        val text = "Mở đầu đoạn một với đầy đủ nội dung để qua ngưỡng tối thiểu.\n\n" +
            "Mở đầu đoạn hai cũng dài tương tự nhằm không bị gộp chunk."
        val got = TextSegmenter.segmentWithGaps(text, maxChars = 140, minChunkChars = 20)
        assertEquals(2, got.size)
        assertEquals(listOf("para"), got.gaps)

        // 2 câu cùng đoạn, tổng vượt maxChars → 2 chunk, gap "sentence"
        val onePara = "Câu thứ nhất đã đủ dài để chiếm gần trọn một chunk rồi. " +
            "Câu thứ hai cũng dài tương đương nên phải tách ra chunk riêng."
        val got2 = TextSegmenter.segmentWithGaps(onePara, maxChars = 60, minChunkChars = 10)
        assertEquals(2, got2.size)
        assertEquals(listOf("sentence"), got2.gaps)
    }

    @Test
    fun mergeShortChunksPrefersNonParaNeighbor() {
        // "Tiêu đề" (7 chữ) + "nội dung..." — minChunkChars=20 phải gộp
        val got = TextSegmenter.segmentWithGaps(
            "Tiêu đề\nNội dung chương một khá dài để không bị gộp thêm lần nữa.",
            maxChars = 140,
            minChunkChars = 20,
        )
        assertEquals(1, got.size)
    }

    @Test
    fun effectiveLenSkipsEmotionTokens() {
        assertEquals(3, TextSegmenter.effectiveLen("<|emotion_1|> abc"))
    }
}
