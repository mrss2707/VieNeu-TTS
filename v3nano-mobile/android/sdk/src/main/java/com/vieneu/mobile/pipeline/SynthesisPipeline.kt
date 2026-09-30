package com.vieneu.mobile.pipeline

import com.vieneu.mobile.api.EngineError
import com.vieneu.mobile.api.Metrics
import com.vieneu.mobile.core.AudioComfortFilter
import com.vieneu.mobile.core.ChunkJoiner
import com.vieneu.mobile.core.GenerationProfile
import com.vieneu.mobile.core.PhoneEncoder
import com.vieneu.mobile.core.TextNormalizer
import com.vieneu.mobile.core.TextSegmenter
import com.vieneu.mobile.core.TrebleFilter
import com.vieneu.mobile.engine.SynthesisEngine

/**
 * Pipeline text/phones → chunk audio đã join (Sprint 2b + 3).
 *
 * Ứng dụng 04 §4.4 / 07: mọi path sinh âm PHẢI đi qua chunk pipeline đúng
 * (bài học bug "nuốt chữ" vòng 8) — không gọi engine trực tiếp cho câu dài.
 *
 * 2b: [TextSegmenter] port structural. Normalize/G2P đầy đủ (sea-g2p) vẫn
 * host-side hoặc [TextNormalizer.mini] — production dùng [synthesizePhones]
 * với phones đã phonemize, hoặc [synthesizeText] nếu integrator inject phonemizer.
 */
class SynthesisPipeline(
    private val engine: SynthesisEngine,
    private val phoneEncoder: PhoneEncoder,
    private val profile: GenerationProfile = GenerationProfile(),
    private val normalizer: TextNormalizer = TextNormalizer.mini,
    private val phonemizer: ((String) -> String)? = null,
) {
    data class Result(
        val pcm: FloatArray,
        val metrics: Metrics,
        val chunkSeconds: List<Double>,
    )

    /**
     * Text → segment → (phonemize) → encode → synthesize từng chunk → join gaps.
     * @param phonemize dùng [phonemizer] inject; null → giữ nguyên text như phones
     *   (chỉ hợp lệ khi caller đã là phoneme stream — xem [synthesizePhones]).
     */
    fun synthesizeText(
        text: String,
        speakerEmb: FloatArray,
        style: FloatArray,
        seed: Long = 0L,
        sr: Int = 24_000,
        onEvent: ((String) -> Unit)? = null,
    ): Result {
        val segmented = TextSegmenter.segmentWithGaps(
            text,
            maxChars = profile.maxCharsPerChunk,
            normalizer = normalizer,
        )
        if (segmented.chunks.isEmpty()) {
            throw EngineError.InvalidInput("Text rỗng sau khi segment")
        }
        val phones = segmented.chunks.map { chunk ->
            phonemizer?.invoke(chunk) ?: chunk
        }
        return synthesizePhoneChunks(phones, segmented.gaps, speakerEmb, style, seed, sr, onEvent)
    }

    /** Phones đã phonemize từng chunk → engine → join. */
    fun synthesizePhones(
        phoneChunks: List<String>,
        gaps: List<String>,
        speakerEmb: FloatArray,
        style: FloatArray,
        seed: Long = 0L,
        sr: Int = 24_000,
        onEvent: ((String) -> Unit)? = null,
    ): Result = synthesizePhoneChunks(phoneChunks, gaps, speakerEmb, style, seed, sr, onEvent)

    private fun synthesizePhoneChunks(
        phoneChunks: List<String>,
        gaps: List<String>,
        speakerEmb: FloatArray,
        style: FloatArray,
        seed: Long,
        sr: Int,
        onEvent: ((String) -> Unit)?,
    ): Result {
        if (!engine.isLoaded) throw EngineError.Internal("Engine chưa load model")
        val pcmChunks = ArrayList<FloatArray>(phoneChunks.size)
        val chunkSeconds = ArrayList<Double>(phoneChunks.size)
        var genMs = 0L
        var audioSec = 0.0
        for ((i, phones) in phoneChunks.withIndex()) {
            val ids = phoneEncoder.encode(phones) { ch ->
                onEvent?.invoke("oov:$ch")
            }
            val t0 = System.nanoTime()
            val pcm = engine.synthesizeChunk(ids, speakerEmb, style, seed + i)
            genMs += (System.nanoTime() - t0) / 1_000_000
            pcmChunks.add(pcm)
            val sec = pcm.size.toDouble() / sr
            chunkSeconds.add(sec)
            audioSec += sec
        }
        var joined = ChunkJoiner.joinAudioChunks(pcmChunks, sr, gaps, onEvent)
        // Comfort DSP (rev 2): treble + presence dip + shelf + soft-clip.
        // rev 3: optional noise-floor downward expander (default ON).
        if (profile.comfortFilterEnabled) {
            joined = AudioComfortFilter.apply(joined, profile.noiseFloorEnabled)
        } else if (profile.trebleFilterEnabled) {
            joined = TrebleFilter.apply(joined)
        }
        val rtf = if (audioSec > 0) genMs / 1000.0 / audioSec else null
        val metrics = Metrics(
            rtf = rtf,
            generationMs = genMs,
            audioSeconds = audioSec,
            nChunks = pcmChunks.size,
            steps = profile.steps,
            precision = "fp16",
        )
        return Result(joined, metrics, chunkSeconds)
    }
}
