package com.vieneu.mobile.lifecycle

import com.vieneu.mobile.api.Metrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ThermalPolicyTest {

    @Test
    fun mapsAndroidStatusToBuckets() {
        assertEquals("none", ThermalPolicy.bucketFor(ThermalPolicy.STATUS_NONE))
        assertEquals("warm", ThermalPolicy.bucketFor(ThermalPolicy.STATUS_LIGHT))
        assertEquals("warm", ThermalPolicy.bucketFor(ThermalPolicy.STATUS_MODERATE))
        assertEquals("hot", ThermalPolicy.bucketFor(ThermalPolicy.STATUS_SEVERE))
        assertEquals("hot", ThermalPolicy.bucketFor(ThermalPolicy.STATUS_CRITICAL))
    }

    @Test
    fun hotTierForcesSingleThreadAndPrefetchFloor() {
        val b = ThermalPolicy.budgetFor(ThermalPolicy.STATUS_SEVERE)
        assertEquals("hot", b.tierName)
        assertEquals(1, b.threads)
        assertTrue(b.prefetchDepth >= 2)
        assertTrue(b.idleBetweenChunksMs > 0)
    }

    @Test
    fun neverChangesSteps() {
        // budget chỉ chứa thread/prefetch/idle — không có steps field
        val fields = ThermalBudget::class.java.declaredFields.map { it.name }
        assertFalse(fields.contains("steps"))
    }
}

class MetricsCollectorTest {

    @Test
    fun drainsAverageRtfAndResets() {
        val c = MetricsCollector()
        c.recordChunk(100, 1.0, 0.1)
        c.recordChunk(300, 3.0, 0.3)
        c.setThermalBucket("warm")
        c.recordCache(hit = true)
        c.recordCache(hit = false)
        val m = c.drain()
        assertEquals(2, m.nChunks)
        assertEquals(0.2, m.rtf!!, 1e-9)
        assertEquals("warm", m.thermalBucket)
        assertEquals(1, m.cacheHit)
        assertEquals(1, m.cacheMiss)
        val empty = c.drain()
        assertEquals(0, empty.nChunks)
        assertNull(empty.rtf)
    }

    @Test
    fun hardFailRecorded() {
        val c = MetricsCollector()
        c.recordError("E_PROVIDER_INIT", hard = true)
        val m = c.drain()
        assertTrue(m.hardFail)
        assertEquals("E_PROVIDER_INIT", m.errorCode)
        assertTrue(m.toJson().contains("hard_fail"))
    }
}
