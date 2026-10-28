package com.vieneu.mobile.pipeline

import com.vieneu.mobile.api.EngineError
import com.vieneu.mobile.core.ChunkJoiner
import com.vieneu.mobile.core.GenerationProfile
import com.vieneu.mobile.core.PhoneEncoder
import com.vieneu.mobile.core.TextNormalizer
import com.vieneu.mobile.engine.SynthesisEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

/** Đường streaming một chunk: phonemize ở worker, pad gap ở đuôi, DSP một lần. */
class SynthesisStreamChunkTest {

    private val sr = 24_000

    /** 0,4 s tiếng 220 Hz + 0,1 s im lặng ở đuôi. */
    private class ToneEngine : SynthesisEngine {
        val seenIds = ArrayList<LongArray>()
        override fun load(modelDir: String, threads: Int) {}
        override val isLoaded: Boolean = true
        override fun synthesizeChunk(phoneIds: LongArray, speakerEmb: FloatArray, style: FloatArray, seed: Long): FloatArray {
            seenIds.add(phoneIds)
            return FloatArray(12_000) { i -> if (i < 9_600) (0.4 * sin(2 * Math.PI * 220.0 * i / 24_000)).toFloat() else 0f }
        }
        override fun close() {}
    }

    private fun encoder() = PhoneEncoder(
        emotionMap = emptyMap(),
        vocab = ('a'..'z').withIndex().associate { (i, c) -> c.toString() to i + 3 } + mapOf(" " to 40, "." to 41),
        bos = 1, eos = 2, pad = 0,
    )

    private fun pipe(
        engine: SynthesisEngine = ToneEngine(),
        profile: GenerationProfile = GenerationProfile(comfortFilterEnabled = false, trebleFilterEnabled = false),
        phonemizer: ((String) -> String)? = { it },
    ) = SynthesisPipeline(engine, encoder(), profile, TextNormalizer.identity, phonemizer)

    @Test
    fun gapPadExtendsTailToTheGapSilenceFloor() {
        for ((gap, pauseS) in ChunkJoiner.V3_GAP_SILENCE) {
            val r = pipe().synthesizeStreamChunk("abc.", isPhones = false, gapAfter = gap, FloatArray(1), FloatArray(1))
            val (_, tail) = ChunkJoiner.edgeSilence(r.pcm, sr)
            assertTrue("gap=$gap tail=${tail / sr.toDouble()}s < $pauseS", tail >= (pauseS * sr).toInt() - 480)
            assertEquals("chunkSeconds chỉ tính tiếng nói", 0.5, r.chunkSeconds.single(), 1e-9)
        }
    }

    @Test
    fun lastChunkGetsNoPad() {
        val r = pipe().synthesizeStreamChunk("abc.", isPhones = false, gapAfter = null, FloatArray(1), FloatArray(1))
        assertEquals(12_000, r.pcm.size)
    }

    @Test
    fun comfortDspSeesTheSilencePadAndPeakIsNormalised() {
        val p = pipe(profile = GenerationProfile())
        val r = p.synthesizeStreamChunk("abc.", isPhones = false, gapAfter = "para", FloatArray(1), FloatArray(1))
        assertTrue(r.pcm.size > 12_000)
        assertTrue(r.pcm.none { it.isNaN() })
        assertEquals(0.8912509f, r.pcm.maxOf { abs(it) }, 1e-3f)
        // đuôi im lặng sau gate: gần 0, không còn dư âm
        assertTrue(r.pcm.takeLast(2_000).all { abs(it) < 1e-3f })
    }

    @Test
    fun rawTextWithoutPhonemizerIsRejectedNotEncoded() {
        val engine = ToneEngine()
        try {
            pipe(engine, phonemizer = null).synthesizeStreamChunk(
                "Năm mười lăm tuổi", isPhones = false, gapAfter = null, FloatArray(1), FloatArray(1),
            )
            fail("phải ném InvalidInput")
        } catch (e: EngineError.InvalidInput) {
            assertEquals("E_INVALID_INPUT", e.code)
        }
        assertEquals("không được chạy engine với chữ thô", 0, engine.seenIds.size)
    }

    @Test
    fun phonemizerOutputIsWhatReachesTheEngine() {
        val engine = ToneEngine()
        pipe(engine, phonemizer = { "abc" }).synthesizeStreamChunk("ignored", isPhones = false, gapAfter = null, FloatArray(1), FloatArray(1))
        assertEquals(listOf(1L, 3L, 4L, 5L, 2L), engine.seenIds.single().toList())
    }

    @Test
    fun phonemizerFailureBecomesEngineError() {
        try {
            pipe(phonemizer = { throw IllegalStateException("native boom") })
                .synthesizeStreamChunk("x", isPhones = false, gapAfter = null, FloatArray(1), FloatArray(1))
            fail("phải ném EngineError")
        } catch (e: EngineError.Internal) {
            assertTrue(e.message.contains("native boom"))
        }
    }

    @Test
    fun phoneChunksSkipPhonemizer() {
        var called = false
        val engine = ToneEngine()
        pipe(engine, phonemizer = { called = true; it })
            .synthesizeStreamChunk("abc", isPhones = true, gapAfter = null, FloatArray(1), FloatArray(1))
        assertTrue(!called)
        assertEquals(1, engine.seenIds.size)
    }
}
