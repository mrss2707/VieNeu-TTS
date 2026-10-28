package com.vieneu.mobile.api

import com.vieneu.mobile.core.GenerationProfile
import com.vieneu.mobile.core.ModelRegistry
import com.vieneu.mobile.core.PhoneEncoder
import com.vieneu.mobile.core.TextNormalizer
import com.vieneu.mobile.engine.SynthesisEngine
import com.vieneu.mobile.pipeline.SynthesisPipeline
import com.vieneu.mobile.player.AudioPlayer
import com.vieneu.mobile.player.GenerationQueue
import com.vieneu.mobile.player.StreamingReader

/**
 * Entry point SDK — builder pattern (04-sdk-android.md §4.2).
 *
 * Không expose ORT/tensor/session. Model install qua [ModelRegistry]
 * (checksum → atomic → probe-load hook).
 *
 * Truyền [Builder.audioPlayer] để [newReader] trả Reader bật streaming (AudioTrack);
 * bỏ qua thì Reader chạy chế độ offline (sinh một lần) như cũ.
 */
class VieNeuTts private constructor(
    val profile: GenerationProfile,
    val modelRegistry: ModelRegistry,
    private val engine: SynthesisEngine,
    private val phoneEncoder: PhoneEncoder,
    private val normalizer: TextNormalizer,
    private val phonemizer: ((String) -> String)?,
    private val audioPlayer: AudioPlayer? = null,
    private val prefetchDepth: Int = 3,
) {
    private var pipeline: SynthesisPipeline? = null
    private var streamingReader: StreamingReader? = null

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
        val player = audioPlayer
        val streaming = if (player != null) {
            StreamingReader(
                pipeline = p,
                profile = profile,
                player = player,
                queue = GenerationQueue(prefetchDepth),
                normalizer = normalizer,
            ).also { streamingReader = it }
        } else {
            null
        }
        return Reader(p, streaming)
    }

    /** Giải phóng engine + player. */
    fun close() {
        streamingReader?.release()
        streamingReader = null
        engine.close()
    }

    fun voiceManager(): VoiceManager = VoiceManager

    class Builder {
        private var profile: GenerationProfile = GenerationProfile()
        private var registry: ModelRegistry? = null
        private var engine: SynthesisEngine? = null
        private var encoder: PhoneEncoder? = null
        private var normalizer: TextNormalizer = TextNormalizer.mini
        private var phonemizer: ((String) -> String)? = null
        private var audioPlayer: AudioPlayer? = null
        private var prefetchDepth: Int = 3

        fun profile(p: GenerationProfile) = apply { profile = p }
        fun modelRegistry(r: ModelRegistry) = apply { registry = r }
        fun engine(e: SynthesisEngine) = apply { engine = e }
        fun phoneEncoder(e: PhoneEncoder) = apply { encoder = e }
        fun normalizer(n: TextNormalizer) = apply { normalizer = n }
        fun phonemizer(f: ((String) -> String)?) = apply { phonemizer = f }

        /**
         * Bật playback streaming (AndroidAudioPlayer trên Android). Bỏ qua → Reader offline.
         */
        fun audioPlayer(p: AudioPlayer?) = apply { audioPlayer = p }

        /** Bound của ready-buffer (≥2 để hạn chế underrun). */
        fun streaming(prefetchDepth: Int = 3) = apply {
            require(prefetchDepth >= 1) { "prefetchDepth >= 1" }
            this.prefetchDepth = prefetchDepth
        }

        fun build(): VieNeuTts {
            val eng = requireNotNull(engine) { "phải set engine(OrtSynthesisEngine)" }
            val enc = requireNotNull(encoder) { "phải set phoneEncoder" }
            val reg = requireNotNull(registry) { "phải set modelRegistry(ModelRegistry(root))" }
            return VieNeuTts(profile, reg, eng, enc, normalizer, phonemizer, audioPlayer, prefetchDepth)
        }
    }
}
