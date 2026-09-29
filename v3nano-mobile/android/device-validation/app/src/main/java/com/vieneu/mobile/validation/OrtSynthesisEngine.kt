package com.vieneu.mobile.validation

import ai.onnxruntime.OrtEnvironment
import com.vieneu.mobile.api.EngineError
import com.vieneu.mobile.engine.SynthesisEngine
import java.io.File

/**
 * [SynthesisEngine] trên ORT Android — adapter Sprint 3.
 *
 * Load 4 graph qua `createSession(path)` (asset đã copy ra cache — tránh OOM Dalvik).
 * Thân inference dùng [NanoBenchmarkCore.runInference] (đã lock parity vòng 2–8).
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
    private var graphs: NanoBenchmarkCore.Graphs? = null

    override var isLoaded: Boolean = false
        private set

    @Throws(EngineError::class)
    override fun load(modelDir: String, threads: Int) {
        try {
            val e = OrtEnvironment.getEnvironment()
            val so = NanoBenchmarkCore.sessionOptionsForProvider(provider).first
            so.setIntraOpNumThreads(threads)
            val base = { name: String ->
                val f = File(modelDir, name)
                if (f.isFile) f.absolutePath else File(modelDir, "nano_graphs/${File(name).name}").absolutePath
            }
            graphs = NanoBenchmarkCore.Graphs(e, so, base)
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
            // DeterministicRng seed theo chunk nằm sẵn trong runInference (seed=0).
            // [seed] của caller sẽ nối vào ở Sprint 4 khi GenerationQueue có cancel boundary.
            val (wav, _, _) = NanoBenchmarkCore.runInference(
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
