package com.vieneu.mobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ChunkJoiner vs golden join_gaps.json — sai lệch ±1 sample (07-kiem-thu.md §7.4). */
class ChunkJoinerTest {

    private fun golden(): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(
            checkNotNull(javaClass.classLoader?.getResource("golden/join_gaps.json")).readText()
        ).jsonObject

    private fun floatsOf(el: kotlinx.serialization.json.JsonElement): FloatArray =
        el.jsonArray.map { it.jsonPrimitive.content.toFloat() }.toFloatArray()

    @Test
    fun gapSilenceTableMatchesGolden() {
        val gap = golden()["gap_silence"]!!.jsonObject
        assertEquals(0.70, ChunkJoiner.V3_GAP_SILENCE["para"]!!, 1e-12)
        assertEquals(0.50, ChunkJoiner.V3_GAP_SILENCE["sentence"]!!, 1e-12)
        assertEquals(0.30, ChunkJoiner.V3_GAP_SILENCE["minor"]!!, 1e-12)
        assertEquals(gap["para"]!!.jsonPrimitive.content.toDouble(), ChunkJoiner.V3_GAP_SILENCE["para"]!!, 1e-12)
        assertEquals(gap["sentence"]!!.jsonPrimitive.content.toDouble(), ChunkJoiner.V3_GAP_SILENCE["sentence"]!!, 1e-12)
        assertEquals(gap["minor"]!!.jsonPrimitive.content.toDouble(), ChunkJoiner.V3_GAP_SILENCE["minor"]!!, 1e-12)
    }

    @Test
    fun joinPairsMatchGolden() {
        val pairs = golden()["pairs"]!!.jsonArray
        for (el in pairs) {
            val p = el.jsonObject
            val name = p["name"]!!.jsonPrimitive.content
            val sr = p["sample_rate"]!!.jsonPrimitive.content.toInt()
            val prev = floatsOf(p["prev"]!!)
            val next = floatsOf(p["next"]!!)
            val pauseS = p["pause_seconds"]!!.jsonPrimitive.content.toDouble()

            val (lead, tailRaw) = ChunkJoiner.edgeSilence(prev, sr)
            val expLead = p["prev_lead_silence"]!!.jsonPrimitive.content.toInt()
            val expTail = p["prev_tail_silence"]!!.jsonPrimitive.content.toInt()
            assertTrue("$name prev_lead=$lead (expect $expLead)", kotlin.math.abs(expLead - lead) <= 1)
            val prevTailForPad = if (lead == prev.size) prev.size else tailRaw
            assertTrue("$name prev_tail", kotlin.math.abs(expTail - prevTailForPad) <= 1)

            val (nextLead, _) = ChunkJoiner.edgeSilence(next, sr)
            assertTrue("$name next_lead", kotlin.math.abs(p["next_lead_silence"]!!.jsonPrimitive.content.toInt() - nextLead) <= 1)

            val pad = ChunkJoiner.pausePadSamples(prev, next, sr, pauseS)
            assertTrue("$name pad", kotlin.math.abs(p["pad_samples"]!!.jsonPrimitive.content.toInt() - pad) <= 1)

            val joined = ChunkJoiner.joinAudioChunks(listOf(prev, next), sr, listOf(pauseS.let { if (pauseS == 0.7) "para" else if (pauseS == 0.5) "sentence" else "minor" }))
            assertTrue("$name joined", kotlin.math.abs(p["joined_samples"]!!.jsonPrimitive.content.toInt() - joined.size) <= 1)
        }
    }

    @Test
    fun edgeCases() {
        // danh sách rỗng
        assertTrue(ChunkJoiner.joinAudioChunks(emptyList(), 24000, emptyList()).isEmpty())
        // chunk đơn: passthrough nguyên bản (không copy vào mảng mới làm mất dữ liệu)
        val single = floatArrayOf(1f, 2f, 3f)
        org.junit.Assert.assertSame(single, ChunkJoiner.joinAudioChunks(listOf(single), 24000, emptyList()))
        // gap type lạ -> pause "sentence" + onEvent báo một lần
        val prev = FloatArray(24000) { 0f }
        val next = FloatArray(24000) { 0f }
        val events = mutableListOf<String>()
        val out = ChunkJoiner.joinAudioChunks(listOf(prev, next), 24000, listOf("weird"), onEvent = { events.add(it) })
        assertEquals(1, events.size)
        // hai mảng toàn im lặng: tail prev 24000 + lead next 24000 đã "đủ" pause 0.5s -> pad = 0
        assertEquals(48000, out.size)
    }
}