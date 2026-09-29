package com.vieneu.mobile.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * RNG cho nhiễu khởi tạo flow — giữ NGUYÊN ngữ nghĩa NanoBenchmarkCore.kt:141-146
 * (kotlin.random.Random(seed) + Gaussian qua Box-Muller, cùng thứ tự gọi nextDouble).
 *
 * QUYẾT ĐỊNH RNG (b) của kế hoạch 001-mvp — ĐÃ CHỐT, đừng mở lại nếu không có dữ kiện mới:
 * KHÔNG port PCG64/ziggurat của numpy bit-exact (numpy dùng PCG64 + ziggurat, khác
 * JVM tuyệt đối; rủi ro drift cao, và cross-platform không khả thi vì
 * ORT 1.22 ≠ 1.24/SIMD). contract determinism là: CÙNG platform, cùng seed -> max_diff=0;
 * cross-platform so bằng WER (±1 điểm) và năng lượng tín hiệu.
 *
 * Dùng kotlin.random.Random (KHÔNG phải java.util.Random) để chuỗi GIỐNG HỆT harness cũ —
 * đo được hai bên khác nhau khi seed=0 (xem KotlinRandomEquivalenceDocTest). Trên native
 * Kotlin/Native hành vi khác — không dùng class này cho iOS, iOS giữ std::mt19937 riêng.
 */
class DeterministicRng(seed: Int) {
    private val rng = kotlin.random.Random(seed)

    /** Một mẫu Gaussian chuẩn (Box-Muller, float32) — thứ tự tiêu thụ nextDouble
     * y hệt harness để refactor không đổi một bit audio. */
    fun nextGaussian(): Float {
        val u1 = max(rng.nextDouble(), 1e-9)
        val u2 = rng.nextDouble()
        return (sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)).toFloat()
    }

    fun nextGaussians(count: Int): FloatArray = FloatArray(count) { nextGaussian() }

    companion object {
        /** Luật seed theo chunk (v3nano.py:424-430): chunk i chạy với seed+i, không
         * dùng lại seed gốc cho mọi chunk (nếu không các chunk giống nhau ra audio
         * giống hệt nhau). */
        fun seedForChunk(seed: Int, chunkIndex: Int): Int = seed + chunkIndex
    }
}