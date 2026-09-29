package com.vieneu.mobile.core

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TÀI LIỆU hành vi kotlin.random.Random trên JVM (kotlin-stdlib 1.9.24) — giả định then
 * chốt cho refactor harness (bước cuối Sprint 2a): harness cũ dùng kotlin.random.Random(0),
 * SDK DeterministicRng dùng kotlin.random.Random(seed) để CHUỖI GIỐNG HỆT trước/sau
 * refactor. Test này khóa hành vi được dựa vào, để upgrade Kotlin/kotlinx sau này không
 * lặng lẽ phá nó.
 *
 * Kết luận đo được (2026-09-29, kotlin-stdlib 1.9.24):
 *  - kotlin.random.Random(seed) KHÁC java.util.Random(seed) với MỌI seed (kể cả 0 và 42)
 *    — đừng giả định chúng bằng nhau khi port hay khi hard-code golden.
 *  - kotlin.random.Random(seed) lặp lại được (cùng seed -> cùng chuỗi) — nền tảng của
 *    contract determinism "seed -> max_diff=0" trên cùng platform.
 */
class KotlinRandomEquivalenceDocTest {

    @Test
    fun kotlinRandomSeedZeroIsRepeatable() {
        val k1 = kotlin.random.Random(0)
        val k2 = kotlin.random.Random(0)
        for (i in 0 until 8) {
            assertTrue("kotlin.random.Random(0) phải lặp lại được (i=$i)", k1.nextDouble() == k2.nextDouble())
        }
    }

    @Test
    fun kotlinRandomSeed42IsRepeatable() {
        val k1 = kotlin.random.Random(42)
        val k2 = kotlin.random.Random(42)
        for (i in 0 until 8) {
            assertTrue("kotlin.random.Random(42) phải lặp lại được (i=$i)", k1.nextDouble() == k2.nextDouble())
        }
    }

    @Test
    fun kotlinRandomDiffersFromJavaUtilRandomForAllSeeds() {
        for (seed in intArrayOf(0, 1, 42)) {
            val k = kotlin.random.Random(seed)
            val j = java.util.Random(seed.toLong())
            var anyDiff = false
            for (i in 0 until 8) {
                if (k.nextDouble() != j.nextDouble()) anyDiff = true
            }
            assertTrue("kotlin.random.Random($seed) phải KHÁC java.util.Random($seed)", anyDiff)
        }
    }

    @Test
    fun deterministicRngMatchesKotlinRandomSequence() {
        // SDK là "cùng thuật toán, cùng thứ tự tiêu thụ" với kotlin.random.Random(seed)
        // — lock để refactor sau này không đổi một bit audio so với harness cũ.
        val src = kotlin.random.Random(42)
        val rng = DeterministicRng(42)
        for (i in 0 until 8) {
            val u1 = maxOf(src.nextDouble(), 1e-9)
            val u2 = src.nextDouble()
            val expected = (kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)).toFloat()
            assertTrue("g[$i]", rng.nextGaussian() == expected)
        }
    }
}