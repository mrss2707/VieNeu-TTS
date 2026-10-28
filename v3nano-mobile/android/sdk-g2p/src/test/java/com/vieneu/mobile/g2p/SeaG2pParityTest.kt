package com.vieneu.mobile.g2p

import com.vieneu.mobile.core.ChunkPhonemizer
import com.vieneu.mobile.core.SeaTextNormalizer
import com.vieneu.mobile.core.TextSegmenter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Parity với Python (g2p_golden.json, sinh bởi host/export_g2p_golden.py) chạy LÕI RUST THẬT
 * qua JNI trên JVM host. Cần dylib + vi.seaz do native/sea-g2p-mobile/build_android.sh dựng;
 * thiếu thì bỏ qua (assume) — kiểm số test bị skip khi chạy CI.
 */
class SeaG2pParityTest {
    private lateinit var backend: SeaG2pBackend
    private lateinit var golden: JsonObject

    @Before
    fun setUp() {
        val dict = System.getProperty("sea.g2p.dict")?.let(::File)
        val libDirs = System.getProperty("java.library.path").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.map(::File)
        val hasLib = libDirs.any { d -> listOf("libsea_g2p_rs.dylib", "libsea_g2p_rs.so").any { File(d, it).isFile } }
        assumeTrue("thiếu vi.seaz — chạy native/sea-g2p-mobile/build_android.sh", dict?.isFile == true)
        assumeTrue("thiếu libsea_g2p_rs — chạy build_android.sh", hasLib)
        backend = SeaG2pBackend(dict!!.absolutePath)
        golden = Json.parseToJsonElement(
            javaClass.classLoader!!.getResource("g2p_golden.json")!!.readText(),
        ).jsonObject
    }

    @After
    fun tearDown() {
        if (::backend.isInitialized) backend.close()
    }

    private fun cases(): JsonArray = golden["cases"]!!.jsonArray
    private fun strings(o: JsonObject, k: String) = o[k]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun segmentationWithRustNormalizerMatchesPython() {
        val maxChars = golden["max_chars"]!!.jsonPrimitive.content.toInt()
        val norm = SeaTextNormalizer(backend)
        val bad = ArrayList<String>()
        for (c in cases()) {
            val o = c.jsonObject
            val text = o["text"]!!.jsonPrimitive.content
            val seg = TextSegmenter.segmentWithGaps(text, maxChars = maxChars, normalizer = norm)
            if (seg.chunks != strings(o, "chunks") || seg.gaps != strings(o, "gaps")) {
                bad.add("«${text.take(50)}»\n  py : ${strings(o, "chunks")} ${strings(o, "gaps")}\n  kt : ${seg.chunks} ${seg.gaps}")
            }
        }
        assertTrue("${bad.size}/${cases().size} lệch:\n" + bad.take(3).joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun phonemesOfEveryGoldenChunkMatchPython() {
        val ph = ChunkPhonemizer(backend)
        var n = 0
        for (c in cases()) {
            val o = c.jsonObject
            val chunks = strings(o, "chunks")
            val phones = strings(o, "phones")
            for (i in chunks.indices) {
                val got = if (chunks[i].isBlank()) "" else ph.phonemize(chunks[i])
                assertEquals("chunk «${chunks[i].take(60)}»", phones[i], got)
                n++
            }
        }
        assertTrue("golden phải có ≥50 chunk, có $n", n >= 50)
    }

    @Test
    fun endToEndTextToPhonesMatchesPython() {
        val maxChars = golden["max_chars"]!!.jsonPrimitive.content.toInt()
        val norm = SeaTextNormalizer(backend)
        val ph = ChunkPhonemizer(backend)
        for (c in cases()) {
            val o = c.jsonObject
            val seg = TextSegmenter.segmentWithGaps(o["text"]!!.jsonPrimitive.content, maxChars = maxChars, normalizer = norm)
            assertEquals(strings(o, "phones"), seg.chunks.map { if (it.isBlank()) "" else ph.phonemize(it) })
        }
    }

    @Test
    fun rawVietnameseTextNeverLosesVowelsLikeTheOldDemo() {
        // Hồi quy "nuốt chữ": chữ thô qua PhoneEncoder làm rơi ă ư ờ ê... Giờ phải ra IPA.
        val out = backend.phonemize("Năm mười lăm tuổi", true)
        assertTrue(out, out.none { it in "ăườêấủộ" })
        assertTrue(out, out.contains("ˈ"))
    }

    @Test
    fun emptyAndNulTextBehave() {
        assertEquals("", backend.phonemize("", true))
        try {
            backend.phonemize("a\u0000b", true)
            fail("NUL phải bị từ chối")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("NUL"))
        }
        // sau lỗi, backend vẫn dùng được (handle được trả về pool)
        assertTrue(backend.phonemize("xin chào", true).isNotEmpty())
    }

    @Test
    fun badDictionaryFailsAtConstruction() {
        try {
            SeaG2pBackend("/không/tồn/tại.seaz")
            fail("phải ném")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("sea_g2p_open"))
        }
    }

    @Test
    fun closedBackendRejectsCalls() {
        backend.close()
        try {
            backend.phonemize("xin chào", true)
            fail("phải ném sau close")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("đã đóng"))
        }
        backend.close() // idempotent
    }

    @Test
    fun concurrentCallsFromManyThreadsAreCorrect() {
        val expected = backend.phonemize("Hôm nay trời đẹp quá, chúng ta đi dạo nhé.", true)
        val pool = Executors.newFixedThreadPool(8)
        val failures = java.util.Collections.synchronizedList(ArrayList<String>())
        val done = CountDownLatch(8)
        repeat(8) {
            pool.execute {
                try {
                    repeat(300) {
                        val got = backend.phonemize("Hôm nay trời đẹp quá, chúng ta đi dạo nhé.", true)
                        if (got != expected) failures.add(got)
                        backend.normalize("Giá 1.500.000đ", false)
                    }
                } catch (t: Throwable) {
                    failures.add(t.toString())
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue("timeout", done.await(60, TimeUnit.SECONDS))
        pool.shutdown()
        assertTrue(failures.take(3).toString(), failures.isEmpty())
    }
}
