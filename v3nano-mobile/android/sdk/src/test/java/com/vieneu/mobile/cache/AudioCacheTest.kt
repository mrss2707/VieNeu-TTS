package com.vieneu.mobile.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AudioCacheTest {

    private fun tmpRoot() = Files.createTempDirectory("vnm-cache").toFile()

    @Test
    fun putGetRoundTrip() {
        val cache = AudioCache(tmpRoot(), "vnm-0.1.0")
        val pcm = FloatArray(100) { it * 0.01f }
        cache.put("chunk-0", pcm)
        assertTrue(cache.contains("chunk-0"))
        val got = cache.get("chunk-0")!!
        assertEquals(pcm.size, got.size)
        assertEquals(pcm[10], got[10], 0f)
    }

    @Test
    fun evictsOldestWhenOverQuota() {
        val root = tmpRoot()
        // quota nhỏ: 3 chunk × 400 bytes ≈ evict bớt
        val cache = AudioCache(root, "rev", quotaBytes = 900)
        val pcm = FloatArray(100) // 400 bytes
        cache.put("a", pcm)
        Thread.sleep(5)
        cache.put("b", pcm)
        Thread.sleep(5)
        cache.put("c", pcm)
        Thread.sleep(5)
        cache.put("d", pcm)
        assertTrue(cache.sizeBytes() <= 900 + 400) // có thể còn 2 file sau evict
        assertTrue(cache.entryCount() <= 3)
    }

    @Test
    fun clearRemovesAll() {
        val cache = AudioCache(tmpRoot(), "rev2")
        cache.put("x", FloatArray(4))
        cache.clear()
        assertFalse(cache.contains("x"))
        assertNull(cache.get("x"))
        assertEquals(0, cache.entryCount())
    }
}
