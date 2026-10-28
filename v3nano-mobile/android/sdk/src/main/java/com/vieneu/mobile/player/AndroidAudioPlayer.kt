package com.vieneu.mobile.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * [AudioPlayer] thật trên Android — AudioTrack `MODE_STREAM`, 24 kHz mono PCM16
 * (04-sdk-android.md §4.4).
 *
 * - Float PCM32 → Int16 tại biên phát (`coerceIn(-1f,1f) * 32767`).
 * - Buffer ≥ 2 chunk để hạn chế underrun khi inference chậm hơn phát.
 * - Underrun báo qua [setOnUnderrunListener] (đọc `getUnderrunCount`).
 * - Audio focus xin trong [play], nhả trong [pause]/[stop] (minSdk 26).
 *
 * File này dùng `android.media.*` nên CHỈ nằm ở main source set của SDK — không đụng
 * gói `core` (giữ core thuần JVM).
 */
class AndroidAudioPlayer(
    context: Context,
    private val tag: String = "VieNeuAudio",
) : AudioPlayer {

    private val appContext = context.applicationContext
    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sm = PlayerStateMachine()

    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { /* host điều khiển pause */ }

    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()
    }

    private var track: AudioTrack? = null
    private var sampleRate = 24_000

    /** Điểm kết thúc (frame) của từng chunk đã ghi — dùng để tính [bufferedChunks]. */
    private val chunkEndFrames = ConcurrentLinkedQueue<Int>()
    private val buffered = AtomicInteger(0)
    private val writtenFrames = AtomicInteger(0)
    private val running = AtomicBoolean(false)
    private var monitor: Thread? = null
    private var underrunListener: (() -> Unit)? = null

    override val state: AudioPlayer.State get() = sm.state

    override fun prepare(sampleRate: Int, channels: Int) {
        require(channels == 1) { "chỉ hỗ trợ mono" }
        this.sampleRate = sampleRate
        releaseTrackOnly()
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).takeIf { it > 0 } ?: (sampleRate * 2)
        // ≥ 2 giây audio (an toàn ≥ 2 chunk dài ~1s) để chống underrun khi sinh chậm.
        val bufferBytes = maxOf(minBuf * 2, sampleRate * 2 * 2)
        track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        writtenFrames.set(0)
        chunkEndFrames.clear()
        buffered.set(0)
        sm.onLoading()
    }

    override fun submit(pcm: FloatArray) {
        val t = track ?: return
        if (pcm.isEmpty()) return
        val bytes = ByteArray(pcm.size * 2)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (v in pcm) {
            val s = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bb.putShort(s)
        }
        // write() chặn khi buffer đầy — backpressure cho producer (không drop audio).
        val written = try {
            t.write(bytes, 0, bytes.size)
        } catch (e: IllegalStateException) {
            Log.w(tag, "write() khi track không hợp lệ: ${e.message}")
            return
        }
        if (written <= 0) return
        writtenFrames.addAndGet(written / 2)
        chunkEndFrames.add(writtenFrames.get())
        buffered.incrementAndGet()
        ensureMonitor()
    }

    override fun play() {
        val t = track ?: return
        requestFocus()
        t.play()
        sm.onPlay()
        ensureMonitor()
    }

    override fun pause() {
        val t = track ?: return
        t.pause()
        sm.onPause()
        abandonFocus()
    }

    override fun stop() {
        val t = track ?: return
        try {
            t.pause()
            t.flush()
        } catch (e: IllegalStateException) {
            Log.w(tag, "stop() khi track không hợp lệ: ${e.message}")
        }
        writtenFrames.set(0)
        chunkEndFrames.clear()
        buffered.set(0)
        sm.onStop()
        abandonFocus()
    }

    override fun bufferedChunks(): Int = buffered.get()

    override fun setOnUnderrunListener(listener: (() -> Unit)?) {
        underrunListener = listener
    }

    /** Giải phóng tài nguyên — gọi khi reader/host hủy vĩnh viễn. */
    fun release() {
        releaseTrackOnly()
        abandonFocus()
    }

    private fun releaseTrackOnly() {
        running.set(false)
        monitor?.interrupt()
        monitor = null
        track?.let {
            try {
                it.pause()
                it.flush()
            } catch (_: IllegalStateException) {
                // track đã ở trạng thái không hợp lệ — vẫn release bên dưới.
            }
            it.release()
        }
        track = null
    }

    /**
     * Monitor nhẹ: cập nhật [bufferedChunks] theo `playbackHeadPosition` và báo underrun
     * khi `getUnderrunCount` tăng. Chỉ chạy khi có dữ liệu đã ghi.
     */
    private fun ensureMonitor() {
        if (running.getAndSet(true)) return
        monitor = Thread({
            var lastUnderrun = track?.underrunCount ?: 0
            while (running.get()) {
                val t = track ?: break
                val head = t.playbackHeadPosition
                while (true) {
                    val end = chunkEndFrames.peek() ?: break
                    if (head >= end) {
                        chunkEndFrames.poll()
                        buffered.updateAndGet { n -> if (n > 0) n - 1 else 0 }
                    } else {
                        break
                    }
                }
                val uc = t.underrunCount
                if (uc > lastUnderrun) {
                    lastUnderrun = uc
                    underrunListener?.invoke()
                }
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "vieneu-audio-monitor").apply {
            isDaemon = true
            start()
        }
    }

    private fun requestFocus() {
        audioManager.requestAudioFocus(focusRequest)
    }

    private fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }
}
