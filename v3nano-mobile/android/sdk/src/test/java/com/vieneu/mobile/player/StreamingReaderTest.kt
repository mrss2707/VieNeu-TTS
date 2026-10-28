package com.vieneu.mobile.player

import com.vieneu.mobile.core.GenerationProfile
import com.vieneu.mobile.core.PhoneEncoder
import com.vieneu.mobile.core.TextNormalizer
import com.vieneu.mobile.engine.SynthesisEngine
import com.vieneu.mobile.pipeline.SynthesisPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * StreamingReader wiring test — fake engine + fake player, KHÔNG cần ORT/device.
 * Chỉ test điều phối (queue/cancel/prefetch/metrics), không test AndroidAudioPlayer.
 */
class StreamingReaderTest {

    private class FakeEngine : SynthesisEngine {
        var loaded = false
        override fun load(modelDir: String, threads: Int) { loaded = true }
        override val isLoaded: Boolean get() = loaded
        override fun synthesizeChunk(
            phoneIds: LongArray,
            speakerEmb: FloatArray,
            style: FloatArray,
            seed: Long,
        ): FloatArray = FloatArray(2400) { 0.1f } // 0.1s @ 24k
        override fun close() { loaded = false }
    }

    private class FakePlayer : AudioPlayer {
        override var state: AudioPlayer.State = AudioPlayer.State.IDLE
        var prepared = false
        var submitted = 0
        val submittedPcm = ArrayList<FloatArray>()
        override fun prepare(sampleRate: Int, channels: Int) { prepared = true }
        override fun submit(pcm: FloatArray) { submitted++; submittedPcm.add(pcm) }
        override fun play() { state = AudioPlayer.State.PLAYING }
        override fun pause() { state = AudioPlayer.State.PAUSED }
        override fun stop() { state = AudioPlayer.State.STOPPED }
        override fun bufferedChunks(): Int = submitted
        override fun setOnUnderrunListener(listener: (() -> Unit)?) {}
    }

    private fun encoder() = PhoneEncoder(
        emotionMap = mapOf("<|emotion_1|>" to "①"),
        vocab = (0..20).associate { it.toString() to it } + mapOf(
            "a" to 1, "b" to 2, " " to 3, "." to 4, "①" to 5,
        ),
        bos = 1, eos = 2, pad = 0,
    )

    private val text = "Câu một dài hơi một chút. Câu hai cũng tương tự vậy đó!"

    private fun reader(
        player: FakePlayer = FakePlayer(),
        maxChars: Int = 20,
        phonemizer: ((String) -> String)? = { it },
    ): StreamingReader {
        val engine = FakeEngine().also { it.load("/tmp") }
        val profile = GenerationProfile(maxCharsPerChunk = maxChars)
        val pipe = SynthesisPipeline(engine, encoder(), profile, TextNormalizer.identity, phonemizer = phonemizer)
        return StreamingReader(
            pipeline = pipe,
            profile = profile,
            player = player,
            queue = GenerationQueue(64), // đủ lớn để runPending không block khi test không drain
            normalizer = TextNormalizer.identity,
        )
    }

    @Test
    fun enqueueSplitsTextIntoJobs() {
        val r = reader()
        val n = r.enqueue(text, FloatArray(192), FloatArray(12800), seed = 0L)
        assertTrue("phải tách ≥2 chunk", n >= 2)
        assertEquals(n, r.queue.pendingSize())
    }

    @Test
    fun cancelStopsAtBoundarySoWorkerProducesNothing() {
        val r = reader()
        r.enqueue(text, FloatArray(2), FloatArray(2), seed = 0L)
        r.cancel()
        // Sau cancelAll + reset: queue rỗng → worker không sinh chunk nào.
        assertEquals(0, r.worker.runPending(FloatArray(2), FloatArray(2)))
    }

    @Test
    fun setPrefetchDepthPropagatesToQueue() {
        val r = reader()
        r.setPrefetchDepth(5)
        assertEquals(5, r.queue.prefetchDepth)
    }

    @Test
    fun metricsDrainReportsChunksOnce() {
        val r = reader()
        val n = r.enqueue(text, FloatArray(192), FloatArray(12800), seed = 0L)
        val produced = r.worker.runPending(FloatArray(192), FloatArray(12800))
        assertEquals(n, produced)

        val first = r.metrics()
        assertEquals(n, first.nChunks)
        assertTrue(first.audioSeconds != null)

        val second = r.metrics() // accumulator đã reset sau lần drain đầu
        assertEquals(0, second.nChunks)
    }

