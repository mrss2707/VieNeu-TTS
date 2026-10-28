package com.vieneu.mobile.player

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded queue chunk + priority gần + cancel-at-boundary (04 §4.4 / 01 §1.4).
 *
 * - Một producer (segment) + một synthesis worker (MVP).
 * - [cancel] = báo cờ; worker kết thúc ở **biên chunk hiện tại**, không kill giữa tensor.
 * - [prefetchDepth] = bound của ready-buffer (chunk đã sinh xong chờ phát).
 */
class GenerationQueue(
    prefetchDepth: Int = 3,
) {
    data class ChunkJob(
        val id: Long,
        val index: Int,
        val phoneChunk: String,
        val gapAfter: String?,
        val seed: Long,
        val priority: Int = 0, // 0 = near (mặc định), lớn hơn = xa hơn
        /** true: [phoneChunk] đã là phoneme; false: là text chunk, worker phải phonemize. */
        val isPhones: Boolean = true,
    )

    data class ReadyChunk(
        val job: ChunkJob,
        val pcm: FloatArray,
        val generationMs: Long,
    )

    private val bound = requireNotNull(
        if (prefetchDepth >= 1) prefetchDepth else null,
    ) { "prefetchDepth >= 1" }

    private val pending = ConcurrentLinkedQueue<ChunkJob>()
    private val ready = ArrayBlockingQueue<ReadyChunk>(bound)
    private val cancelled = AtomicBoolean(false)
    private val idGen = AtomicLong(0)
    @Volatile
    var prefetchDepth: Int = bound
        private set

    fun setPrefetchDepth(depth: Int) {
        require(depth >= 1)
        prefetchDepth = depth
    }

    fun enqueue(job: ChunkJob) {
        if (cancelled.get()) return
        pending.add(job)
    }

    fun enqueueAll(jobs: List<ChunkJob>) = jobs.forEach { enqueue(it) }

    /** Worker lấy job kế (FIFO theo index — priority gần = index nhỏ). */
    fun takePending(): ChunkJob? {
        if (cancelled.get()) return null
        return pending.poll()
    }

    fun publishReady(chunk: ReadyChunk) {
        if (cancelled.get()) return
        // block khi đầy — producer sinh nhanh hơn player thì chờ (bounded prefetch)
        ready.put(chunk)
    }

    fun takeReady(): ReadyChunk? {
        if (cancelled.get()) {
            ready.clear()
            return null
        }
        return ready.poll()
    }

    /** Hủy toàn bộ: worker thấy cờ và dừng ở biên; ready buffer flush. */
    fun cancelAll() {
        cancelled.set(true)
        pending.clear()
        ready.clear()
    }

    /** Cho phép job mới sau [cancelAll]. */
    fun reset() {
        cancelled.set(false)
        pending.clear()
        ready.clear()
    }

    val isCancelled: Boolean get() = cancelled.get()

    fun pendingSize(): Int = pending.size

    fun readySize(): Int = ready.size

    fun nextId(): Long = idGen.incrementAndGet()
}
