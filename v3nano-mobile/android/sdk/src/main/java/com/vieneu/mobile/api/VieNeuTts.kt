package com.vieneu.mobile.api

import com.vieneu.mobile.core.GenerationProfile
import com.vieneu.mobile.core.ModelRegistry
import com.vieneu.mobile.core.PhoneEncoder
import com.vieneu.mobile.core.TextNormalizer
import com.vieneu.mobile.engine.SynthesisEngine
import com.vieneu.mobile.pipeline.SynthesisPipeline

/**
 * Entry point SDK — builder pattern (04-sdk-android.md §4.2).
 *
 * Không expose ORT/tensor/session. Model install qua [ModelRegistry]
 * (checksum → atomic → probe-load hook).
 */
class VieNeuTts private constructor(
    val profile: GenerationProfile,
    val modelRegistry: ModelRegistry,
    private val engine: SynthesisEngine,
    private val phoneEncoder: PhoneEncoder,
    private val normalizer: TextNormalizer,
    private val phonemizer: ((String) -> String)?,
) {
    private var pipeline: SynthesisPipeline? = null

    /** Load sessions từ [modelDir] đã install. */
    @Throws(EngineError::class)
    fun load(modelDir: String) {
        engine.load(modelDir, threads = profile.threads)
        pipeline = SynthesisPipeline(
            engine = engine,
            phoneEncoder = phoneEncoder,
            profile = profile,
            normalizer = normalizer,
            phonemizer = phonemizer,
        )
    }

    val isReady: Boolean get() = engine.isLoaded

    fun newReader(): Reader {
        val p = pipeline ?: throw EngineError.Internal("Gọi load(modelDir) trước khi newReader()")
        return Reader(p)
    }

    fun voiceManager(): VoiceManager = VoiceManager

    class Builder {
        private var profile: GenerationProfile = GenerationProfile()
        private var registry: ModelRegistry? = null
        private var engine: SynthesisEngine? = null
        private var encoder: PhoneEncoder? = null
        private var normalizer: TextNormalizer = TextNormalizer.mini
        private var phonemizer: ((String) -> String)? = null

        fun profile(p: GenerationProfile) = apply { profile = p }
        fun modelRegistry(r: ModelRegistry) = apply { registry = r }
        fun engine(e: SynthesisEngine) = apply { engine = e }
        fun phoneEncoder(e: PhoneEncoder) = apply { encoder = e }
        fun normalizer(n: TextNormalizer) = apply { normalizer = n }
        fun phonemizer(f: ((String) -> String)?) = apply { phonemizer = f }

        fun build(): VieNeuTts {
            val eng = requireNotNull(engine) { "phải set engine(OrtSynthesisEngine)" }
            val enc = requireNotNull(encoder) { "phải set phoneEncoder" }
            val reg = requireNotNull(registry) { "phải set modelRegistry(ModelRegistry(root))" }
            return VieNeuTts(profile, reg, eng, enc, normalizer, phonemizer)
        }
    }
}
