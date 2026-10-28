package com.vieneu.mobile.api

import com.vieneu.mobile.pipeline.SynthesisPipeline
import com.vieneu.mobile.player.StreamingReader

/**
 * Reader enqueue / play / pause / cancel (04 §4.2).
 *
 * Hai chế độ:
 * - **Streaming** (host build với `audioPlayer(...)`): [enqueue]/[play]/[pause]/[cancel]/
 *   [setPrefetchDepth] delegate sang [StreamingReader] — PCM phát trực tiếp qua AudioTrack.
 * - **Offline** (không có streaming): [enqueueSynthesize]/[enqueuePhones] sinh một lần rồi
 *   giữ PCM trong [pcm] — giữ để tương thích ngược.
 */
class Reader internal constructor(
    private val pipeline: SynthesisPipeline,
    private val streaming: StreamingReader? = null,
) {
    @Volatile
    private var cancelled = false

    private var last: SynthesisPipeline.Result? = null

    /** Chế độ streaming đang bật? */
    val isStreaming: Boolean get() = streaming != null

    /** Sinh toàn bộ [text] (tự chunk). Trả metrics; PCM giữ trong reader. */
    @Throws(EngineError::class)
    fun enqueueSynthesize(
        text: String,
        speakerEmb: FloatArray,
        style: FloatArray,
        seed: Long = 0L,
        onEvent: ((String) -> Unit)? = null,
    ): SynthesisPipeline.Result {
        if (cancelled) throw EngineError.Canceled()
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
        if (cancelled) throw EngineError.Canceled()
        val result = pipeline.synthesizePhones(phoneChunks, gaps, speakerEmb, style, seed, onEvent = onEvent)
        last = result
        return result
    }

    /**
     * Streaming: tách [text] rồi xếp vào queue; gọi [play] để sinh + phát.
     * @return số chunk đã enqueue.
     */
    fun enqueue(
        text: String,
        speakerEmb: FloatArray,
        style: FloatArray,
        seed: Long = 0L,
    ): Int {
        val s = requireStreaming()
        cancelled = false
        return s.enqueue(text, speakerEmb, style, seed)
    }

    /** Streaming: enqueue phones đã phonemize giữa các chunk (khác bản offline ở trên). */
    fun enqueuePhonesStreaming(
        phoneChunks: List<String>,
        gaps: List<String>,
        speakerEmb: FloatArray,
        style: FloatArray,
        seed: Long = 0L,
    ): Int {
        val s = requireStreaming()
        cancelled = false
        return s.enqueuePhones(phoneChunks, gaps, speakerEmb, style, seed)
    }

    fun pcm(): FloatArray = last?.pcm ?: FloatArray(0)

    /** Metrics: ưu tiên drain của streaming; nếu offline trả metrics của kết quả cuối. */
    fun metrics(): Metrics = streaming?.metrics() ?: (last?.metrics ?: Metrics())

    fun onProgress(listener: (chunkIndex: Int, nChunks: Int) -> Unit) {
        requireStreaming().onProgress = listener
    }

    fun onDone(listener: () -> Unit) {
        requireStreaming().onDone = listener
    }

    fun onError(listener: (Throwable) -> Unit) {
        requireStreaming().onError = listener
    }

    fun setOnUnderrunListener(listener: (() -> Unit)?) {
        streaming?.setOnUnderrunListener(listener)
    }

    /** Hủy job — cancel-at-boundary ở streaming, chặn request kế tiếp ở offline. */
    fun cancel() {
        cancelled = true
        last = null
        streaming?.cancel()
    }

    fun pause() {
        streaming?.pause()
    }

    fun play() {
        streaming?.play()
    }

    fun setPrefetchDepth(depth: Int) {
        require(depth >= 1) { "prefetchDepth >= 1" }
        streaming?.setPrefetchDepth(depth)
    }

    private fun requireStreaming(): StreamingReader =
        streaming ?: throw EngineError.Internal("Reader này không bật streaming — build với audioPlayer(...)")
}
