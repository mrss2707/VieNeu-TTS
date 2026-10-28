package com.vieneu.mobile.ort

import ai.onnxruntime.OrtEnvironment
import com.vieneu.mobile.api.EngineError
import com.vieneu.mobile.engine.SynthesisEngine
import java.io.File

/**
 * [SynthesisEngine] trên ORT Android — chuyển từ
 * `android/device-validation/.../OrtSynthesisEngine.kt` sang module ORT này.
 *
 * Load 4 graph qua `createSession(path)` (file trên đĩa — tránh OOM Dalvik).
 * Thân inference dùng [OrtInference.run] (đã lock parity vòng 2–8).
 *
 * Nhận [modelDir] trực tiếp trong [load] — việc resolve asset/thư mục nằm ở host,
 * module này KHÔNG phụ thuộc `Context`.
 */
class OrtSynthesisEngine(
    private val threads: Int = 4,
    private val provider: String = "CPU",
    private val steps: Int = 8,
    private val cfg: Double = 3.0,
    private val sway: Double = -1.0,
    private val padId: Long = 0,
    private val bosId: Long = 1,
    private val eosId: Long = 2,
    private val fps: Double = 12.5,
    private val nullSpk: FloatArray = FloatArray(192),
    private val nullStyle: FloatArray = FloatArray(50 * 256),
) : SynthesisEngine {

    private var env: OrtEnvironment? = null
    private var graphs: OrtGraphs? = null

    override var isLoaded: Boolean = false
        private set

    @Throws(EngineError::class)
    override fun load(modelDir: String, threads: Int) {
        try {
            val e = OrtEnvironment.getEnvironment()
            val so = OrtSessionOptions.providerStatusFor(provider).first
            so.setIntraOpNumThreads(threads)
            val base = { name: String ->
                // Chấp nhận cả 3 layout: modelDir/<name>, modelDir/graphs/<file>,
                // modelDir/nano_graphs/<file> (harness cũ) và file phẳng.
                val file = File(name).name
                listOf(
                    File(modelDir, name),
                    File(modelDir, file),
                    File(modelDir, "graphs/$file"),
                    File(modelDir, "nano_graphs/$file"),
                ).firstOrNull { it.isFile }?.absolutePath ?: File(modelDir, name).absolutePath
            }
            graphs = OrtGraphs(e, so, base)
            env = e
            isLoaded = true
        } catch (t: Throwable) {
            isLoaded = false
            throw EngineError.Corrupt("Không load được graph từ $modelDir: ${t.message}", t)
        }
    }

    @Throws(EngineError::class)
    override fun synthesizeChunk(
        phoneIds: LongArray,
        speakerEmb: FloatArray,
        style: FloatArray,
        seed: Long,
    ): FloatArray {
        val g = graphs ?: throw EngineError.Internal("Engine chưa load")
        val e = env ?: throw EngineError.Internal("OrtEnvironment missing")
        return try {
            // DeterministicRng seed theo chunk nằm sẵn trong OrtInference.run (seed=0).
            // [seed] của caller nối vào khi GenerationQueue có cancel boundary (defer).
            val (wav, _, _) = OrtInference.run(
                g, e, phoneIds, speakerEmb, style, nullSpk, nullStyle,
                padId, bosId, eosId, fps, steps, cfg, sway,
            )
            wav
        } catch (t: Throwable) {
            throw EngineError.Internal("Inference fail: ${t.message}", t)
        }
    }

    override fun close() {
        graphs?.close()
        graphs = null
        env = null
        isLoaded = false
    }
}
