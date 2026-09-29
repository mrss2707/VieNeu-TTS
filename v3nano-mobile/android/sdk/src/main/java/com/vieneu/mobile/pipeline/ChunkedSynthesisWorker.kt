package com.vieneu.mobile.pipeline

import com.vieneu.mobile.api.EngineError
import com.vieneu.mobile.core.GenerationProfile
import com.vieneu.mobile.lifecycle.MetricsCollector
import com.vieneu.mobile.lifecycle.ThermalBudget
import com.vieneu.mobile.lifecycle.ThermalPolicy
import com.vieneu.mobile.player.GenerationQueue

/**
 * Worker đơn (01 §1.5) — sinh chunk theo queue, cancel-at-boundary.
 * Playback thread không bao giờ chờ inference (01 §1.3).
 */
class ChunkedSynthesisWorker(
    private val pipeline: SynthesisPipeline,
    private val queue: GenerationQueue,
    private val metrics: MetricsCollector = MetricsCollector(),
    private val onChunkReady: (GenerationQueue.ReadyChunk) -> Unit = {},
) {
    @Volatile
    private var thermalStatus: Int = ThermalPolicy.STATUS_NONE

    fun onThermalStatus(androidStatus: Int) {
        thermalStatus = androidStatus
        metrics.setThermalBucket(ThermalPolicy.bucketFor(androidStatus))
    }

    fun budget(): ThermalBudget = ThermalPolicy.budgetFor(thermalStatus)

    /**
     * Sinh lần lượt các job pending tới khi hết hoặc [GenerationQueue.isCancelled].
     * Cancel = dừng ở biên chunk (sau khi xong chunk hiện tại).
     */
    fun runPending(
        speakerEmb: FloatArray,
        style: FloatArray,
        sampleRate: Int = 24_000,
    ): Int {
        val b = budget()
        var produced = 0
        while (!queue.isCancelled) {
            val job = queue.takePending() ?: break
            try {
                val t0 = System.nanoTime()
                val pcm = pipeline.synthesizePhones(
                    phoneChunks = listOf(job.phoneChunk),
                    gaps = listOfNotNull(job.gapAfter),
                    speakerEmb = speakerEmb,
                    style = style,
                    seed = job.seed,
                    sr = sampleRate,
                ).pcm
                val genMs = (System.nanoTime() - t0) / 1_000_000
                val audioSec = pcm.size.toDouble() / sampleRate
                val rtf = if (audioSec > 0) genMs / 1000.0 / audioSec else null
                metrics.recordChunk(genMs, audioSec, rtf)
                if (b.idleBetweenChunksMs > 0) {
                    try {
                        Thread.sleep(b.idleBetweenChunksMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
                if (queue.isCancelled) break
                val ready = GenerationQueue.ReadyChunk(job, pcm, genMs)
                queue.publishReady(ready)
                onChunkReady(ready)
                produced++
            } catch (e: EngineError.Cancelled) {
                break
            } catch (e: EngineError) {
                metrics.recordError(e.code, hard = true)
                break
            }
        }
        return produced
    }

    fun metricsCollector(): MetricsCollector = metrics
}
