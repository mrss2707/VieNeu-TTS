package com.vieneu.mobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TrebleFilter vs golden treble_sos.json — đáp ứng xung ±1e-4, hệ số ±1e-12.
 * (07-kiem-thu.md §7.4) */
class TrebleFilterTest {

    private fun golden(): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(
            checkNotNull(javaClass.classLoader?.getResource("golden/treble_sos.json")).readText()
        ).jsonObject

    @Test
    fun sosCoefficientsMatchScipyGolden() {
        val goldenSos = golden()["sos"]!!.jsonArray[0].jsonArray.map { it.jsonPrimitive.content.toDouble() }
        assertEquals(6, TrebleFilter.SOS.size)
        for (i in goldenSos.indices) {
            assertEquals("sos[$i]", goldenSos[i], TrebleFilter.SOS[i], 1e-12)
        }
    }

    @Test
    fun impulseResponseMatchesGolden() {
        val impulse = golden()["impulse_input"]!!.jsonArray.map { it.jsonPrimitive.content.toFloat() }.toFloatArray()
        val expected = golden()["impulse_filtered"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() }.toDoubleArray()
        val filtered = TrebleFilter.filter(impulse)
        assertEquals(expected.size, filtered.size)
        for (i in expected.indices) {
            assertEquals("impulse[$i]", expected[i], filtered[i].toDouble(), 1e-4)
        }
    }

    @Test
    fun toneResponseAndMixFormula() {
        val tone = golden()["tone_input"]!!.jsonArray.map { it.jsonPrimitive.content.toFloat() }.toFloatArray()
        val expected = golden()["tone_filtered"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() }.toDoubleArray()
        val filtered = TrebleFilter.filter(tone)
        for (i in expected.indices) {
            assertEquals("tone[$i]", expected[i], filtered[i].toDouble(), 1e-4)
        }
        // out = (1-MIX)*x + MIX*filtered (số học float32)
        val mixed = TrebleFilter.apply(tone)
        val mix = TrebleFilter.MIX.toFloat()
        for (i in tone.indices) {
            val expectedMix = (1.0f - mix) * tone[i] + mix * filtered[i]
            assertEquals("mix[$i]", expectedMix, mixed[i], 1e-6f)
        }
        // wav rỗng: passthrough
        assertTrue(TrebleFilter.apply(FloatArray(0)).isEmpty())
        assertTrue(TrebleFilter.filter(FloatArray(0)).isEmpty())
    }
}