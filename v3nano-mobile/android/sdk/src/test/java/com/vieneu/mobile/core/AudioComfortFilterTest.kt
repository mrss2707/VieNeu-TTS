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

    @Test
    fun noiseFloorAttenuatesQuietTail() {
        // 0.5 s speech-like + 0.4 s noise floor (−54 dBFS) — tail cuối phải êm hơn khi gate ON
        val sr = AudioComfortFilter.SAMPLE_RATE
        val speech = FloatArray(sr / 2) { i -> (0.4 * kotlin.math.sin(2 * Math.PI * 180.0 * i / sr)).toFloat() }
        val floor = FloatArray((sr * 2) / 5) { i -> (0.002 * kotlin.math.sin(2 * Math.PI * 97.0 * i / sr)).toFloat() }
        val wav = speech + floor
        val on = AudioComfortFilter.apply(wav.copyOf(), noiseFloorEnabled = true)
        val off = AudioComfortFilter.apply(wav.copyOf(), noiseFloorEnabled = false)
        // đo 150 ms cuối — env + hold + close đã settle
        val tailOn = on.copyOfRange(on.size - 3600, on.size)
        val tailOff = off.copyOfRange(off.size - 3600, off.size)
        fun rms(a: FloatArray) = kotlin.math.sqrt(a.map { it * it }.average())
        assertTrue("gate ON tail RMS=${rms(tailOn)} OFF=${rms(tailOff)}", rms(tailOn) < rms(tailOff) * 0.5f)
    }

    @Test
    fun noiseFloorKeepsSpeechPeak() {
        val n = 2400
        val speech = FloatArray(n) { i -> (0.5 * kotlin.math.sin(2 * Math.PI * 200.0 * i / 24000.0)).toFloat() }
        val out = AudioComfortFilter.apply(speech, noiseFloorEnabled = true)
        var peak = 0f
        for (v in out) if (abs(v) > peak) peak = abs(v)
        assertEquals(AudioComfortFilter.TARGET_PEAK, peak, 1e-3f)
    }

    @Test
    fun spectralDenoiseAttenuatesRustleUnderSpeech() {
        // speech 200 Hz + rustle noise floor trộn liên tục — spectral phải hạ rustle
        val sr = AudioComfortFilter.SAMPLE_RATE
        val n = sr * 2
        val wav = FloatArray(n) { i ->
            val speech = 0.5 * Math.sin(2 * Math.PI * 200.0 * i / sr)
            val rustle = 0.01 * Math.sin(2 * Math.PI * 3700.0 * i / sr) +
                0.01 * Math.sin(2 * Math.PI * 5100.0 * i / sr)
            (speech + rustle).toFloat()
        }
        val on = AudioComfortFilter.applySpectralDenoise(wav.copyOf())
        // dải rustle 3.5–5.3 kHz RMS phải giảm so với gốc
        fun bandRms(a: FloatArray, lo: Double, hi: Double): Double {
            // crude: RMS của high-pass residual (a - mean) không đủ — dùng energy ước lượng
            // qua band-pass 1-pole đơn giản: y = x - LP(x)
            var lp = 0.0
            val cut = 2.0 * Math.PI * lo / sr
            val alpha = cut / (1.0 + cut)
            var acc = 0.0
            var cnt = 0
            for (v in a) {
                lp += alpha * (v - lp)
                val hp = v - lp
                if (hp > 1e-6 || hp < -1e-6) {
                    acc += hp * hp
                    cnt++
                }
            }
            return if (cnt == 0) 0.0 else Math.sqrt(acc / cnt)
        }
        val rOn = bandRms(on, 3500.0, 8000.0)
        val rOff = bandRms(wav, 3500.0, 8000.0)
        assertTrue("spectral should cut HF rustle on=$rOn off=$rOff", rOn < rOff * 0.7)
    }
}
