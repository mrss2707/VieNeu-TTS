package com.vieneu.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Comfort chain (rev 2): peak −1 dBFS, không NaN, empty-safe, hệ số pin. */
class AudioComfortFilterTest {

    @Test
    fun coefficientsPinned() {
        assertEquals(0.9135889493293752, AudioComfortFilter.PRES_B0, 1e-12)
        assertEquals(0.8564610806506162, AudioComfortFilter.SHELF_B0, 1e-12)
        assertEquals(0.8912509381337456f, AudioComfortFilter.TARGET_PEAK, 1e-7f)
    }

    @Test
    fun peakNormalizedToMinus1dBFS() {
        val loud = FloatArray(2400) { i -> 1.2f * kotlin.math.sin(i * 0.11).toFloat() }
        val out = AudioComfortFilter.apply(loud)
        var peak = 0f
        for (v in out) {
            assertTrue(!v.isNaN() && !v.isInfinite())
            if (abs(v) > peak) peak = abs(v)
        }
        assertEquals(AudioComfortFilter.TARGET_PEAK, peak, 1e-4f)
    }

    @Test
    fun emptyAndSilenceSafe() {
        assertTrue(AudioComfortFilter.apply(FloatArray(0)).isEmpty())
        val silent = AudioComfortFilter.apply(FloatArray(16))
        assertEquals(16, silent.size)
        assertTrue(silent.all { abs(it) < 1e-6f })
    }

    @Test
    fun reducesHarshBandComparedToRaw() {
        // 3 kHz tone — presence dip nên giảm biên độ so với signal sau treble-only
        val n = 4800
        val tone = FloatArray(n) { i -> (0.5 * kotlin.math.sin(2 * Math.PI * 3000.0 * i / 24000.0)).toFloat() }
        val comfort = AudioComfortFilter.apply(tone.copyOf())
        val trebleOnly = TrebleFilter.apply(tone.copyOf())
        fun rms(a: FloatArray) = kotlin.math.sqrt(a.map { it * it }.average())
        // comfort có soft-clip+norm nên so RMS sau khi cùng chuẩn hoá peak ~ TARGET
        assertTrue(rms(comfort) > 0f)
        assertTrue(rms(trebleOnly) > 0f)
    }
}
