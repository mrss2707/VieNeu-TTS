package com.vieneu.mobile.core

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DSP chạy trên TỪNG chunk (streaming) so với MỘT lần trên cả đoạn (demo native).
 * Đo xem khác biệt có nằm ở biên chunk hay không, và có đủ lớn để nghe thấy.
 */
class ChunkBoundaryDspTest {
    private val sr = 24_000

    /** "Tiếng nói" giả: các âm tiết 150 ms (biên độ đổi theo từng âm) cách nhau 40 ms im lặng, + nền nhiễu −60 dBFS. */
    private fun speechLike(seconds: Double, seed: Int): FloatArray {
        val rnd = java.util.Random(seed.toLong())
        val n = (seconds * sr).toInt()
        val out = FloatArray(n)
        var i = 0
        while (i < n) {
            val len = (0.150 * sr).toInt()
            val amp = 0.15f + rnd.nextFloat() * 0.45f
            val f = 140.0 + rnd.nextInt(180)
            for (k in 0 until len) {
                if (i + k >= n) break
                val env = sin(Math.PI * k / len).toFloat()
                out[i + k] = amp * env * sin(2 * Math.PI * f * k / sr).toFloat() +
                    0.5f * amp * env * sin(2 * Math.PI * f * 3 * k / sr).toFloat()
            }
            i += len + (0.040 * sr).toInt()
        }
        for (k in out.indices) out[k] += (rnd.nextGaussian() * 0.001).toFloat()
        return out
    }

    private fun rms(a: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until to) s += a[i].toDouble() * a[i]
        return sqrt(s / max(1, to - from))
    }

    @Test
    fun perChunkVsWholeAtTheBoundaryAndPeakNormalisation() {
        // 4 chunk 3 s, mỗi chunk có độ to khác nhau (model sinh từng chunk với mức ra khác nhau)
        val gain = floatArrayOf(1.0f, 0.55f, 0.8f, 0.4f)
        val chunks = gain.mapIndexed { idx, g -> speechLike(3.0, idx + 1).also { c -> for (i in c.indices) c[i] *= g } }

        val whole = AudioComfortFilter.apply(chunks.reduce { a, b -> a + b }, noiseFloorEnabled = true, spectralEnabled = true)
        val per = chunks.map { AudioComfortFilter.apply(it.copyOf(), noiseFloorEnabled = true, spectralEnabled = true) }

        // 1) Mức to nhỏ: DSP từng chunk peak-normalise RIÊNG → chunk nhỏ bị kéo to bằng chunk to.
        val perPeaks = per.map { c -> c.maxOf { abs(it) } }
        val wholeChunkPeaks = (0 until 4).map { k -> (k * 3 * sr until (k + 1) * 3 * sr).maxOf { abs(whole[it]) } }
        println("PEAK per-chunk  : ${perPeaks.map { "%.3f".format(it) }}")
        println("PEAK whole-pass : ${wholeChunkPeaks.map { "%.3f".format(it) }}")
        val perSpread = perPeaks.max() / perPeaks.min()
        val wholeSpread = wholeChunkPeaks.max() / wholeChunkPeaks.min()
        println("PEAK spread max/min: per-chunk=%.2f whole=%.2f".format(perSpread, wholeSpread))

        // 2) Nhảy mức RMS ngay tại biên (100 ms hai bên): người nghe cảm nhận như "giật" âm lượng.
        val win = (0.100 * sr).toInt()
        var worstJumpDb = 0.0
        val joined = per.reduce { a, b -> a + b }
        for (k in 1 until 4) {
            val b = k * 3 * sr
            val left = rms(joined, b - win, b)
            val right = rms(joined, b, b + win)
            val jump = 20 * Math.log10(max(right, 1e-9) / max(left, 1e-9))
            println("BOUNDARY $k: rms left=%.4f right=%.4f jump=%.1f dB".format(left, right, jump))
            worstJumpDb = max(worstJumpDb, abs(jump))
        }
        // 3) Độ khác biệt tổng thể giữa hai cách chạy
        var diff = 0.0; var ref = 0.0
        for (i in whole.indices) { diff += (whole[i] - joined[i]).toDouble().let { it * it }; ref += whole[i].toDouble() * whole[i] }
        println("RELATIVE DIFF whole vs per-chunk = %.1f%%".format(100 * sqrt(diff / ref)))
        assertTrue(perPeaks.all { !it.isNaN() })
    }
}
