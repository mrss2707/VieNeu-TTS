package com.vieneu.mobile.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Hằng số điều kiện của model, nạp từ gói thay vì mặc định trong code.
 *
 * `null_spk` / `null_style` là nhánh KHÔNG điều kiện của CFG: nếu là số 0 thì CFG đẩy sai hướng
 * và giọng không còn bị ép vào speaker đã chọn (nghe lẫn nam/nữ). `flow_fps` quyết định số
 * frame từ thời lượng dự đoán. Cả ba PHẢI lấy từ gói — engine Python và demo đã validate cũng vậy.
 *
 * Nguồn: `assets/constants.npz` (zip không nén, mỗi mảng là .npy v1.0 float32 little-endian)
 * và `assets/tokenizer/tokenizer.json` (`flow_fps`).
 */
class ModelConstants(
    val nullSpk: FloatArray,
    val nullStyle: FloatArray,
    val flowFps: Double,
) {
    companion object {
        const val SPK_DIM = 192
        const val STYLE_DIM = 50 * 256

        /** Đọc [npz] (constants.npz) + [flowFps]. Ném [IllegalArgumentException] nếu thiếu/sai định dạng. */
        fun load(npz: File, flowFps: Double): ModelConstants {
            require(flowFps > 0.0 && flowFps.isFinite()) { "flow_fps không hợp lệ: $flowFps" }
            ZipFile(npz).use { zip ->
                fun array(name: String, expected: Int): FloatArray {
                    val entry = zip.getEntry("$name.npy")
                        ?: throw IllegalArgumentException("constants.npz thiếu $name.npy")
                    val arr = zip.getInputStream(entry).use { parseNpyFloat32(it.readBytes(), name) }
                    require(arr.size == expected) { "$name có ${arr.size} phần tử, cần $expected" }
                    require(arr.all { it.isFinite() }) { "$name chứa NaN/Inf" }
                    return arr
                }
                return ModelConstants(array("null_spk", SPK_DIM), array("null_style", STYLE_DIM), flowFps)
            }
        }

        /** .npy v1.0/2.0: magic, version, header_len, header ASCII, dữ liệu. Chỉ chấp nhận `<f4` C-order. */
        internal fun parseNpyFloat32(bytes: ByteArray, label: String): FloatArray {
            require(bytes.size > 10 && bytes[0] == 0x93.toByte() && String(bytes, 1, 5, Charsets.US_ASCII) == "NUMPY") {
                "$label không phải .npy"
            }
            val major = bytes[6].toInt()
            val headerLen: Int
            val headerStart: Int
            when (major) {
                1 -> { headerLen = ByteBuffer.wrap(bytes, 8, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF; headerStart = 10 }
                2, 3 -> { headerLen = ByteBuffer.wrap(bytes, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int; headerStart = 12 }
                else -> throw IllegalArgumentException("$label: .npy version $major chưa hỗ trợ")
            }
            val header = String(bytes, headerStart, headerLen, Charsets.US_ASCII)
            require("'<f4'" in header) { "$label: dtype phải là float32 little-endian, header=$header" }
            require("'fortran_order': False" in header) { "$label: không hỗ trợ fortran_order" }
            val dataStart = headerStart + headerLen
            val n = (bytes.size - dataStart) / 4
            require((bytes.size - dataStart) % 4 == 0) { "$label: dữ liệu không chia hết cho 4 byte" }
            val out = FloatArray(n)
            ByteBuffer.wrap(bytes, dataStart, n * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
            return out
        }
    }
}
