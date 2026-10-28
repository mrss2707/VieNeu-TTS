package com.vieneu.mobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** null_spk / null_style / flow_fps phải lấy từ gói (đã từng là 0 / 0 / 12,5 → lẫn giọng nam-nữ). */
class ModelConstantsTest {

    private fun resource(name: String): File {
        val tmp = File.createTempFile("res", name.substringAfterLast('/'))
        tmp.deleteOnExit()
        tmp.writeBytes(javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes())
        return tmp
    }

    private val expected = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("golden/constants_expected.json")!!.readText(),
    ).jsonObject

    private fun floats(key: String) = expected[key]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() }

    @Test
    fun realPackageConstantsMatchNumpy() {
        val c = ModelConstants.load(resource("golden/constants.npz"), 15.625)
        assertEquals(ModelConstants.SPK_DIM, c.nullSpk.size)
        assertEquals(ModelConstants.STYLE_DIM, c.nullStyle.size)
        floats("null_spk_head").forEachIndexed { i, v -> assertEquals(v, c.nullSpk[i].toDouble(), 1e-7) }
        floats("null_style_head").forEachIndexed { i, v -> assertEquals(v, c.nullStyle[i].toDouble(), 1e-7) }
        assertEquals(expected["null_spk_sum"]!!.jsonPrimitive.content.toDouble(), c.nullSpk.sumOf { it.toDouble() }, 1e-4)
        assertEquals(expected["null_style_sum"]!!.jsonPrimitive.content.toDouble(), c.nullStyle.sumOf { it.toDouble() }, 1e-3)
        assertEquals(expected["null_style_last"]!!.jsonPrimitive.content.toDouble(), c.nullStyle.last().toDouble(), 1e-7)
        assertEquals(15.625, c.flowFps, 0.0)
    }

    @Test
    fun loadedConstantsAreNotTheZeroDefaults() {
        val c = ModelConstants.load(resource("golden/constants.npz"), 15.625)
        assertTrue("null_spk không được là toàn 0", c.nullSpk.any { it != 0f })
        assertTrue("null_style không được là toàn 0", c.nullStyle.any { it != 0f })
    }

    private fun npy(vararg values: Float, descr: String = "<f4", fortran: Boolean = false): ByteArray {
        var header = "{'descr': '$descr', 'fortran_order': ${if (fortran) "True" else "False"}, 'shape': (${values.size},), }"
        while ((10 + header.length + 1) % 64 != 0) header += " "
        header += "\n"
        val bb = ByteBuffer.allocate(10 + header.length + values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.put(0x93.toByte()).put("NUMPY".toByteArray()).put(1).put(0).putShort(header.length.toShort())
        bb.put(header.toByteArray(Charsets.US_ASCII))
        values.forEach { bb.putFloat(it) }
        return bb.array()
    }

    private fun npz(entries: Map<String, ByteArray>): File {
        val f = File.createTempFile("const", ".npz").also { it.deleteOnExit() }
        ZipOutputStream(f.outputStream()).use { z ->
            entries.forEach { (n, b) -> z.putNextEntry(ZipEntry("$n.npy")); z.write(b); z.closeEntry() }
        }
        return f
    }

    private fun assertRejects(file: File, fps: Double = 15.625, mustContain: String) {
        try {
            ModelConstants.load(file, fps)
            fail("phải ném IllegalArgumentException chứa «$mustContain»")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.contains(mustContain))
        }
    }

    private val goodSpk = FloatArray(192) { 0.1f }
    private val goodStyle = FloatArray(12800) { 0.2f }

    @Test
    fun rejectsMissingArray() =
        assertRejects(npz(mapOf("null_spk" to npy(*goodSpk))), mustContain = "thiếu null_style")

    @Test
    fun rejectsWrongLength() =
        assertRejects(npz(mapOf("null_spk" to npy(1f, 2f), "null_style" to npy(*goodStyle))), mustContain = "cần 192")

    @Test
    fun rejectsWrongDtype() = assertRejects(
        npz(mapOf("null_spk" to npy(*goodSpk, descr = "<f8"), "null_style" to npy(*goodStyle))), mustContain = "float32",
    )

    @Test
    fun rejectsFortranOrder() = assertRejects(
        npz(mapOf("null_spk" to npy(*goodSpk, fortran = true), "null_style" to npy(*goodStyle))), mustContain = "fortran",
    )

    @Test
    fun rejectsNaN() = assertRejects(
        npz(mapOf("null_spk" to npy(*FloatArray(192) { if (it == 5) Float.NaN else 0.1f }), "null_style" to npy(*goodStyle))),
        mustContain = "NaN",
    )

    @Test
    fun rejectsBadFps() {
        val ok = npz(mapOf("null_spk" to npy(*goodSpk), "null_style" to npy(*goodStyle)))
        assertRejects(ok, fps = 0.0, mustContain = "flow_fps")
        assertRejects(ok, fps = Double.NaN, mustContain = "flow_fps")
    }
}
