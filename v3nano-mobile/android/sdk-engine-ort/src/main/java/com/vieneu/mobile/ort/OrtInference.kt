package com.vieneu.mobile.ort

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import com.vieneu.mobile.core.DeterministicRng
import com.vieneu.mobile.core.FlowScheduler
import java.nio.FloatBuffer

/**
 * Một lượt flow-matching Euler — port NGUYÊN `NanoBenchmarkCore.runInference`
 * (parity đã lock vòng 2–8, golden `export_golden_contract.py`).
 *
 * `sway` warp lịch Euler: tg = u + sway*(cos(pi/2*u) - 1 + u) — KHÔNG phải no-op khi
 * khác 0, phải áp cho cả t và dt. Duration/RNG nằm ở SDK core (`com.vieneu.mobile.core.*`).
 *
 * Chỉ dùng ORT + gói `core` của SDK — KHÔNG `android.*` (giữ thuần để tái dùng cả harness).
 */
object OrtInference {

    fun run(
        g: OrtGraphs,
        env: OrtEnvironment,
        idsLong: LongArray,
        spk: FloatArray,
        style: FloatArray,
        nullSpk: FloatArray,
        nullStyle: FloatArray,
        padId: Long,
        bosId: Long,
        eosId: Long,
        fps: Double,
        steps: Int,
        cfg: Double,
        sway: Double = 0.0,
    ): Triple<FloatArray, Long, Long> {
        val L = idsLong.size
        val ids2d = arrayOf(idsLong)
        val idsTensor = OnnxTensor.createTensor(env, ids2d)
        val styleShape = longArrayOf(1, 50, 256)
        val styleTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(style), styleShape)
        val nullStyleTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(nullStyle), styleShape)
        val spkShape = longArrayOf(1, 192)
        val spkTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(spk), spkShape)
        val nullSpkTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(nullSpk), spkShape)
        // ctx_mask kiểu BOOL — khớp export (không đổi sang float/int).
        val mask = Array(1) { BooleanArray(L) { idx -> idsLong[idx] != padId } }
        val maskTensor = OnnxTensor.createTensor(env, mask)
        val nullIds = arrayOf(longArrayOf(bosId, eosId))
        val nullIdsTensor = OnnxTensor.createTensor(env, nullIds)
        val nullMask = Array(1) { BooleanArray(2) { true } }
        val nullMaskTensor = OnnxTensor.createTensor(env, nullMask)

        val t0 = System.currentTimeMillis()
        val ctxOut = g.text.run(mapOf("ids" to idsTensor, "style" to styleTensor))
        val ctxTensor = ctxOut[0] as OnnxTensor
        val nullCtxOut = g.text.run(mapOf("ids" to nullIdsTensor, "style" to nullStyleTensor))
        val nullCtxTensor = nullCtxOut[0] as OnnxTensor

        val durOut = g.dur.run(mapOf("ctx" to ctxTensor, "ctx_mask" to maskTensor, "spk" to spkTensor))
        val logS = (durOut[0] as OnnxTensor).floatBuffer.get(0)
        val secs = FlowScheduler.durationSeconds(logS.toDouble())
        val T = FlowScheduler.frames(secs, fps)

        val rng = DeterministicRng(0)
        val C = 144
        var x = FloatArray(C * T) { rng.nextGaussian() }
        val xShape = longArrayOf(1, C.toLong(), T.toLong())
        val n = steps
        val sch = FlowScheduler.schedule(n, sway)

        for (i in 0 until n) {
            val tVal = floatArrayOf(sch.t[i])
            val tTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(tVal), longArrayOf(1))
            val xTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), xShape)
            val vOut = g.ve.run(
                mapOf(
                    "x" to xTensor, "t" to tTensor, "ctx" to ctxTensor, "ctx_mask" to maskTensor,
                    "spk" to spkTensor, "style" to styleTensor,
                ),
            )
            val vBuf = (vOut[0] as OnnxTensor).floatBuffer
            val v = FloatArray(vBuf.remaining()); vBuf.get(v)

            val vFinal: FloatArray
            if (cfg > 0) {
                val vuOut = g.ve.run(
                    mapOf(
                        "x" to xTensor, "t" to tTensor, "ctx" to nullCtxTensor, "ctx_mask" to nullMaskTensor,
                        "spk" to nullSpkTensor, "style" to nullStyleTensor,
                    ),
                )
                val vuBuf = (vuOut[0] as OnnxTensor).floatBuffer
                val vu = FloatArray(vuBuf.remaining()); vuBuf.get(vu)
                vFinal = FloatArray(v.size) { j -> vu[j] + cfg.toFloat() * (v[j] - vu[j]) }
                vuOut.close()
            } else {
                vFinal = v
            }
            val dt = sch.dt[i]
            x = FloatArray(x.size) { j -> x[j] + dt * vFinal[j] }
            vOut.close(); xTensor.close(); tTensor.close()
        }

        val xFinalTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), xShape)
        val decOut = g.dec.run(mapOf("x" to xFinalTensor))
        val wavBuf = (decOut[0] as OnnxTensor).floatBuffer
        val wav = FloatArray(wavBuf.remaining()); wavBuf.get(wav)
        val genMs = System.currentTimeMillis() - t0

        idsTensor.close(); styleTensor.close(); nullStyleTensor.close(); spkTensor.close()
        nullSpkTensor.close(); maskTensor.close(); nullIdsTensor.close(); nullMaskTensor.close()
        ctxOut.close(); nullCtxOut.close(); decOut.close(); xFinalTensor.close()

        return Triple(wav, genMs, T.toLong())
    }
}
