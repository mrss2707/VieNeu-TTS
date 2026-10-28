package com.vieneu.mobile.g2p

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vieneu.mobile.core.ChunkPhonemizer
import com.vieneu.mobile.core.SeaTextNormalizer
import com.vieneu.mobile.core.TextSegmenter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Chạy trên Android thật/emulator: libsea_g2p_rs.so qua JNI + vi.seaz chép từ assets APK.
 * Cùng g2p_golden.json (Python) với test JVM — chứng minh bản .so arm64 cho đúng phoneme.
 */
@RunWith(AndroidJUnit4::class)
class SeaG2pDeviceTest {
    private lateinit var backend: SeaG2pBackend

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        backend = SeaG2pBackend(SeaG2pAssets.install(ctx).absolutePath)
    }

    @After
    fun tearDown() = backend.close()

    @Test
    fun goldenSegmentationAndPhonemesMatchPython() {
        val golden = Json.parseToJsonElement(
            javaClass.classLoader!!.getResourceAsStream("g2p_golden.json")!!.bufferedReader().readText(),
        ).jsonObject
        val maxChars = golden["max_chars"]!!.jsonPrimitive.content.toInt()
        val norm = SeaTextNormalizer(backend)
        val ph = ChunkPhonemizer(backend)
        var chunks = 0
        val t0 = System.nanoTime()
        for (c in golden["cases"]!!.jsonArray) {
            val o = c.jsonObject
            val seg = TextSegmenter.segmentWithGaps(o["text"]!!.jsonPrimitive.content, maxChars = maxChars, normalizer = norm)
            assertEquals(o["chunks"]!!.jsonArray.map { it.jsonPrimitive.content }, seg.chunks)
            assertEquals(o["gaps"]!!.jsonArray.map { it.jsonPrimitive.content }, seg.gaps)
            assertEquals(
                o["phones"]!!.jsonArray.map { it.jsonPrimitive.content },
                seg.chunks.map { if (it.isBlank()) "" else ph.phonemize(it) },
            )
            chunks += seg.chunks.size
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        android.util.Log.i("SeaG2pDeviceTest", "PARITY_OK chunks=$chunks total_ms=$ms")
        assertTrue(chunks >= 50)
    }
}
