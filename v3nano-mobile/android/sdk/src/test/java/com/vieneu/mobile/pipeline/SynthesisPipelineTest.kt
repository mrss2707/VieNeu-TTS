package com.vieneu.mobile.pipeline

import com.vieneu.mobile.api.EngineError
import com.vieneu.mobile.core.GenerationProfile
import com.vieneu.mobile.core.PhoneEncoder
import com.vieneu.mobile.core.TextNormalizer
import com.vieneu.mobile.engine.SynthesisEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pipeline wiring test với fake engine — không cần ORT.
 * Chunking + join dùng core đã lock golden.
 */
class SynthesisPipelineTest {

    private class FakeEngine : SynthesisEngine {
        var loaded = false
        var calls = 0
        override fun load(modelDir: String, threads: Int) { loaded = true }
        override val isLoaded: Boolean get() = loaded
        override fun synthesizeChunk(
            phoneIds: LongArray,
            speakerEmb: FloatArray,
            style: FloatArray,
            seed: Long,
        ): FloatArray {
            calls++
            // 0.1s audio @ 24k, amplitude theo seed để nhận diện chunk
            return FloatArray(2400) { i -> (seed % 100).toFloat() / 100f + i * 0f }
        }
        override fun close() { loaded = false }
    }

    private fun encoder() = PhoneEncoder(
        emotionMap = mapOf("<|emotion_1|>" to "①"),
        vocab = (0..20).associate { it.toString() to it } + mapOf(
            "a" to 1, "b" to 2, " " to 3, "." to 4, "①" to 5,
        ),
        bos = 1,
        eos = 2,
        pad = 0,
    )

    @Test
    fun synthesizesMultipleChunksAndJoins() {
        val engine = FakeEngine().also { it.load("/tmp") }
        val pipe = SynthesisPipeline(
            engine = engine,
            phoneEncoder = encoder(),
            profile = GenerationProfile(maxCharsPerChunk = 20),
            normalizer = TextNormalizer.identity,
        )
        val text = "Một câu khá dài để tách. Thêm câu nữa cũng dài không kém nhé!"
        val r = pipe.synthesizeText(text, FloatArray(192), FloatArray(12800), seed = 1)
        assertTrue(engine.calls >= 2)
        assertTrue(r.metrics.nChunks >= 2)
        assertTrue(r.pcm.isNotEmpty())
        assertTrue(r.metrics.audioSeconds!! > 0.0)
        assertEquals("int8", r.metrics.precision)
    }

    @Test
    fun rejectsEmptyText() {
        val engine = FakeEngine().also { it.load("/tmp") }
        val pipe = SynthesisPipeline(engine, encoder(), normalizer = TextNormalizer.identity)
        try {
            pipe.synthesizeText("   ", FloatArray(1), FloatArray(1))
            fail("phải throw InvalidInput")
        } catch (e: EngineError.InvalidInput) {
            assertEquals("E_INVALID_INPUT", e.code)
        }
    }

    @Test
    fun rejectsWhenEngineNotLoaded() {
        val pipe = SynthesisPipeline(FakeEngine(), encoder())
        try {
            pipe.synthesizePhones(listOf("a."), emptyList(), FloatArray(1), FloatArray(1))
            fail("phải throw Internal")
        } catch (e: EngineError.Internal) {
            assertEquals("E_INTERNAL", e.code)
        }
    }

    @Test
    fun seedPerChunkIsStable() {
        val engine = FakeEngine().also { it.load("/tmp") }
        val pipe = SynthesisPipeline(engine, encoder(), profile = GenerationProfile(maxCharsPerChunk = 20))
        val text = "Câu một dài hơi một chút. Câu hai cũng tương tự vậy đó!"
        val r1 = pipe.synthesizeText(text, FloatArray(2), FloatArray(2), seed = 42)
        val r2 = pipe.synthesizeText(text, FloatArray(2), FloatArray(2), seed = 42)
        assertEquals(r1.chunkSeconds, r2.chunkSeconds)
        assertEquals(r1.pcm.size, r2.pcm.size)
    }
}
