package com.vieneu.mobile.core

/**
 * Comfort DSP chain — hậu kỳ sau khi join PCM (mặc định ON trong [GenerationProfile]).
 *
 * Mục tiêu nghe lâu không gắt / không xé (validated trên iPhone loa ngoài, 2026-09-30):
 *  1. [TrebleFilter] — LP 6 kHz mix 0.5 (bù steps=8, §0.8)
 *  2. Presence dip 3 kHz −3 dB Q=1 — cắt band loa phone bơm (“gắt”)
 *  3. High-shelf 5.5 kHz −2.5 dB — de-ess / bớt “xé” phụ âm
 *  4. tanh soft-clip + peak-normalize −1 dBFS — hết clip cứng
 *
 * Hệ số biquad tính sẵn (RBJ, fs=24 kHz) — khớp NanoTtsEngine.mm trên iOS.
 * Chi phí ~vài ms / 30 s audio (không đáng kể RTF).
 *
 * Sửa hệ số = đổi audio contract → bump [GenerationProfile.PROFILE_REVISION].
 */
object AudioComfortFilter {
    const val SAMPLE_RATE = 24_000

    // Presence peaking 3 kHz, gain −3 dB, Q=1
    const val PRES_B0 = 0.9135889493293752
    const val PRES_B1 = -0.9957854991465043
    const val PRES_B2 = 0.49466440877807316
    const val PRES_A1 = -0.9957854991465043
    const val PRES_A2 = 0.4082533581074483

    // High-shelf 5.5 kHz, −2.5 dB
    const val SHELF_B0 = 0.8564610806506162
    const val SHELF_B1 = -0.05949127659576462
    const val SHELF_B2 = 0.14761344731755777
    const val SHELF_A1 = -0.23685227176481605
    const val SHELF_A2 = 0.18143552313722544

    const val SOFT_CLIP_DRIVE = 1.15f
    /** 10^(−1/20) — peak mục tiêu −1 dBFS. */
    const val TARGET_PEAK = 0.8912509381337456f

    fun biquad(
        wav: FloatArray,
        b0: Double, b1: Double, b2: Double, a1: Double, a2: Double,
    ): FloatArray {
        if (wav.isEmpty()) return wav
        val out = FloatArray(wav.size)
        var z1 = 0.0
        var z2 = 0.0
        for (i in wav.indices) {
            val x = wav[i].toDouble()
            val y = b0 * x + z1
            z1 = b1 * x + z2 - a1 * y
            z2 = b2 * x - a2 * y
            out[i] = y.toFloat()
        }
        return out
    }

    /** Treble + presence dip + shelf + soft-clip + peak-norm. */
    fun apply(wav: FloatArray): FloatArray {
        if (wav.isEmpty()) return wav
        var x = TrebleFilter.apply(wav)
        x = biquad(x, PRES_B0, PRES_B1, PRES_B2, PRES_A1, PRES_A2)
        x = biquad(x, SHELF_B0, SHELF_B1, SHELF_B2, SHELF_A1, SHELF_A2)

        val drive = SOFT_CLIP_DRIVE
        val norm = (1.0 / Math.tanh(drive.toDouble())).toFloat()
        var peak = 0f
        for (i in x.indices) {
            x[i] = Math.tanh((drive * x[i]).toDouble()).toFloat() * norm
            val a = kotlin.math.abs(x[i])
            if (a > peak) peak = a
        }
        if (peak > 1e-8f) {
            val g = TARGET_PEAK / peak
            for (i in x.indices) x[i] *= g
        }
        return x
    }
}
