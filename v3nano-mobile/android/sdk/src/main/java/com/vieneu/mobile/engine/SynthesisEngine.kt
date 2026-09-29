package com.vieneu.mobile.engine

import com.vieneu.mobile.api.EngineError

/**
 * Engine sinh audio một chunk — ORT binding nằm ở module app/harness
 * (onnxruntime-android không kéo vào core test JVM — quyết định plan 001-mvp).
 *
 * Contract:
 * - 4 session (mỗi graph 1), load qua `createSession(path)` — KHÔNG `byte[]` (OOM Dalvik).
 * - `ctx_mask` kiểu BOOL.
 * - Trả PCM 24 kHz mono float32.
 */
interface SynthesisEngine {
    /** Load 4 graph từ [modelDir] (đã verify checksum bởi ModelRegistry). */
    @Throws(EngineError::class)
    fun load(modelDir: String, threads: Int = 4)

    val isLoaded: Boolean

    /**
     * Sinh waveform cho MỘT chunk phone ids.
     * @param phoneIds [bos, ..., eos]
     * @param speakerEmb float32 192
     * @param style float32 50×256 (flatten 12800)
     */
    @Throws(EngineError::class)
    fun synthesizeChunk(
        phoneIds: LongArray,
        speakerEmb: FloatArray,
        style: FloatArray,
        seed: Long,
    ): FloatArray

    fun close()
}

/** Kết quả probe-load / provider init — log vào Metrics, không suy luận silent fallback. */
data class ProviderStatus(
    val requested: String,
    val actual: String,
    val fellBackToCpu: Boolean,
    val reason: String? = null,
)

interface SessionBuilderFactory {
    /**
     * Thử khởi tạo provider theo thứ tự [order] (D2: CPU → XNNPACK).
     * Trả về provider THẬT đang dùng + lý do fallback (nếu có).
     */
    fun create(order: List<String>): ProviderStatus
}
