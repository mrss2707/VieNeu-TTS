package com.vieneu.mobile.player

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [AudioPlayer] bọc ngoài: chuyển mọi lời gọi sang [delegate] và GHI lại đúng PCM float32 mà
 * `submit` nhận (tức audio SAU model + DSP — thứ người nghe nghe thật).
 *
 * Dùng để chẩn đoán "chỗ này nghe chói" bằng chính audio thiết bị phát (INT8 + DSP) thay vì suy luận
 * từ bản Python FP32. Mỗi lần submit ghi thêm `boundaries` (số mẫu) để tách lại từng chunk.
 *
 * Ghi ra [pcmFile] (float32 little-endian mono) và [indexFile] (mỗi dòng: chỉ số chunk, số mẫu).
 * Chỉ bật khi host chủ động bọc; mặc định SDK không ghi gì.
 */
class RecordingAudioPlayer(
    private val delegate: AudioPlayer,
    private val pcmFile: File,
    private val indexFile: File,
) : AudioPlayer by delegate {
    private val lock = Any()
    private var pcmOut: BufferedOutputStream? = null
    private var indexOut: BufferedOutputStream? = null
    private var chunk = 0

    init {
        pcmFile.parentFile?.mkdirs()
        reset()
    }

    /** Bắt đầu bản ghi mới (xoá file cũ). */
    fun reset() = synchronized(lock) {
        closeStreams()
        pcmOut = BufferedOutputStream(FileOutputStream(pcmFile, false), 1 shl 16)
        indexOut = BufferedOutputStream(FileOutputStream(indexFile, false))
        chunk = 0
    }

    override fun submit(pcm: FloatArray) {
        synchronized(lock) {
            val out = pcmOut
            if (out != null && pcm.isNotEmpty()) {
                val bb = ByteBuffer.allocate(pcm.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (v in pcm) bb.putFloat(v)
                out.write(bb.array())
                indexOut?.write("$chunk,${pcm.size}\n".toByteArray())
                out.flush()
                indexOut?.flush()
                chunk++
            }
        }
        delegate.submit(pcm)
    }

    override fun stop() {
        delegate.stop()
        synchronized(lock) { pcmOut?.flush(); indexOut?.flush() }
    }

    fun close() = synchronized(lock) { closeStreams() }

    private fun closeStreams() {
        try { pcmOut?.close() } catch (_: Exception) {}
        try { indexOut?.close() } catch (_: Exception) {}
        pcmOut = null
        indexOut = null
    }
}
