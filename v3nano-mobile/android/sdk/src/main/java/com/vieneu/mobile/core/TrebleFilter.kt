package com.vieneu.mobile.core

/**
 * Gentle low-pass treble filter — biquad SOS bậc 2, cutoff 6 kHz, fs=24 kHz, mix 50%.
 *
 * Hệ số SOS tính TRƯỚC từ scipy.signal.butter(2, 6000, btype='low', fs=24000, output='sos')
 * (v3nano-mobile/host/apply_treble_filter.py — giá trị thu được: b0=b2=0.2928932188134525,
 * b1=0.585786437626905, a1≈0, a2=0.17157287525380993, a0=1.0). Đầu ra:
 * filtered = sosfilt(sos, x)   (IIR causal, filter trực tiếp trạng thái double)
 * out[i]   = (1-MIX)*x[i] + MIX*filtered[i]   (số học float32 — khớp numpy)
 *
 * Mục đích (xem docs/_supermeo/_summary/001-v3-nano-mobile.md §0.8): steps=8 tổng năng
 * lượng thấp hơn ~9.5% khiến dải 1-3 kHz nổi tương đối; filter đưa tỷ trọng phổ về gần
 * steps=16, chi phí RTF ≈0.00026 — áp HOẶC không tuỳ GenerationProfile.trebleFilterEnabled.
 *
 * Golden test: TrebleFilterTest phải khớp treble_sos.json ±1e-4 trên đáp ứng xung.
 */
object TrebleFilter {
    const val SAMPLE_RATE = 24_000
    const val CUTOFF_HZ = 6_000
    const val ORDER = 2
    const val MIX = 0.5

    // SOS section (a0=1.0) — xem KDoc trên; sửa ở đây = đổi audio contract, phải bump model revision.
    const val B0 = 0.2928932188134525
    const val B1 = 0.585786437626905
    const val B2 = 0.2928932188134525
    const val A1 = -1.8403695052228406e-16
    const val A2 = 0.17157287525380993

    val SOS: DoubleArray = doubleArrayOf(B0, B1, B2, 1.0, A1, A2)

    /** Lọc thuần (chưa mix) — direct-form-2 transposed biquad, trạng thái double,
     * mỗi mẫu output đúc float32 (khớp numpy sosfilt trên input float32). */
    fun filter(wav: FloatArray): FloatArray {
        if (wav.isEmpty()) return wav
        val out = FloatArray(wav.size)
        var z1 = 0.0
        var z2 = 0.0
        for (i in wav.indices) {
            val x = wav[i].toDouble()
            val y = B0 * x + z1
            z1 = B1 * x + z2 - A1 * y
            z2 = B2 * x - A2 * y
            out[i] = y.toFloat()
        }
        return out
    }

    /** Áp filter + mix 50% với bản gốc (số học float32) — tương đương apply_treble_filter(). */
    fun apply(wav: FloatArray): FloatArray {
        val filt = filter(wav)
        return FloatArray(wav.size) { i -> (1.0f - MIX.toFloat()) * wav[i] + MIX.toFloat() * filt[i] }
    }
}