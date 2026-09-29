package com.vieneu.mobile.pipeline

import com.vieneu.mobile.core.GenerationProfile
import com.vieneu.mobile.core.PhoneEncoder
import com.vieneu.mobile.core.TextNormalizer
import com.vieneu.mobile.engine.SynthesisEngine
import com.vieneu.mobile.lifecycle.ThermalPolicy
import com.vieneu.mobile.player.GenerationQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkedSynthesisWorkerTest {

    private class FakeEngine : SynthesisEngine {
        override fun load(modelDir: String, threads: Int) {}
        override val isLoaded: Boolean = true
        override fun synthesizeChunk(
            phoneIds: LongArray,
            speakerEmb: FloatArray,
            style: FloatArray,
            seed: Long,
        ): FloatArray = FloatArray(2400)

        override fun close() {}
    }

    private fun encoder() = PhoneEncoder(
        emotionMap = emptyMap(),
        vocab = mapOf("a" to 1, "b" to 2, "." to 4),
        bos = 1, eos = 2, pad = 0,
    )

    @Test
    fun producesUntilQueueEmpty() {
        val q = GenerationQueue(prefetchDepth = 4)
        val pipe = SynthesisPipeline(FakeEngine(), encoder(), normalizer = TextNormalizer.identity)
        val worker = ChunkedSynthesisWorker(pipe, q)
        q.enqueueAll(
            listOf(
                GenerationQueue.ChunkJob(1, 0, "a.", "sentence", 1),
                GenerationQueue.ChunkJob(2, 1, "b.", "sentence", 2),
            ),
        )
        val n = worker.runPending(FloatArray(192), FloatArray(12800))
        assertEquals(2, n)
        assertEquals(2, q.readySize())
        assertEquals(2, worker.metricsCollector().drain().nChunks)
    }

    @Test
    fun cancelStopsAtBoundary() {
        val q = GenerationQueue()
        val pipe = SynthesisPipeline(FakeEngine(), encoder(), normalizer = TextNormalizer.identity)
        var readyCount = 0
        val worker = ChunkedSynthesisWorker(pipe, q) { readyCount++ }
        q.enqueueAll(
            (0 until 5).map {
                GenerationQueue.ChunkJob(it.toLong(), it, "a.", "minor", it.toLong())
            },
        )
        q.cancelAll()
        val n = worker.runPending(FloatArray(1), FloatArray(1))
        assertEquals(0, n)
        assertEquals(0, readyCount)
    }

    @Test
    fun thermalHotBudgetIsSingleThread() {
        val q = GenerationQueue()
        val pipe = SynthesisPipeline(FakeEngine(), encoder())
        val worker = ChunkedSynthesisWorker(pipe, q)
        worker.onThermalStatus(ThermalPolicy.STATUS_SEVERE)
        assertEquals("hot", worker.budget().tierName)
        assertEquals(1, worker.budget().threads)
        assertTrue(worker.metricsCollector().drain().thermalBucket == "hot")
    }
}
