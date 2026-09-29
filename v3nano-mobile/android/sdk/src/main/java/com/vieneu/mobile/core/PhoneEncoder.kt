package com.vieneu.mobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Mã hoá chuỗi phoneme -> ids [bos, ..., eos] — port v3nano.py:227-243 (encode_phones):
 * (1) thay mọi tag emotion (<|emotion_k|> -> ①…) TRƯỚC khi tra; (2) ký tự ngoài vocab bị
 * DROP kèm cảnh báo MỘT LẦN mỗi ký tự (không fail cả request); (3) bos/eos luôn nằm đầu/cuối.
 *
 * Chỉ tra ký tự đơn (UTF-16 char): vocab của model có đúng 3 khoá dài hơn 1 ký tự là
 * <pad>/<bos>/<eos> (đã kiểm chứng trên config.json của revision pin), mọi ký tự phoneme
 * đều BMP — nên việc lặp char không khác Python lặp code point.
 *
 * Nạp từ assets/tokenizer/tokenizer.json của package mobile P1 (schema v1, xem
 * v3nano-mobile/host/build_mobile_package.py — open question 1 của plan đã chốt naming này).
 */
class PhoneEncoder(
    private val emotionMap: Map<String, String>,
    private val vocab: Map<String, Int>,
    val bos: Int,
    val eos: Int,
    val pad: Int,
) {
    private val oovWarned = HashSet<String>()

    /**
     * Phones (có thể chứa <|emotion_k|>) -> LongArray ids [1, L] (flat — khớp golden
     * phone_ids.json của v3nano-mobile/host/export_golden_contract.py).
     * @param onOov callback nhận ký tự ngoài vocab (lần ĐẦU gặp) — thay vai trò logger
     * warning của Python, giữ core không phụ thuộc logging framework.
     */
    fun encode(phones: String, onOov: ((String) -> Unit)? = null): LongArray {
        var s = phones
        for ((tag, ch) in emotionMap) {
            s = s.replace(tag, ch)
        }
        val ids = ArrayList<Int>(s.length + 2)
        ids.add(bos)
        for (ch in s) {
            val key = ch.toString()
            val idx = vocab[key]
            if (idx == null) {
                if (oovWarned.add(key)) onOov?.invoke(key)
            } else {
                ids.add(idx)
            }
        }
        ids.add(eos)
        return LongArray(ids.size) { i -> ids[i].toLong() }
    }

    companion object {
        /** Tokenizer file schema v1 (build_mobile_package.py) — trường thừa được bỏ qua
         * để revision sau thêm field không vỡ parser (03-model-package.md §3.6). */
        @Serializable
        data class TokenizerFile(
            val schema_version: String = "1",
            val bos_id: Int = 1,
            val eos_id: Int = 2,
            val pad_id: Int = 0,
            val emotion_tags: Map<String, String> = emptyMap(),
            val vocab: Map<String, Int> = emptyMap(),
        )

        fun fromTokenizerJson(jsonText: String): PhoneEncoder {
            val tok = Json { ignoreUnknownKeys = true }.decodeFromString<TokenizerFile>(jsonText)
            return PhoneEncoder(tok.emotion_tags, tok.vocab, tok.bos_id, tok.eos_id, tok.pad_id)
        }
    }
}