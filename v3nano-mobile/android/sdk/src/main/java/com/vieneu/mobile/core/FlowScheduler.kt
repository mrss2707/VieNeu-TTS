package com.vieneu.mobile.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Port v3nano.py:262-269 (OnnxV3NanoEngine.infer): secs = min(exp(log_s)/max(speed,1e-3), 15.0),
 * T = max(MIN_FRAMES=2, round(secs*fps)), và lịch trình Euler warped
 * tg = u + sway*(cos(pi/2*u) - 1 + u) — sway KHÔNG phải no-op khi khác 0, phải áp cho
 * CẢ t lẫn dt (không truyền thẳng u).
 *
 * Ghi chú khác biệt có chủ đích với Python: [frames] dùng Math.round (round-half-up) như
 * harness Android hiện tại; Python round() là banker's rounding. Khác nhau chỉ ở đúng
 * ranh giới x.5 frames (hiếm, ±1 frame) — KHÔNG sửa để giữ output harness tương đương
 * trước/sau refactor; bất kỳ ai port chính xác numpy phải ghi rõ quyết định này.
 */
object FlowScheduler {
    const val MAX_CHUNK_SECONDS = 15.0
    const val MIN_FRAMES = 2

    /** secs = min(exp(logS) / max(speed, 1e-3), MAX_CHUNK_SECONDS) — v3nano.py:262 */
    fun durationSeconds(logS: Double, speed: Double = 1.0): Double =
        min(exp(logS) / max(speed, 1e-3), MAX_CHUNK_SECONDS)

    /** T = max(MIN_FRAMES, round(secs * fps)) — v3nano.py:263 */
    fun frames(secs: Double, fps: Double): Int = max(MIN_FRAMES, round(secs * fps).toInt())

    /** Lịch trình Euler warped cho [steps] bước với [sway] — v3nano.py:267-268. */
    class Schedule(val steps: Int, val u: DoubleArray, val tg: DoubleArray) {
        /** t[i] = float32(tg[i]) — giá trị đưa vào tensor t (v3nano.py:270) */
        val t: FloatArray
            get() = FloatArray(steps) { i -> tg[i].toFloat() }

        /** dt[i] = float32(tg[i+1] - tg[i]) — bước Euler (v3nano.py:277) */
        val dt: FloatArray
            get() = FloatArray(steps) { i -> (tg[i + 1] - tg[i]).toFloat() }
    }

    fun schedule(steps: Int, sway: Double): Schedule {
        val n = max(1, steps)
        val u = DoubleArray(n + 1) { i -> i.toDouble() / n }
        val tg = DoubleArray(n + 1) { i -> u[i] + sway * (cos(PI / 2.0 * u[i]) - 1.0 + u[i]) }
        return Schedule(n, u, tg)
    }
}