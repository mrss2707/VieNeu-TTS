package com.vieneu.mobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Index giọng của gói model (`assets/voices/voices_v3_nano_mobile.json`).
 *
 * Đường dẫn file trong index (`voices/<id>/speaker_emb.bin`) tính từ thư mục `assets/`
 * — CHA của thư mục chứa index. Nối vào thư mục chứa index ra `assets/voices/voices/...`
 * (không tồn tại); host từng đọc thiếu file thành vector 0 và model tự chọn giọng → lẫn nam/nữ.
 * Vì vậy: thiếu file hoặc sai kích thước là LỖI, không bao giờ trả vector 0.
 */
class VoiceIndex private constructor(
    val voices: List<Entry>,
    val defaultVoiceId: String?,
) {
    data class Entry(
        val id: String,
        val name: String,
        val gender: String?,
        val werReference: Double?,
        val speakerEmbFile: File,
        val styleFile: File,
    )

    fun find(id: String): Entry? = voices.find { it.id == id }

    companion object {
        const val SPK_DIM = ModelConstants.SPK_DIM
        const val STYLE_DIM = ModelConstants.STYLE_DIM

        /** [indexFile] = voices_v3_nano_mobile.json. Ném [IllegalArgumentException] nếu index hỏng. */
        fun load(indexFile: File): VoiceIndex {
            val assetsDir = indexFile.parentFile?.parentFile
                ?: throw IllegalArgumentException("Index giọng phải nằm trong assets/voices/: $indexFile")
            val root = Json.parseToJsonElement(indexFile.readText()).jsonObject
            val out = ArrayList<Entry>()
            for (el in root["voices"]?.jsonArray ?: throw IllegalArgumentException("Index giọng thiếu 'voices'")) {
                val v = el.jsonObject
                val id = v.str("voice_id") ?: v.str("id") ?: continue
                if (id.isBlank()) continue
                val files = v["files"]?.jsonObject
                val spk = files?.str("speaker_emb") ?: throw IllegalArgumentException("Giọng $id thiếu files.speaker_emb")
                val sty = files.str("style") ?: throw IllegalArgumentException("Giọng $id thiếu files.style")
                out.add(
                    Entry(
                        id = id,
                        name = v.str("display_name") ?: v.str("name") ?: id,
                        gender = v.str("gender"),
                        werReference = v["wer_reference"]?.jsonPrimitive?.doubleOrNull,
                        speakerEmbFile = File(assetsDir, spk),
                        styleFile = File(assetsDir, sty),
                    ),
                )
            }
            return VoiceIndex(out, root.str("default_voice_id")?.takeIf { it.isNotBlank() } ?: out.firstOrNull()?.id)
        }

        private fun JsonObject.str(key: String): String? =
            this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it != "null" }

        /** Đọc float32 little-endian đúng [dim] phần tử; thiếu file / sai kích thước / NaN → ném. */
        fun readEmbedding(file: File, dim: Int): FloatArray {
            require(file.isFile) { "Không tìm thấy file giọng: ${file.path}" }
            val bytes = file.readBytes()
            require(bytes.size == dim * 4) { "File giọng ${file.name} có ${bytes.size} byte, cần ${dim * 4}" }
            val out = FloatArray(dim)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
            require(out.all { it.isFinite() }) { "File giọng ${file.name} chứa NaN/Inf" }
            require(out.any { it != 0f }) { "File giọng ${file.name} toàn số 0 — không phải giọng hợp lệ" }
            return out
        }
    }
}
