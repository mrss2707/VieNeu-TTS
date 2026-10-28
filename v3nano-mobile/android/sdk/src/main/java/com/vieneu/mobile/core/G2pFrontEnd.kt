package com.vieneu.mobile.core

/**
 * Bề mặt tối thiểu của lõi sea-g2p (Rust) mà SDK cần — implement bằng JNI ở module
 * `sdk-g2p`, bằng fake ở test. Core giữ thuần JVM, không biết gì về native.
 *
 * Mọi hàm nhận/trả UTF-8 text; ném [IllegalStateException] khi native báo lỗi.
 */
interface G2pBackend : AutoCloseable {
    /** Normalize (số, ngày, đơn vị, viết tắt...). [puncNorm] = chốt dấu cuối câu. */
    fun normalize(text: String, puncNorm: Boolean): String

    /** Normalize rồi G2P → chuỗi phoneme (đúng `SEAPipeline.run` của bản Python). */
    fun phonemize(text: String, puncNorm: Boolean): String

    /** Chốt dấu câu cuối bằng đúng luật `punc_norm` của sea-g2p (không normalize lại). */
    fun puncNorm(text: String): String

    override fun close() {}
}

/** Emotion cue inline `[cười]` / `<|emotion_k|>` — port `phonemize_text.py`. */
object EmotionCues {
    private val SPLIT = Regex("(\\[[^\\]]+\\]|<\\|emotion_\\d+\\|>)")
    private val TAG_TO_K = mapOf(
        "chuckle" to 1, "cười" to 1, "cuoi" to 1,
        "sigh" to 2, "thở dài" to 2, "tho dai" to 2,
        "clear throat" to 3, "hắng giọng" to 3, "hang giong" to 3,
    )

    /** Dấu câu bám vào token trước (không thêm space) — khớp phones lúc train. */
    val ATTACHING_PUNCT: Set<Char> = ".,!?;:…)]}\"'’”".toSet()

    fun hasCue(text: String): Boolean = text.contains('[') || text.contains("<|emotion_")

    /**
     * Tách giống `re.split` có capture group của Python: phần tử chẵn là text,
     * phần tử lẻ là cue (`[...]` hoặc `<|emotion_k|>`). Kotlin `Regex.split` KHÔNG giữ
     * delimiter nên không dùng được.
     */
    fun splitKeepingCues(text: String): List<String> {
        val parts = ArrayList<String>()
        var last = 0
        for (m in SPLIT.findAll(text)) {
            parts.add(text.substring(last, m.range.first))
            parts.add(m.value)
            last = m.range.last + 1
        }
        parts.add(text.substring(last))
        return parts
    }

    /** `[cười]` → `<|emotion_1|>`; `<|...|>` giữ nguyên; tag lạ → null. */
    fun tagToken(tag: String): String? {
        val t = tag.trim()
        if (t.startsWith("<|")) return t
        val inner = t.removePrefix("[").removeSuffix("]").trim().lowercase()
        val k = TAG_TO_K[inner] ?: return null
        return "<|emotion_$k|>"
    }
}

/**
 * Normalizer cho [TextSegmenter] dựa trên lõi sea-g2p — khớp
 * `_normalize_sentence_keep_cues` / `normalizer.normalize(punc_norm=False)` của Python.
 * `punc_norm` KHÔNG bật ở tầng câu (dấu cuối chốt một lần ở tầng chunk).
 */
class SeaTextNormalizer(private val backend: G2pBackend) : TextNormalizer {
    override fun normalize(sentence: String): String = backend.normalize(sentence, false)

    override fun puncNorm(chunk: String): String = backend.puncNorm(chunk)

    override fun normalizeKeepCues(sentence: String): String {
        if (!EmotionCues.hasCue(sentence)) return normalize(sentence)
        val out = ArrayList<String>()
        for ((i, part) in EmotionCues.splitKeepingCues(sentence).withIndex()) {
            if (i % 2 == 1) {
                out.add(EmotionCues.tagToken(part) ?: part)
            } else if (part.isNotBlank()) {
                out.add(backend.normalize(part, false))
            }
        }
        return out.filter { it.isNotEmpty() }.joinToString(" ")
    }
}

/**
 * Chunk text (đã tách + normalize bởi [TextSegmenter]) → chuỗi phoneme đưa vào
 * [PhoneEncoder]. Port `phonemize_text_with_emotions`: không có cue thì một lần gọi
 * `phonemize(punc_norm=true)`; có cue thì giữ `<|emotion_k|>` trong dòng phoneme.
 */
class ChunkPhonemizer(private val backend: G2pBackend) {
    fun phonemize(chunk: String): String {
        if (!EmotionCues.hasCue(chunk)) return backend.phonemize(chunk, true)
        var out = ""
        for ((i, part) in EmotionCues.splitKeepingCues(chunk).withIndex()) {
            if (i % 2 == 1) {
                val token = EmotionCues.tagToken(part)
                if (token != null) {
                    out = if (out.isEmpty()) token else "$out $token"
                    continue
                }
            }
            // Mảnh giữa các cue: KHÔNG ép punc_norm để khỏi chèn "." giữa câu.
            val ph = if (part.isNotBlank()) backend.phonemize(part, false) else ""
            if (ph.isEmpty()) continue
            out = when {
                out.isEmpty() -> ph
                ph[0] in EmotionCues.ATTACHING_PUNCT -> out + ph
                else -> "$out $ph"
            }
        }
        return backend.puncNorm(out)
    }
}
