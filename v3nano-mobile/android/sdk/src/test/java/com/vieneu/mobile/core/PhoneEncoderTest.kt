package com.vieneu.mobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** PhoneEncoder vs golden phone_ids.json (v3nano-mobile/host/export_golden_contract.py). */
class PhoneEncoderTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResource("golden/$name")) { "missing golden/$name" }
            .readText(Charsets.UTF_8)

    private val encoder: PhoneEncoder by lazy {
        PhoneEncoder.fromTokenizerJson(resource("tokenizer.json"))
    }

    @Test
    fun goldenSamplesMatch() {
        val golden = Json.parseToJsonElement(resource("phone_ids.json")).jsonObject
        val samples = golden["samples"]!!.jsonObject
        for ((key, value) in samples) {
            val phones = value.jsonObject["phones"]!!.jsonPrimitive.content
            val expected = value.jsonObject["ids"]!!.jsonArray.map { it.jsonPrimitive.content.toLong() }.toLongArray()
            assertArrayEquals("sample '$key'", expected, encoder.encode(phones))
        }
    }

    @Test
    fun emotionTagsReplacedBeforeVocabLookup() {
        // <|emotion_1|> -> ① (U+2460); golden phone_ids giữ index vocab của ①
        val ids = encoder.encode("<|emotion_1|>")
        val vocab = Json.parseToJsonElement(resource("phone_ids.json")).jsonObject["vocab"]!!.jsonObject
        val one = vocab["①"]!!.jsonPrimitive.content.toLong()
        assertArrayEquals(longArrayOf(1L, one, 2L), ids)
    }

    @Test
    fun oovDroppedAndWarnedOnce() {
        // 😀 (U+1F600, surrogate pair) ngoài vocab -> DROP, không fail cả request;
        // mỗi code unit được cảnh báo ĐÚNG MỘT LẦN (v3nano.py:237-240 warned-once)
        val warnings = mutableListOf<String>()
        val ids = encoder.encode("ɑ😀b", onOov = { warnings.add(it) })
        assertArrayEquals(longArrayOf(1, 42, 16, 2), ids)
        assertEquals(2, warnings.size)

        val again = mutableListOf<String>()
        encoder.encode("ɑ😀b", onOov = { again.add(it) })
        assertEquals(0, again.size)
    }

    @Test
    fun bosEosPadIds() {
        assertEquals(1, encoder.bos)
        assertEquals(2, encoder.eos)
        assertEquals(0, encoder.pad)
    }
}