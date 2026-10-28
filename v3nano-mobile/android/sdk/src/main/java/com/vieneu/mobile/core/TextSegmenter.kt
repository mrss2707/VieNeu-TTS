package com.vieneu.mobile.core

/**
 * Port structural của `normalize_to_chunks_v3_with_gaps` (Sprint 2b).
 *
 * Nguồn sự thật Python:
 * - `vieneu_utils.core_utils.split_into_sentences` / `pack_sentences_into_chunks` / `_classify_gap`
 * - `vieneu_utils.phonemize_text.normalize_to_chunks_v3_with_gaps` / `_merge_short_chunks` / `_effective_len`
 *
 * **Giới hạn đã chốt (đừng mở lại khi không có dữ kiện mới):** bước normalize đầy đủ
 * (số, abbreviation, email…) + G2P nằm trong `sea_g2p` (Rust `sea_g2p_rs.abi3.so`)
 * — **không** port sang Kotlin trong Sprint 2b. SDK nhận text đã normalize/phonemize
 * qua [TextNormalizer] / chuỗi phones, hoặc dùng MiniNormalizer (lowercase + quote
 * strip + punc cleanup) cho demo.
 *
 * Golden lock: `v3nano-mobile/eval/golden/chunk_split_structural.json`
 * (sinh từ component Python — identity normalize).
 */
object TextSegmenter {

    data class Chunks(val chunks: List<String>, val gaps: List<String>) {
        val size: Int get() = chunks.size
    }

    // ── Regex / bảng — khớp core_utils.py + phonemize_text.py ──────────────────

