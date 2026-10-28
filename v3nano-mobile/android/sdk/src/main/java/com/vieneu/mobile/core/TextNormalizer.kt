package com.vieneu.mobile.core

/**
 * Pluggable normalize cho [TextSegmenter].
 *
 * **Giới hạn Sprint 2b:** normalize đầy đủ + G2P của VieNeu nằm trong `sea_g2p`
 * (Rust extension) — không port bit-exact sang Kotlin. SDK production nhận text
 * đã normalize/phonemize (host export / `enqueuePhones`), hoặc dùng
 * [mini] cho demo / text đã gần chuẩn.
 */
interface TextNormalizer {
    /** Normalize MỘT câu, `punc_norm=False` (dấu cuối chốt ở tầng chunk). */
    fun normalize(sentence: String): String

    /** Normalize giữ inline emotion cue `[cười]` → `<|emotion_1|>`. */
    fun normalizeKeepCues(sentence: String): String

    /**
     * Chốt dấu câu cuối cho MỘT chunk. Mặc định = bản port Kotlin [TextSegmenter.puncNorm]
     * (golden structural); normalizer dựa trên sea-g2p override bằng chính luật của lõi Rust,
     * vì luật đó đổi theo phiên bản (0.10.0 thêm: đoạn < 3 từ giữa chuỗi đổi "." thành ",").
     */
    fun puncNorm(chunk: String): String = TextSegmenter.puncNorm(chunk)

    companion object {
        /** Identity — text đã normalize sẵn (golden structural / host pipeline). */
        val identity: TextNormalizer = object : TextNormalizer {
            override fun normalize(sentence: String): String = sentence
            override fun normalizeKeepCues(sentence: String): String = sentence
        }

        /**
         * MiniNormalizer: lowercase + quote/bracket strip + multi-punct collapse
         * + ellipsis→comma + emotion cue map. KHÔNG expand số/abbreviation/email
         * (sea-g2p) — host-side nếu cần parity full golden.
         */
        val mini: TextNormalizer = MiniTextNormalizer
    }
}

/**
 * Bản thu gọn của sea-g2p Normalizer cho các sửa dấu/quote phổ biến.
 * Không thay thế sea-g2p — xem [TextNormalizer].
 */
object MiniTextNormalizer : TextNormalizer {

    private val EMOTION_TAG_TO_K = mapOf(
        "chuckle" to 1, "cười" to 1, "cuoi" to 1,
        "sigh" to 2, "thở dài" to 2, "tho dai" to 2,
        "clear throat" to 3, "hắng giọng" to 3, "hang giong" to 3,
    )
    private val RE_EMOTION_SPLIT = Regex("(\\[[^\\]]+\\]|<\\|emotion_\\d+\\|>)")
    private val ATTACHING_PUNCT = setOf('.', ',', '!', '?', ';', ':', '…', ')', ']', '}', '"', '\'', '’', '”')

    override fun normalize(sentence: String): String {
        var s = sentence
        // ellipsis → comma (sea-g2p: "Thật à..." → "thật à,")
        s = s.replace(Regex("\\.{3,}|…+"), ",")
        // multi-punct collapse: "???" → "?", "?!" → "?", "!!" → "!"
        s = s.replace(Regex("([.!?])\\s*[.!?]+"), "$1")
        // quote / bracket strip → comma when attached punctuation style
        s = s.replace(Regex("[\"“”‘’«»‹›「」『』]"), "")
        s = s.replace(Regex("\\s*:\\s*\"?\\s*"), ", ")
        s = s.replace(Regex("\\s+"), " ").trim()
        // drop leftover spaces before punct
        s = s.replace(Regex("\\s+([,.!?;:])"), "$1")
        // collapse ",," / ", ," leftover
        s = s.replace(Regex("(,\\s*)+"), ", ")
        return s.lowercase().trim()
    }

    override fun normalizeKeepCues(sentence: String): String {
        if ('[' !in sentence && "<|emotion_" !in sentence) return normalize(sentence)
        val sb = StringBuilder()
        val parts = RE_EMOTION_SPLIT.split(sentence)
        for ((i, part) in parts.withIndex()) {
            if (i % 2 == 1) {
                val tok = emotionTagToken(part) ?: part
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(tok)
            } else if (part.isNotBlank()) {
                val ph = normalize(part)
                if (ph.isEmpty()) continue
                if (sb.isEmpty()) sb.append(ph)
                else if (ph.first() in ATTACHING_PUNCT) sb.append(ph)
                else sb.append(' ').append(ph)
            }
        }
        return sb.toString()
    }

    private fun emotionTagToken(tag: String): String? {
        val t = tag.trim()
        if (t.startsWith("<|")) return t
        val inner = t.removeSurrounding("[", "]").trim().lowercase()
        val k = EMOTION_TAG_TO_K[inner] ?: return null
        return "<|emotion_$k|>"
    }
}
