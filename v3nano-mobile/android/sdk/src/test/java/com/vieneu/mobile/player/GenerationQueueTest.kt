package com.vieneu.mobile.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationQueueTest {

    private fun job(i: Int) = GenerationQueue.ChunkJob(
        id = i.toLong(), index = i, phoneChunk = "p$i", gapAfter = "sentence", seed = i.toLong(),
    )

    @Test
    fun fifoPendingAndBoundedReady() {
        val q = GenerationQueue(prefetchDepth = 2)
        q.enqueueAll(listOf(job(0), job(1), job(2)))
        assertEquals(3, q.pendingSize())

        assertEquals(0, q.takePending()!!.index)
        q.publishReady(GenerationQueue.ReadyChunk(job(0), FloatArray(10), 5))
        q.publishReady(GenerationQueue.ReadyChunk(job(1), FloatArray(10), 5))
        assertEquals(2, q.readySize())
        assertEquals(0, q.takeReady()!!.job.index)
    }

    @Test
    fun cancelAllClearsAndBlocks() {
        val q = GenerationQueue()
        q.enqueue(job(0))
        q.cancelAll()
        assertTrue(q.isCancelled)
        assertNull(q.takePending())
        assertEquals(0, q.pendingSize())

        q.reset()
        assertFalse(q.isCancelled)
        q.enqueue(job(1))
        assertEquals(1, q.pendingSize())
    }

    @Test
    fun setPrefetchDepthRequiresPositive() {
        val q = GenerationQueue()
        q.setPrefetchDepth(5)
        assertEquals(5, q.prefetchDepth)
    }
}
