package com.vieneu.mobile.api

import com.vieneu.mobile.pipeline.SynthesisPipeline

/**
 * Reader enqueue / play / pause / cancel (04 §4.2).
 *
 * MVP: sinh **một lần** (offline) rồi trả PCM — streaming AudioTrack + prefetch
 * queue thuộc Sprint 4 (`GenerationQueue`). API giữ đúng tên để integrator
 * không phải đổi khi upgrade.
 */
class Reader internal constructor(
    private val pipeline: SynthesisPipeline,
) {
    @Volatile
    private var cancelled = false

    private var last: SynthesisPipeline.Result? = null

    /** Sinh toàn bộ [text] (tự chunk). Trả metrics; PCM giữ trong reader. */
    @Throws(EngineError::class)
    fun enqueueSynthesize(
        text: String,
        speakerEmb: FloatArray,
        style: FloatArray,
        seed: Long = 0L,
        onEvent: ((String) -> Unit)? = null,
    ): SynthesisPipeline.Result {
        if (cancelled) throw EngineError.Cancelled()
        val result = pipeline.synthesizeText(text, speakerEmb, style, seed, onEvent = onEvent)
        last = result
        return result
    }

    @Throws(EngineError::class)
    fun enqueuePhones(
        phoneChunks: List<String>,
        gaps: List<String>,
        speakerEmb: FloatArray,
        style: FloatArray,
        seed: Long = 0L,
        onEvent: ((String) -> Unit)? = null,
    ): SynthesisPipeline.Result {
        if (cancelled) throw EngineError.Cancelled()
        val result = pipeline.synthesizePhones(phoneChunks, gaps, speakerEmb, style, seed, onEvent = onEvent)
        last = result
        return result
    }

    fun pcm(): FloatArray = last?.pcm ?: FloatArray(0)

    fun metrics(): Metrics = last?.metrics ?: Metrics()

    /** Hủy job — Sprint 4 sẽ cancel-at-boundary; hiện tại chặn request kế tiếp. */
    fun cancel() {
        cancelled = true
        last = null
    }

    fun pause() { /* Sprint 4: giữ queue */ }

    fun play() { /* Sprint 4: AudioTrack streaming */ }

    fun setPrefetchDepth(depth: Int) {
        require(depth >= 1) { "prefetchDepth >= 1" }
        // Sprint 4: GenerationQueue
    }
}
