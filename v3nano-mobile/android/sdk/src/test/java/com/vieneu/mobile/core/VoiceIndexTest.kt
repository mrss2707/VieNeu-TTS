package com.vieneu.mobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Hồi quy: host đọc giọng từ `assets/voices/voices/...` (không tồn tại) → vector 0 → lẫn giọng nam/nữ. */
class VoiceIndexTest {

    private val packageDir: File = File(
        javaClass.classLoader!!.getResource("golden/pkg_voices/assets/voices/voices_v3_nano_mobile.json")!!.toURI(),
    ).parentFile.parentFile.parentFile // .../pkg_voices

    private val indexFile = File(packageDir, "assets/voices/voices_v3_nano_mobile.json")

    private val expected = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("golden/voices_expected.json")!!.readText(),
    ).jsonObject

    @Test
    fun resolvesEveryFileToAPathThatExists() {
        val idx = VoiceIndex.load(indexFile)
        assertEquals(11, idx.voices.size)
        for (v in idx.voices) {
            assertTrue("thiếu ${v.speakerEmbFile}", v.speakerEmbFile.isFile)
            assertTrue("thiếu ${v.styleFile}", v.styleFile.isFile)
            assertFalse("đường dẫn lặp 'voices/voices': ${v.speakerEmbFile}", v.speakerEmbFile.path.contains("voices/voices"))
        }
    }

    @Test
    fun everyVoiceMatchesTheSourcePresetAndIsNotZero() {
        val idx = VoiceIndex.load(indexFile)
        val voices = expected["voices"]!!.jsonObject
        assertEquals(voices.keys, idx.voices.map { it.id }.toSet())
        for (v in idx.voices) {
            val e = voices[v.id]!!.jsonObject
            val spk = VoiceIndex.readEmbedding(v.speakerEmbFile, VoiceIndex.SPK_DIM)
            val sty = VoiceIndex.readEmbedding(v.styleFile, VoiceIndex.STYLE_DIM)
            assertEquals("${v.id} spk_sum", e["spk_sum"]!!.jsonPrimitive.content.toDouble(), spk.sumOf { it.toDouble() }, 1e-3)
            assertEquals("${v.id} style_sum", e["style_sum"]!!.jsonPrimitive.content.toDouble(), sty.sumOf { it.toDouble() }, 1e-2)
            e["spk_head"]!!.jsonArray.forEachIndexed { i, x -> assertEquals("${v.id} spk[$i]", x.jsonPrimitive.content.toDouble(), spk[i].toDouble(), 1e-6) }
            e["style_head"]!!.jsonArray.forEachIndexed { i, x -> assertEquals("${v.id} style[$i]", x.jsonPrimitive.content.toDouble(), sty[i].toDouble(), 1e-6) }
            assertEquals("${v.id} gender", e["gender"]!!.jsonPrimitive.content, v.gender)
        }
    }

    @Test
    fun differentVoicesHaveDifferentVectors() {
        val idx = VoiceIndex.load(indexFile)
        val a = VoiceIndex.readEmbedding(idx.find("my_duyen")!!.speakerEmbFile, VoiceIndex.SPK_DIM)
        val b = VoiceIndex.readEmbedding(idx.find("duc_tri")!!.speakerEmbFile, VoiceIndex.SPK_DIM)
        assertTrue("nữ và nam không được trùng vector", !a.contentEquals(b))
        assertEquals("female", idx.find("my_duyen")!!.gender)
        assertEquals("male", idx.find("duc_tri")!!.gender)
    }

    @Test
    fun defaultVoiceComesFromIndex() {
        assertEquals(expected["default"]!!.jsonPrimitive.content, VoiceIndex.load(indexFile).defaultVoiceId)
    }

    @Test
    fun oldPathBuildingWasWrongAndMissing() {
        // Cách nối cũ: thư mục chứa index + đường dẫn tương đối trong index.
        val rel = "voices/my_duyen/speaker_emb.bin"
        assertFalse(File(indexFile.parentFile, rel).isFile)
        assertTrue(File(indexFile.parentFile.parentFile, rel).isFile)
    }

    @Test
    fun missingFileIsAnErrorNotAZeroVector() {
        try {
            VoiceIndex.readEmbedding(File(packageDir, "assets/voices/voices/my_duyen/speaker_emb.bin"), VoiceIndex.SPK_DIM)
            fail("phải ném")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.contains("Không tìm thấy"))
        }
    }

    @Test
    fun wrongSizeAndAllZeroAreRejected() {
        val bad = File.createTempFile("emb", ".bin").also { it.deleteOnExit() }
        bad.writeBytes(ByteArray(10))
        try { VoiceIndex.readEmbedding(bad, VoiceIndex.SPK_DIM); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("cần")) }
        bad.writeBytes(ByteArray(VoiceIndex.SPK_DIM * 4))
        try { VoiceIndex.readEmbedding(bad, VoiceIndex.SPK_DIM); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("toàn số 0")) }
    }

    @Test
    fun indexWithoutFilesIsRejected() {
        val dir = File.createTempFile("pkg", "").also { it.delete(); it.mkdirs(); it.deleteOnExit() }
        val voicesDir = File(dir, "assets/voices").also { it.mkdirs() }
        val f = File(voicesDir, "voices_v3_nano_mobile.json")
        f.writeText("""{"voices":[{"voice_id":"x","display_name":"X"}]}""")
        try { VoiceIndex.load(f); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("files.speaker_emb")) }
    }
}
