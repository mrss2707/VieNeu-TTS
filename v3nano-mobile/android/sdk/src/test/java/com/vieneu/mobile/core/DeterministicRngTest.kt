package com.vieneu.mobile.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RNG (b): pin chuỗi seed=42 — hai lần chạy GIỐNG NHAU tuyệt đối (determinism nội bộ
 * platform). Giá trị đầu hard-code từ kotlin.random.Random(42)+Box-Muller (đo trên
 * kotlin-stdlib 1.9.24) — nếu upgrade stdlib làm đổi chuỗi, test này FAIL và buộc
 * review chủ động (không lặng lẽ drift).
 *
 * KHÔNG kỳ vọng bit-exact với numpy PCG64/ziggurat — quyết định RNG (b) đã chốt.
 */
class DeterministicRngTest {

    // g[0..7] của seed=42, pin cứng từ kotlin.random.Random(42)+Box-Muller (kotlin-stdlib
    // 1.9.24) — giá trị này KHÔNG phụ thuộc java.util.Random (đo được hai bên khác nhau).
    private val PINNED_SEED42 = floatArrayOf(
        1.4254984f, 1.5888556f, -1.5381043f, 1.0108517f,
        0.87825894f, 0.3180051f, -0.5842155f, -1.333386f,
    )

    @Test
    fun seed42TwoRunsIdentical() {
        val a = DeterministicRng(42).nextGaussians(1000)
        val b = DeterministicRng(42).nextGaussians(1000)
        assertArrayEquals(a, b, 0f)
    }

    @Test
    fun seed42PinnedFirstSamples() {
        val rng = DeterministicRng(42)
        for (i in PINNED_SEED42.indices) {
            assertEquals("g[$i]", PINNED_SEED42[i], rng.nextGaussian(), 0f)
        }
    }

    @Test
    fun differentSeedsDiffer() {
        val a = DeterministicRng(7).nextGaussians(64)
        val b = DeterministicRng(8).nextGaussians(64)
        var anyDiff = false
        for (i in a.indices) if (a[i] != b[i]) anyDiff = true
        assertTrue(anyDiff)
    }

    @Test
    fun seedLawPerChunk() {
        // v3nano.py:424-430: chunk i chạy với seed+i — chunk khác nhau KHÔNG lặp chuỗi
        assertEquals(42, DeterministicRng.seedForChunk(42, 0))
        assertEquals(43, DeterministicRng.seedForChunk(42, 1))
        val c0 = DeterministicRng(DeterministicRng.seedForChunk(42, 0)).nextGaussians(64)
        val c1 = DeterministicRng(DeterministicRng.seedForChunk(42, 1)).nextGaussians(64)
        assertFalse(c0.contentEquals(c1))
    }
}