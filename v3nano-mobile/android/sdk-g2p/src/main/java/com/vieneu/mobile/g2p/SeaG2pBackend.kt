package com.vieneu.mobile.g2p

import com.vieneu.mobile.core.G2pBackend
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [G2pBackend] trên lõi Rust sea-g2p.
 *
 * Handle native không thread-safe, mà SDK gọi normalize (thread của `enqueue`) và
 * phonemize (worker `vieneu-synth`) đồng thời — nên giữ một POOL handle: mỗi lời gọi mượn
 * một handle, trả lại sau khi xong. Các handle dùng chung một file từ điển mmap (page cache
 * của OS) nên thêm handle gần như không tốn RAM.
 *
 * Gọi sau [close] ném [IllegalStateException].
 */
class SeaG2pBackend(private val dictPath: String) : G2pBackend {
    private val idle = ConcurrentLinkedQueue<Long>()
    private val all = ConcurrentLinkedQueue<Long>()
    private val closed = AtomicBoolean(false)

    init {
        SeaG2pNative.ensureLoaded()
        // Mở thử một handle ngay để lỗi từ điển hỏng/thiếu lộ ra lúc khởi tạo, không phải lúc đọc.
        idle.add(openHandle())
    }

    private fun openHandle(): Long {
        val h = SeaG2pNative.nativeOpen(dictPath)
        all.add(h)
        return h
    }

    private inline fun <T> withHandle(block: (Long) -> T): T {
        check(!closed.get()) { "SeaG2pBackend đã đóng" }
        val h = idle.poll() ?: openHandle()
        try {
            return block(h)
        } finally {
            if (closed.get()) Unit else idle.add(h)
        }
    }

    override fun normalize(text: String, puncNorm: Boolean): String =
        withHandle { SeaG2pNative.nativeNormalize(it, text, if (puncNorm) 1 else 0) }

    override fun phonemize(text: String, puncNorm: Boolean): String =
        withHandle { SeaG2pNative.nativePhonemize(it, text, if (puncNorm) 1 else 0) }

    override fun puncNorm(text: String): String = SeaG2pNative.nativePuncNorm(text)

    /** Đóng mọi handle. Gọi khi không còn lời gọi nào đang chạy (sau khi dừng reader). */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        idle.clear()
        while (true) SeaG2pNative.nativeClose(all.poll() ?: break)
    }
}
