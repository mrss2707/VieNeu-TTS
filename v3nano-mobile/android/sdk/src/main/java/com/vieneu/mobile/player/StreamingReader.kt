package com.vieneu.mobile.player

import com.vieneu.mobile.api.Metrics
import com.vieneu.mobile.core.GenerationProfile
import com.vieneu.mobile.core.TextNormalizer
import com.vieneu.mobile.core.TextSegmenter
import com.vieneu.mobile.lifecycle.MetricsCollector
import com.vieneu.mobile.pipeline.ChunkedSynthesisWorker
import com.vieneu.mobile.pipeline.SynthesisPipeline
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Điều phối streaming (04 §4.4): segment text → [GenerationQueue] → worker sinh chunk
 * (cancel-at-boundary) → consumer đẩy PCM vào [AudioPlayer]. Playback không bao giờ
 * chờ inference (01 §1.3).
 *
 * Thuần JVM — chỉ dùng `java.util.concurrent`, test được không cần device.
 */
class StreamingReader(
    private val pipeline: SynthesisPipeline,
    private val profile: GenerationProfile = GenerationProfile(),
    private val player: AudioPlayer,
    /** Lộ ra để test/đo; host không nên chạm trực tiếp. */
    val queue: GenerationQueue = GenerationQueue(),
    private val normalizer: TextNormalizer = TextNormalizer.mini,
    private val sampleRate: Int = 24_000,
) {
    /** Lộ cho test trong cùng module. */
    internal val worker: ChunkedSynthesisWorker

    /** Callback sự kiện — host nối vào MethodChannel/EventChannel. */
    var onProgress: ((chunkIndex: Int, nChunks: Int) -> Unit)? = null
    var onDone: (() -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    @Volatile private var speakerEmb: FloatArray? = null
    @Volatile private var style: FloatArray? = null
    @Volatile private var producerDone = false
    @Volatile private var producerFailed = false
    private val started = AtomicBoolean(false)
    private var producer: Thread? = null
    private var consumer: Thread? = null

    @Volatile private var enqueued = 0

    init {
        player.prepare(sampleRate, 1)
        worker = ChunkedSynthesisWorker(pipeline, queue, MetricsCollector()) { ready ->
            onProgress?.invoke(ready.job.index, enqueued)
        }
    }

    /**
     * Tách [text] theo [GenerationProfile.maxCharsPerChunk] rồi enqueue.
     * @return số chunk đã enqueue.
     */
    @Synchronized
    fun enqueue(text: String, speaker: FloatArray, speakerStyle: FloatArray, seed: Long = 0L): Int {
        val seg = TextSegmenter.segmentWithGaps(
            text,
            maxChars = profile.maxCharsPerChunk,
            normalizer = normalizer,
        )
        return enqueueSegments(seg.chunks, seg.gaps, speaker, speakerStyle, seed, isPhones = false)
    }

    /** Đường phones đã phonemize (G2P chưa có on-device) — không segment lại. */
    @Synchronized
    fun enqueuePhones(
        phoneChunks: List<String>,
        gaps: List<String>,
        speaker: FloatArray,
        speakerStyle: FloatArray,
        seed: Long = 0L,
    ): Int = enqueueSegments(phoneChunks, gaps, speaker, speakerStyle, seed, isPhones = true)

    private fun enqueueSegments(
        chunks: List<String>,
        gaps: List<String>,
        speaker: FloatArray,
        speakerStyle: FloatArray,
        seed: Long,
        isPhones: Boolean,
    ): Int {
        if (chunks.isEmpty()) return 0
        speakerEmb = speaker
        style = speakerStyle
        val jobs = chunks.indices.map { i ->
            GenerationQueue.ChunkJob(
                id = queue.nextId(),
                index = enqueued++,
                phoneChunk = chunks[i],
                gapAfter = gaps.getOrNull(i),
                seed = seed + i,
                isPhones = isPhones,
            )
        }
        queue.enqueueAll(jobs)
        return jobs.size
    }

    /** Bắt đầu sinh + phát. An toàn gọi lại (chỉ resume playback nếu đã chạy). */
    @Synchronized
    fun play() {
        if (!started.compareAndSet(false, true)) {
            player.play()
            return
        }
        producerDone = false
        producerFailed = false
        val spk = speakerEmb ?: FloatArray(0)
        val sty = style ?: FloatArray(0)

        producer = Thread({
            try {
                worker.runPending(spk, sty, sampleRate)
            } catch (_: InterruptedException) {
                // cancel() interrupt worker đang chờ publishReady — thoát êm.
            } catch (t: Throwable) {
                producerFailed = true
                onError?.invoke(t)
            } finally {
                producerDone = true
            }
        }, "vieneu-synth").apply { isDaemon = true; start() }

        consumer = Thread({
            player.play()
            try {
                while (true) {
                    if (queue.isCancelled) break
                    val ready = queue.takeReady()
                    if (ready != null) {
                        player.submit(ready.pcm)
                    } else if (producerDone && queue.readySize() == 0) {
                        break
                    } else {
                        try {
                            Thread.sleep(5)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
                // Lượt đọc tự kết thúc: cho phép enqueue + play() lượt kế khởi động worker mới. Trước đây
                // chỉ cancel() đặt lại `started` nên lượt thứ hai chỉ resume player và treo vĩnh viễn.
                // Chỉ đặt lại nếu thread này vẫn là consumer hiện tại (cancel()+play() có thể đã thay nó).
                val failed = producerFailed
                val cancelled = queue.isCancelled
                finishRun()
                if (!cancelled && !failed) onDone?.invoke()
            } catch (t: Throwable) {
                finishRun()
                onError?.invoke(t)
            }
        }, "vieneu-play").apply { isDaemon = true; start() }
    }

    @Synchronized
    private fun finishRun() {
        if (Thread.currentThread() === consumer) {
            started.set(false)
            enqueued = 0
        }
    }

    fun pause() {
        player.pause()
    }

    /** Hủy: worker dừng ở biên chunk, flush buffer, sẵn sàng nhận job mới. */
    @Synchronized
    fun cancel() {
        queue.cancelAll()
        player.stop()
        producer?.interrupt() // đánh thức worker nếu đang block ở publishReady (put)
        producer = null
        queue.reset()
        producerDone = false
        producerFailed = false
        started.set(false)
        enqueued = 0
    }

    fun setPrefetchDepth(depth: Int) {
        require(depth >= 1) { "prefetchDepth >= 1" }
        queue.setPrefetchDepth(depth)
    }

    fun setOnUnderrunListener(listener: (() -> Unit)?) {
        player.setOnUnderrunListener(listener)
    }

    fun bufferedChunks(): Int = player.bufferedChunks()

    /** Drain metrics của lượt hiện tại (reset accumulator sau khi đọc). */
    fun metrics(): Metrics = worker.metricsCollector().drain()

    /** Dừng sinh/phát. Vòng đời tài nguyên của [player] do host quản lý. */
    fun release() {
        cancel()
    }
}