    @Test
    fun missingPhonemizerFailsLoudlyInsteadOfReadingRawText() {
        val player = FakePlayer()
        val r = reader(player, phonemizer = null)
        r.enqueue(text, FloatArray(2), FloatArray(2), seed = 0L)
        val errors = ArrayList<Throwable>()
        val ended = CountDownLatch(1)
        r.onError = { synchronized(errors) { errors.add(it) }; ended.countDown() }
        var done = false
        r.onDone = { done = true; ended.countDown() }

        r.play()
        assertTrue("phải có kết thúc trong 5s", ended.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals(1, errors.size)
        assertTrue(errors[0] is com.vieneu.mobile.api.EngineError.InvalidInput)
        assertTrue("không được báo xong khi chưa đọc chữ nào", !done)
        assertEquals(0, player.submitted)
    }

    @Test
    fun enqueueRunsPhonemizerPerChunkOnWorkerNotCaller() {
        val callers = java.util.Collections.synchronizedList(ArrayList<String>())
        val r = reader(phonemizer = { callers.add(Thread.currentThread().name); it })
        r.enqueue(text, FloatArray(2), FloatArray(2), seed = 0L)
        assertTrue("enqueue không được phonemize trên thread gọi", callers.isEmpty())
        val n = r.queue.pendingSize()
        r.worker.runPending(FloatArray(2), FloatArray(2))
        assertEquals(n, callers.size)
    }

    /** Ghi lại vector giọng mà engine nhận cho TỪNG chunk (nhận diện bằng giá trị phần tử đầu). */
    private class VoiceRecordingEngine(val delayMs: Long = 0) : SynthesisEngine {
        val seen = java.util.Collections.synchronizedList(ArrayList<Float>())
        override fun load(modelDir: String, threads: Int) {}
        override val isLoaded: Boolean = true
        override fun synthesizeChunk(phoneIds: LongArray, speakerEmb: FloatArray, style: FloatArray, seed: Long): FloatArray {
            seen.add(speakerEmb[0])
            // ORT native KHÔNG dừng khi thread bị interrupt: chờ tới hết thời gian, bỏ qua ngắt.
            val end = System.nanoTime() + delayMs * 1_000_000
            while (System.nanoTime() < end) Thread.onSpinWait()
            return FloatArray(2400) { 0.1f }
        }
        override fun close() {}
    }

    private fun voiceReader(engine: VoiceRecordingEngine, player: FakePlayer): StreamingReader {
        val profile = GenerationProfile(maxCharsPerChunk = 20)
        val pipe = SynthesisPipeline(engine, encoder(), profile, TextNormalizer.identity, phonemizer = { it })
        return StreamingReader(pipe, profile, player, GenerationQueue(64), TextNormalizer.identity)
    }

    @Test
    fun everyChunkOfALongTextUsesTheSelectedVoice() {
        val engine = VoiceRecordingEngine()
        val player = FakePlayer()
        val r = voiceReader(engine, player)
        r.enqueue(text.repeat(4), FloatArray(192) { 7f }, FloatArray(12800), seed = 0L)
        val done = CountDownLatch(1)
        r.onDone = { done.countDown() }
        r.play()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue("phải có nhiều chunk, có ${engine.seen.size}", engine.seen.size >= 4)
        assertTrue("mọi chunk phải dùng giọng 7: ${engine.seen}", engine.seen.all { it == 7f })
    }

    @Test
    fun switchingVoiceMidReadNeverLeaksOldVoiceIntoTheNewRead() {
        // Mô phỏng demo: đọc bằng giọng A, bấm Phát lại (cancel → enqueue giọng B → play) khi A còn đang sinh.
        repeat(20) { attempt ->
            val engine = VoiceRecordingEngine(delayMs = 15)
            val player = FakePlayer()
            val r = voiceReader(engine, player)
            r.enqueue(text.repeat(6), FloatArray(192) { 1f }, FloatArray(12800), seed = 0L)
            r.play()
            Thread.sleep(40) // để worker A đang sinh dở
            r.cancel()
            engine.seen.clear()
            r.enqueue(text.repeat(2), FloatArray(192) { 2f }, FloatArray(12800), seed = 0L)
            val done = CountDownLatch(1)
            r.onDone = { done.countDown() }
            r.play()
            assertTrue("attempt $attempt: lượt B phải xong", done.await(10, TimeUnit.SECONDS))
            Thread.sleep(60) // worker A (nếu còn sống) có thời gian ghi nhầm
            val leaked = synchronized(engine.seen) { engine.seen.filter { it != 2f } }
            assertTrue("attempt $attempt: chunk giọng cũ lọt vào lượt mới: $leaked", leaked.isEmpty())
        }
    }

    @Test
    fun secondReadAfterNaturalCompletionRunsAndFiresDoneAgain() {
        // Đọc xong tự nhiên (không cancel) rồi enqueue + play tiếp: trước đây `started` không được
        // đặt lại nên play() chỉ resume player, không khởi động worker → OnDone không bao giờ bắn.
        val engine = VoiceRecordingEngine()
        val player = FakePlayer()
        val r = voiceReader(engine, player)

        r.enqueue(text, FloatArray(192) { 1f }, FloatArray(12800), seed = 0L)
        val done1 = CountDownLatch(1)
        r.onDone = { done1.countDown() }
        r.play()
        assertTrue("lượt 1 phải xong", done1.await(5, TimeUnit.SECONDS))

        engine.seen.clear()
        r.enqueue(text, FloatArray(192) { 2f }, FloatArray(12800), seed = 0L)
        val done2 = CountDownLatch(1)
        r.onDone = { done2.countDown() }
        r.play()
        assertTrue("lượt 2 phải xong (không treo)", done2.await(5, TimeUnit.SECONDS))
        assertTrue("lượt 2 phải dùng giọng 2: ${engine.seen}", engine.seen.isNotEmpty() && engine.seen.all { it == 2f })
    }

    @Test
    fun playStreamsChunksAndFiresDone() {
        val player = FakePlayer()
        val r = reader(player)
        r.enqueue(text, FloatArray(2), FloatArray(2), seed = 0L)
        val done = CountDownLatch(1)
        val progress = ArrayList<Int>()
        r.onDone = { done.countDown() }
        r.onProgress = { idx, _ -> synchronized(progress) { progress.add(idx) } }

        r.play()
        assertTrue("onDone phải bắn trong 5s", done.await(5, TimeUnit.SECONDS))
        assertTrue("player phải nhận ≥1 chunk", player.submitted >= 1)
        assertTrue("onProgress phải báo ≥1 chunk", progress.isNotEmpty())
    }
}
