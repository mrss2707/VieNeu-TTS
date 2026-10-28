package com.vieneu.mobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Câu dài không dấu ngắt: số mảnh phải là ceil(len/max) và khớp Python từng chuỗi. */
class LongSentenceSplitTest {
    private val golden = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("golden/long_sentence_split.json")!!.readText(),
    ).jsonObject

    @Test
    fun matchesPythonPacking() {
        assertTrue(golden.isNotEmpty())
        for ((name, v) in golden) {
            val o = v.jsonObject
            val max = o["max"]!!.jsonPrimitive.content.toInt()
            val expected = o["chunks"]!!.jsonArray.map { it.jsonPrimitive.content }
            val got = TextSegmenter.packSentencesIntoChunks(listOf(o["sentence"]!!.jsonPrimitive.content), max)
            assertEquals(name, expected, got)
        }
    }

    @Test
    fun threeHundredFiveCharsAtMax140IsThreeBalancedPiecesNotTwoGreedyOnes() {
        val words = "ông ngồi bên hiên nhà nhìn ra cánh đồng lúa chín vàng trải dài đến tận chân trời không một bóng người"
        val s = (("$words ").repeat(3)).trim()
        assertEquals(305, s.length)
        val got = TextSegmenter.packSentencesIntoChunks(listOf(s), 140)
        assertEquals(3, got.size)
        assertTrue(got.all { it.length <= 140 })
        assertTrue("phải chia đều, không dồn sát trần: ${got.map { it.length }}", got.maxOf { it.length } - got.minOf { it.length } <= 20)
    }
}
