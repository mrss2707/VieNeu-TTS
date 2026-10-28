package com.vieneu.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** De-esser động 5–8 kHz (rev 5): chỉ hạ khi dải sibilant trội, KHÔNG đụng dải giọng. */
class DeEsserTest {
    private val sr = AudioComfortFilter.SAMPLE_RATE

    private fun tone(hz: Double, amp: Double, seconds: Double): FloatArray =
        FloatArray((seconds * sr).toInt()) { (amp * sin(2 * Math.PI * hz * it / sr)).toFloat() }

    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i].toDouble() * x[i]
        return sqrt(s / (to - from))
    }

    private fun db(a: Double, b: Double) = 20 * log10(a / b)

    /** Giọng 500 Hz + (tuỳ chọn) 6.6 kHz giữa đoạn: mô phỏng phụ âm xát chói bất chợt. */
    private fun voiceWithBurst(burstAmp: Double): FloatArray {
        val n = (0.6 * sr).toInt()
        val v = tone(500.0, 0.3, 0.6)
        val b = tone(6600.0, burstAmp, 0.6)
        return FloatArray(n) { i -> if (i in (0.25 * sr).toInt()..(0.35 * sr).toInt()) v[i] + b[i] else v[i] }
    }

    @Test
    fun voiceOnlyIsUntouched() {
        val x = tone(500.0, 0.3, 0.5) + tone(1200.0, 0.2, 0.5).let { FloatArray(it.size) }
        val y = AudioComfortFilter.applyDeEsser(x)
        var maxDiff = 0.0
        for (i in x.indices) maxDiff = maxOf(maxDiff, abs((x[i] - y[i]).toDouble()))
        assertTrue("giọng không có sibilant phải gần như nguyên vẹn, lệch tối đa $maxDiff", maxDiff < 2e-3)
    }

    /** Năng lượng RMS của dải [hpHz, lpHz] trong [from, to) — đo bằng chính bộ lọc dải của SDK. */
    private fun bandRms(x: FloatArray, hpHz: Double, lpHz: Double, from: Int, to: Int): Double {
        val hp = AudioComfortFilter.rbjPass(true, hpHz)
        val lp = AudioComfortFilter.rbjPass(false, lpHz)
        val f = AudioComfortFilter.biquad(
            AudioComfortFilter.biquad(x, hp[0], hp[1], hp[2], hp[3], hp[4]),
            lp[0], lp[1], lp[2], lp[3], lp[4],
        )
        return rms(f, from, to)
    }

    @Test
    fun loudSibilantBurstIsAttenuatedWhileVoiceBandIsKept() {
        val x = voiceWithBurst(burstAmp = 0.25)
        val y = AudioComfortFilter.applyDeEsser(x)
        val a = (0.28 * sr).toInt(); val b = (0.32 * sr).toInt()
        // Đo TRỰC TIẾP từng dải (phép đo "phần bị trừ so với tổng" từng sai vì tổng có cả giọng 500 Hz).
        val sibDb = db(bandRms(y, 5000.0, 8000.0, a, b), bandRms(x, 5000.0, 8000.0, a, b))
        val voiceDb = db(bandRms(y, 300.0, 3400.0, a, b), bandRms(x, 300.0, 3400.0, a, b))
        assertTrue("burst 6.6 kHz phải giảm ≥3 dB, thực tế $sibDb dB", sibDb < -3.0)
        assertTrue("không được giảm quá maxReduce 12 dB (+0.5 dung sai): $sibDb dB", sibDb > -12.5)
        assertEquals("dải giọng không được đổi", 0.0, voiceDb, 0.2)
        // trước burst, hai dải giữ nguyên
        assertEquals(rms(x, 0, (0.20 * sr).toInt()), rms(y, 0, (0.20 * sr).toInt()), 2e-3)
    }

    @Test
    fun brightnessAt3800HzIsAttenuatedNotJustAbove5kHz() {
        // Hồi quy: rev 5 chỉ nhắm 5–8 kHz nên "rít/xì" ở 3.4–4 kHz (76% khung sáng nhất đo trên điện thoại) lọt qua.
        val n = (0.6 * sr).toInt()
        val v = tone(500.0, 0.3, 0.6)
        val b = tone(3800.0, 0.25, 0.6)
        val x = FloatArray(n) { i -> if (i in (0.25 * sr).toInt()..(0.35 * sr).toInt()) v[i] + b[i] else v[i] }
        val y = AudioComfortFilter.applyDeEsser(x)
        val a = (0.28 * sr).toInt(); val e = (0.32 * sr).toInt()
        val dB = db(bandRms(y, 3400.0, 4400.0, a, e), bandRms(x, 3400.0, 4400.0, a, e))
        val voiceDb = db(bandRms(y, 300.0, 3000.0, a, e), bandRms(x, 300.0, 3000.0, a, e))
        // Đo thực: ~−1.2 dB (burst nhẹ) … −1.8 dB (burst to) — thấp hơn gain tính toán (−3.5…−8.9 dB) vì 3.8 kHz
        // nằm sát cạnh dưới của dải sibilant (băng thông 2 biquad Q=0.707 dốc thoải). rev 5 (5–8 kHz) cho ≈ 0 dB.
        assertTrue("3.8 kHz phải giảm ≥0.8 dB (rev 5 cho ≈0), thực tế $dB dB", dB < -0.8)
        assertEquals("dải giọng không đổi", 0.0, voiceDb, 0.2)
    }

    @Test
    fun reductionNeverExceedsMaxAndOutputIsFinite() {
        val x = voiceWithBurst(burstAmp = 0.9)
        val y = AudioComfortFilter.applyDeEsser(x)
        assertTrue(y.all { it.isFinite() })
        val a = (0.27 * sr).toInt(); val b = (0.33 * sr).toInt()
        // sibilant ra không thể nhỏ hơn 10^(−12/20) = 0.251 lần sibilant vào (kẹp maxReduce)
        val sibIn = rms(tone(6600.0, 0.9, 0.6), a, b)
        val removed = FloatArray(x.size) { x[it] - y[it] }
        assertTrue("không được cắt quá maxReduce: ${rms(removed, a, b) / sibIn}", rms(removed, a, b) / sibIn <= 1.0 - 0.251 + 0.05)
    }

    @Test
    fun emptyAndSilenceAreSafe() {
        assertTrue(AudioComfortFilter.applyDeEsser(FloatArray(0)).isEmpty())
        val s = AudioComfortFilter.applyDeEsser(FloatArray(4800))
        assertTrue(s.all { it == 0f })
    }

    @Test
    fun parametersArePinned() {
        // Đổi tham số = đổi hợp đồng âm thanh ba nền tảng → phải bump PROFILE_REVISION và cập nhật iOS.
        assertEquals(-9.0, AudioComfortFilter.DS_THRESH_DB, 0.0)
        assertEquals(6.0, AudioComfortFilter.DS_RATIO, 0.0)
        assertEquals(10.0, AudioComfortFilter.DS_MAX_REDUCE_DB, 0.0)
        assertEquals(0.0008, AudioComfortFilter.DS_ENV_ATTACK_SEC, 0.0)
        assertEquals(0.008, AudioComfortFilter.DS_ENV_RELEASE_SEC, 0.0)
        assertEquals(0.001, AudioComfortFilter.DS_GAIN_ATTACK_SEC, 0.0)
        assertEquals(0.040, AudioComfortFilter.DS_GAIN_RELEASE_SEC, 0.0)
        assertEquals(6, GenerationProfile.PROFILE_REVISION)
        assertTrue(GenerationProfile().deEsserEnabled)
    }

    @Test
    fun chainWithDeEsserOffMatchesRev4Behaviour() {
        val x = voiceWithBurst(burstAmp = 0.25)
        val off = AudioComfortFilter.apply(x.copyOf(), noiseFloorEnabled = true, spectralEnabled = true, deEsserEnabled = false)
        val default = AudioComfortFilter.apply(x.copyOf(), noiseFloorEnabled = true, spectralEnabled = true)
        assertTrue("mặc định ở tầng hàm là TẮT (giữ hợp đồng cũ cho caller cũ)", off.contentEquals(default))
        val on = AudioComfortFilter.apply(x.copyOf(), noiseFloorEnabled = true, spectralEnabled = true, deEsserEnabled = true)
        assertTrue("bật de-esser phải đổi đầu ra", !on.contentEquals(off))
    }

    @Test
    fun filterCoefficientsMatchRbjFormula() {
        val hp = AudioComfortFilter.rbjPass(true, 5000.0)
        // HP Q=1/√2: b0 = b2 = −b1/2 ; DC gain = 0
        assertEquals(hp[0], hp[2], 1e-12)
        assertEquals(-2 * hp[0], hp[1], 1e-12)
        assertEquals(0.0, hp[0] + hp[1] + hp[2], 1e-12)
        val lp = AudioComfortFilter.rbjPass(false, 8000.0)
        assertEquals(lp[0], lp[2], 1e-12)
        assertEquals(2 * lp[0], lp[1], 1e-12)
    }
}
