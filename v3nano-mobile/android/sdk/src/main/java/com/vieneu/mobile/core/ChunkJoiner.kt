package com.vieneu.mobile.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Ghép chunk audio theo ranh giới — port từ NanoBenchmarkCore.kt:202-299, chính là port
 * của Python vieneu_utils.core_utils (edge_silence / pause_pad_samples / join_audio_chunks
 * nhánh silence_ps + bảng V3_GAP_SILENCE). Chỉ phần GHÉP được port — phần TÁCH chunk
 * (normalize_to_chunks_v3_with_gaps) thuộc Sprint 2b của kế hoạch 001-mvp.
 *
 * Không dùng android.util.Log: thông tin chẩn đoán đi qua [onEvent] (hàm listener) —
 * core thuần JVM, test được trên CI không cần emulator.
 */
object ChunkJoiner {
    const val EDGE_THRESH_DB = -45.0
    const val EDGE_WIN_S = 0.01

    /** Khoảng nghỉ TỐI THIỂU (giây) giữa hai chunk theo loại ranh giới (core_utils.py:76). */
    val V3_GAP_SILENCE: Map<String, Double> = mapOf(
        "para" to 0.70, "sentence" to 0.50, "minor" to 0.30,
    )

    /** Port edge_silence(): (lead, tail) số mẫu im lặng đầu/cuối [wav] — envelope
     * mean|x| cửa sổ [winS] dưới [threshDb]. Wav toàn im lặng -> (len, 0). */
    fun edgeSilence(
        wav: FloatArray,
        sr: Int,
        threshDb: Double = EDGE_THRESH_DB,
        winS: Double = EDGE_WIN_S,
    ): Pair<Int, Int> {
        val nSamp = wav.size
        val win = max(1, (winS * sr).toInt())
        val nWin = nSamp / win
        if (nWin == 0) return Pair(nSamp, 0)
        val threshLinear = 10.0.pow(threshDb / 20.0)
        val env = DoubleArray(nWin)
        for (w in 0 until nWin) {
            var sum = 0.0
            val base = w * win
            for (j in 0 until win) sum += abs(wav[base + j])
            env[w] = sum / win
        }
        var first = -1
        var last = -1
        for (w in 0 until nWin) {
            if (env[w] > threshLinear) {
                if (first == -1) first = w
                last = w
            }
        }
        if (first == -1) return Pair(nSamp, 0)
        return Pair(first * win, nSamp - (last + 1) * win)
    }

    /** Port pause_pad_samples(): số mẫu zeros cần chèn để khoảng nghỉ THẬT (im lặng đuôi
     * [prevWav] + zeros + im lặng đầu [nextWav]) đạt [pauseS]; 0 nếu đã đủ. */
    fun pausePadSamples(prevWav: FloatArray, nextWav: FloatArray, sr: Int, pauseS: Double): Int {
        val (leadPrev, tailRaw) = edgeSilence(prevWav, sr)
        val tail = if (leadPrev == prevWav.size) prevWav.size else tailRaw
        val (lead, _) = edgeSilence(nextWav, sr)
        return max(0, (pauseS * sr).toInt() - tail - lead)
    }

    /** Port join_audio_chunks(..., silence_ps=...) — nhánh v3. [gaps][i] map qua
     * [V3_GAP_SILENCE]; khoá lạ -> mặc định "sentence" (khớp gaps_to_silence Python),
     * có thông báo một lần qua [onEvent]. Audio từng chunk giữ NGUYÊN (không trim). */
    fun joinAudioChunks(
        chunks: List<FloatArray>,
        sr: Int,
        gaps: List<String>,
        onEvent: ((String) -> Unit)? = null,
    ): FloatArray {
        if (chunks.isEmpty()) return FloatArray(0)
        if (chunks.size == 1) return chunks[0]
        var total = chunks[0].size
        val pads = IntArray(chunks.size - 1)
        for (i in 1 until chunks.size) {
            val gapType = gaps.getOrNull(i - 1)
            val pauseS = V3_GAP_SILENCE[gapType] ?: run {
                if (gapType != null) onEvent?.invoke("unknown gap type '$gapType' -> sentence pause")
                V3_GAP_SILENCE.getValue("sentence")
            }
            val pad = pausePadSamples(chunks[i - 1], chunks[i], sr, pauseS)
            pads[i - 1] = pad
            total += pad + chunks[i].size
        }
        val out = FloatArray(total)
        var pos = chunks[0].size
        System.arraycopy(chunks[0], 0, out, 0, chunks[0].size)
        for (i in 1 until chunks.size) {
            pos += pads[i - 1]  // zeros đã được khởi tạo sẵn (default 0f)
            System.arraycopy(chunks[i], 0, out, pos, chunks[i].size)
            pos += chunks[i].size
        }
        return out
    }
}