    private val RE_NEWLINE = Regex("[\\r\\n]+")
    private val RE_MINOR_PUNCT = Regex("(?<=[,;:\\-–—])\\s+")
    private val RE_TOKEN_KEEP_EN = Regex("<en>.*?</en>|\\S+", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val RE_EMOTION_TOKEN = Regex("<\\|emotion_\\d+\\|>")

    private val OPEN_TO_CLOSE = mapOf(
        '(' to ')', '[' to ']', '{' to '}',
        '“' to '”', '‘' to '’', '«' to '»', '‹' to '›', '「' to '」', '『' to '』',
    )
    private val OPENERS = OPEN_TO_CLOSE.keys
    private val CLOSERS = OPEN_TO_CLOSE.values.toSet()
    private const val SYMMETRIC_QUOTE = '"'
    private val SENT_END_CHARS = setOf('.', '!', '?', '…')
    private val TRAILING_CLOSE = CLOSERS + setOf('"', '\'', '’', '”')

    private val CONN_WORDS = setOf(
        "và", "nhưng", "hoặc", "song", "rồi", "nên", "vì", "nếu", "khi", "để", "do", "bởi",
    )
    private val CONN_PAIRS = setOf(
        "sau" to "khi", "trước" to "khi", "trong" to "khi", "mỗi" to "khi",
        "đến" to "khi", "tới" to "khi", "cho" to "nên", "cho" to "đến",
        "bởi" to "vì", "nếu" to "như", "tuy" to "nhiên", "thế" to "nhưng",
        "vì" to "vậy", "vì" to "thế", "do" to "đó", "sau" to "đó",
    )
    private const val CONN_STRIP = "\"'“”‘’()[]«»…"
    private const val CHUNK_TAIL_SLACK = 15

    private val NUMBER_WORDS = setOf(
        "không", "một", "mốt", "hai", "ba", "bốn", "tư", "năm", "lăm", "sáu", "bảy", "tám",
        "chín", "mười", "mươi", "trăm", "nghìn", "ngàn", "triệu", "tỷ", "tỉ", "linh", "lẻ",
        "phẩy", "chấm",
    )

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * `normalize_to_chunks_v3_with_gaps` — trả `(chunks, gaps)` với
     * `gaps[i] ∈ {para, sentence, minor}` giữa `chunks[i]` và `chunks[i+1]`.
     */
    fun segmentWithGaps(
        text: String,
        maxChars: Int = 140,
        minChunkChars: Int = 20,
        normalizer: TextNormalizer = TextNormalizer.identity,
    ): Chunks {
        if (text.isEmpty()) return Chunks(emptyList(), emptyList())
        val keepCues = text.contains('[') || text.contains("<|emotion_")
        val chunks = ArrayList<String>()
        val gaps = ArrayList<String>()
        for (sentences in normalizedSentencesByPara(text, keepCues, normalizer)) {
            val paraChunks = packSentencesIntoChunks(sentences, maxChars)
            if (paraChunks.isEmpty()) continue
            if (chunks.isNotEmpty()) gaps.add("para")
            for (j in paraChunks.indices) {
                if (j > 0) gaps.add("sentence")
                chunks.add(paraChunks[j])
            }
        }
        val normed = chunks.map { normalizer.puncNorm(it) }
        val reGaps = gaps.mapIndexed { i, g -> if (g == "para") g else classifyGap(normed[i]) }
        return mergeShortChunks(normed, reGaps, minChunkChars)
    }

    /** Chỉ tách câu (quote-aware) — dùng khi caller tự pack. */
    fun splitIntoSentences(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val (sentences, balanced) = scanSentences(text, quoteAware = true)
        return if (!balanced) scanSentences(text, quoteAware = false).first else sentences
    }

    /** Port `pack_sentences_into_chunks` — greedy, cắt câu dài theo minor-punct rồi balanced-cut. */
    fun packSentencesIntoChunks(sentences: List<String>, maxChars: Int = 256): List<String> {
        val finalChunks = ArrayList<String>()
        var buffer = ""
        for (raw in sentences) {
            val sentence = raw.trim()
            if (sentence.isEmpty()) continue
            if (sentence.length > maxChars) {
                if (buffer.isNotEmpty()) {
                    finalChunks.add(buffer)
                    buffer = ""
                }
                for (part in RE_MINOR_PUNCT.split(sentence)) {
                    val p = part.trim()
                    if (p.isEmpty()) continue
                    if (fits(buffer.length, p.length, maxChars)) {
                        buffer = if (buffer.isEmpty()) p else "$buffer $p"
                    } else {
                        if (buffer.isNotEmpty()) finalChunks.add(buffer)
                        buffer = p
                        if (buffer.length > maxChars) {
                            val pieces = splitLongPart(buffer, maxChars)
                            if (pieces.size > 1) finalChunks.addAll(pieces.subList(0, pieces.size - 1))
                            buffer = pieces.lastOrNull() ?: ""
                        }
                    }
                }
            } else {
                if (buffer.isNotEmpty() && !fits(buffer.length, sentence.length, maxChars)) {
                    finalChunks.add(buffer)
                    buffer = sentence
                } else {
                    buffer = if (buffer.isEmpty()) sentence else "$buffer $sentence"
                }
            }
        }
        if (buffer.isNotEmpty()) finalChunks.add(buffer)
        return finalChunks.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Port `punc_norm` (sea-g2p rule, doc trong phonemize_text.py):
     * câu < 5 từ ép dấu cuối về `.`; câu dài thêm `.` nếu chưa kết thúc `, . ! ?`.
     */
    fun puncNorm(chunk: String): String {
        val c = chunk.trim()
        if (c.isEmpty()) return c
        val words = c.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return if (words.size < 5) {
            c.trimEnd('.', ',', '!', '?', ';', ':', '…') + "."
        } else if (c.last() !in ",.!?") {
            "$c."
        } else {
            c
        }
    }

    /** Port `_classify_gap`: `.!?` → sentence; còn lại → minor. */
    fun classifyGap(chunk: String): String {
        val c = chunk.trimEnd()
        return if (c.isNotEmpty() && c.last() in ".!?") "sentence" else "minor"
    }

    /** Port `_effective_len`: emotion token không tính vào độ dài. */
    fun effectiveLen(chunk: String): Int = RE_EMOTION_TOKEN.replace(chunk, "").trim().length

    // ── Sentence scan (core_utils._scan_sentences) ─────────────────────────────

    private fun scanSentences(text: String, quoteAware: Boolean): Pair<List<String>, Boolean> {
        val sentences = ArrayList<String>()
        val n = text.length
        var start = 0
        var i = 0
        var depth = 0
        var inQuote = false
        while (i < n) {
            val ch = text[i]
            if (quoteAware && ch == SYMMETRIC_QUOTE) {
                inQuote = !inQuote
                i++
            } else if (quoteAware && ch in OPENERS) {
                depth++
                i++
            } else if (quoteAware && ch in CLOSERS) {
                if (depth > 0) depth--
                i++
            } else if (ch in SENT_END_CHARS && depth == 0 && !inQuote) {
                var j = i + 1
                while (j < n && text[j] in SENT_END_CHARS) j++
                while (j < n && text[j] in TRAILING_CLOSE) j++
                if (j >= n || text[j].isWhitespace()) {
                    sentences.add(text.substring(start, j))
                    start = j
                    i = j
                    continue
                }
                i = j
            } else {
                i++
            }
        }
        if (start < n) sentences.add(text.substring(start))
        val balanced = depth == 0 && !inQuote
        return Pair(sentences.map { it.trim() }.filter { it.isNotEmpty() }, balanced)
    }

    // ── Long-part split (core_utils._split_long_part + helpers) ────────────────

    private fun connKey(token: String): String =
        token.trim { it in CONN_STRIP }.lowercase()

    private fun tailSlack(maxChars: Int): Int = minOf(CHUNK_TAIL_SLACK, maxChars / 8)

    private fun fits(curLen: Int, addLen: Int, maxChars: Int): Boolean {
        val total = if (curLen == 0) addLen else curLen + 1 + addLen
        val slack = tailSlack(maxChars)
        return total <= maxChars || (addLen <= slack && total <= maxChars + slack)
    }

    private fun isNumberWord(token: String): Boolean {
        val key = connKey(token)
        return key in NUMBER_WORDS || key.all { it.isDigit() }
    }

    private fun tokenizeKeepEn(s: String): List<String> =
        RE_TOKEN_KEEP_EN.findAll(s).map { it.value }.toList()

    private fun spanLen(words: List<String>, start: Int, end: Int): Int {
        if (end <= start) return 0
        var sum = 0
        for (i in start until end) sum += words[i].length
        return sum + (end - start - 1)
    }

    private fun balancedCut(
        words: List<String>,
        start: Int,
        target: Double,
        maxChars: Int,
        minLeft: Int,
    ): Int {
        var bestNat: Pair<Double, Int>? = null
        var bestPlain: Pair<Double, Int>? = null
        var endCap = start + 1
        var j = start + 1
        while (j < words.size) {
            val left = spanLen(words, start, j)
            if (left > maxChars) break
            endCap = j
            val key = connKey(words[j])
            val prev = connKey(words[j - 1])
            val nxt = if (j + 1 < words.size) connKey(words[j + 1]) else ""
            val d = kotlin.math.abs(left - target)
            val insidePair = (prev to key) in CONN_PAIRS
            val natural = (key in CONN_WORDS || (key to nxt) in CONN_PAIRS) && prev !in CONN_WORDS
            if (natural && !insidePair && left >= minLeft && (bestNat == null || d < bestNat.first)) {
                bestNat = Pair(d, j)
            }
            val plainOk = !insidePair && !(isNumberWord(words[j - 1]) && isNumberWord(words[j]))
            if (plainOk && (bestPlain == null || d < bestPlain.first)) {
                bestPlain = Pair(d, j)
            }
            j++
        }
        bestNat?.let { return it.second }
        bestPlain?.let { return it.second }
        var end = endCap
        while (end > start + 1 && (connKey(words[end - 1]) to connKey(words[end])) in CONN_PAIRS) {
            end--
        }
        return end
    }

    private fun splitLongPart(part: String, maxChars: Int): List<String> {
        val words = tokenizeKeepEn(part)
        val minLeft = maxChars / 3
        val pieces = ArrayList<String>()
        var start = 0
        while (start < words.size) {
            val rest = spanLen(words, start, words.size)
            if (rest <= maxChars) {
                pieces.add(words.subList(start, words.size).joinToString(" "))
                break
            }
            var full = start + 1
            while (full < words.size && spanLen(words, start, full + 1) <= maxChars) full++
            if (fits(spanLen(words, start, full), spanLen(words, full, words.size), maxChars)) {
                pieces.add(words.subList(start, words.size).joinToString(" "))
                break
            }
            // ceil nguyên: `-(-rest / maxChars)` của Python dựa vào chia SÀN; Kotlin chia
            // nguyên cắt về 0 nên cùng biểu thức đó ra thiếu một mảnh (305/140 → 2 thay vì 3).
            val nPieces = (rest + maxChars - 1) / maxChars
            val end = balancedCut(words, start, rest.toDouble() / nPieces, maxChars, minLeft)
            pieces.add(words.subList(start, end).joinToString(" "))
            start = end
        }
        return pieces
    }

    // ── merge short chunks (phonemize_text._merge_short_chunks) ────────────────

    private fun mergeShortChunks(
        chunksIn: List<String>,
        gapsIn: List<String>,
        minChars: Int,
    ): Chunks {
        val chunks = chunksIn.toMutableList()
        val gaps = gapsIn.toMutableList()
        while (chunks.size > 1) {
            val short = chunks.indices.filter { effectiveLen(chunks[it]) < minChars }
            if (short.isEmpty()) break
            val i = short.minBy { effectiveLen(chunks[it]) }
            data class Side(val prio: Pair<Boolean, Int>, val name: String)
            val sides = ArrayList<Side>()
            if (i < chunks.size - 1) {
                sides.add(Side(Pair(gaps[i] != "para", -chunks[i + 1].length), "R"))
            }
            if (i > 0) {
                sides.add(Side(Pair(gaps[i - 1] != "para", -chunks[i - 1].length), "L"))
            }
            // Python: max(sides, key=lambda t: t[0]) với t[0]=(gaps[i]!="para", -len)
            val side = sides.maxWith(
                compareBy({ if (it.prio.first) 1 else 0 }, { it.prio.second }),
            ).name
            if (side == "R") {
                chunks[i] = chunks[i] + " " + chunks.removeAt(i + 1)
                gaps.removeAt(i)
            } else {
                chunks[i - 1] = chunks[i - 1] + " " + chunks.removeAt(i)
                gaps.removeAt(i - 1)
            }
        }
        return Chunks(chunks, gaps)
    }

    // ── normalized sentences by para ───────────────────────────────────────────

    /**
     * Tách câu TRƯỚC, normalize SAU (thứ tự cố ý — xem `_normalized_sentences_by_para`).
     * `punc_norm` KHÔNG bật ở tầng câu — dấu cuối chốt một lần ở tầng chunk.
     */
    fun normalizedSentencesByPara(
        text: String,
        keepCues: Boolean = false,
        normalizer: TextNormalizer = TextNormalizer.identity,
    ): List<List<String>> {
        val paragraphs = RE_NEWLINE.split(text).filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) return emptyList()
        val out = ArrayList<List<String>>()
        for (para in paragraphs) {
            val sentences = splitIntoSentences(para)
            if (sentences.isEmpty()) continue
            out.add(
                sentences.map { s ->
                    if (keepCues) normalizer.normalizeKeepCues(s) else normalizer.normalize(s)
                },
            )
        }
        return out
    }
}
