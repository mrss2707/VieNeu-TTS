package com.vieneu.mobile.cache

import java.io.File
import java.security.MessageDigest

/**
 * Disk LRU cache chunk audio (01 §1.6 / 04 §4.4).
 * Path theo model revision; quota byte; dọn khi cancel / đổi voice / trim memory.
 */
class AudioCache(
    private val root: File,
    private val revision: String,
    private val quotaBytes: Long = 64L * 1024 * 1024,
) {
    private val dir = File(root, revision)
    private val lock = Any()

    init {
        dir.mkdirs()
    }

    private fun keyFile(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }.take(32)
        return File(dir, "$name.pcm")
    }

    fun put(key: String, pcm: FloatArray) {
        synchronized(lock) {
            val f = keyFile(key)
            f.outputStream().buffered().use { out ->
                val buf = java.nio.ByteBuffer.allocate(pcm.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                buf.asFloatBuffer().put(pcm)
                out.write(buf.array())
            }
            evictIfNeeded()
        }
    }

    fun get(key: String): FloatArray? {
        synchronized(lock) {
            val f = keyFile(key)
            if (!f.isFile) return null
            // touch LRU
            f.setLastModified(System.currentTimeMillis())
            val bytes = f.readBytes()
            val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val out = FloatArray(bytes.size / 4)
            buf.asFloatBuffer().get(out)
            return out
        }
    }

    fun contains(key: String): Boolean = synchronized(lock) { keyFile(key).isFile }

    fun remove(key: String) {
        synchronized(lock) { keyFile(key).delete() }
    }

    fun clear() {
        synchronized(lock) { dir.listFiles()?.forEach { it.delete() } }
    }

    fun sizeBytes(): Long = synchronized(lock) {
        dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    fun entryCount(): Int = synchronized(lock) { dir.listFiles()?.size ?: 0 }

    /** Evict LRU (oldest lastModified) cho tới khi size <= quota. */
    private fun evictIfNeeded() {
        var size = dir.listFiles()?.sumOf { it.length() } ?: 0L
        if (size <= quotaBytes) return
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        for (f in files) {
            if (size <= quotaBytes) break
            size -= f.length()
            f.delete()
        }
    }
}
