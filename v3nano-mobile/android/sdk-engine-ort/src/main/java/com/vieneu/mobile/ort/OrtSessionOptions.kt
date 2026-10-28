package com.vieneu.mobile.ort

import ai.onnxruntime.OrtSession
import android.util.Log
import com.vieneu.mobile.engine.ProviderStatus

/**
 * Session options theo provider — port `NanoBenchmarkCore.sessionOptionsForProvider`
 * (đã lock parity Android). Ladder: CPU → XNNPACK → NNAPI, fallback CPU có log
 * tường minh (không silent fallback).
 */
object OrtSessionOptions {
    const val TAG = "OrtEngine"

    /**
     * Tạo [OrtSession.SessionOptions] cho [provider] và trả luôn [ProviderStatus] mô tả
     * provider THẬT đang dùng + lý do fallback (nếu có).
     */
    fun providerStatusFor(provider: String, threads: Int = 4): Pair<OrtSession.SessionOptions, ProviderStatus> {
        val so = OrtSession.SessionOptions()
        so.setIntraOpNumThreads(threads)
        if (!is64Bit()) {
            // ORT 1.22.0 armeabi-v7a: tối ưu đồ thị làm SIGBUS (BUS_ADRALN) trong createSession
            // trên Redmi M2006C3LG (Helio G35, Android 11) với cả INT8 lẫn FP16. Tắt tối ưu thì
            // 4 graph INT8 nạp được. Chỉ áp cho 32-bit để không đổi hành vi đã đo trên arm64.
            so.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
        }
        var actual = "CPU"
        var reason: String? = null
        when (provider) {
            "XNNPACK" -> {
                try {
                    so.addXnnpack(emptyMap())
                    actual = "XNNPACK"
                } catch (e: Exception) {
                    reason = e.message
                    Log.w(TAG, "XNNPACK unavailable, falling back to CPU: ${e.message}")
                }
            }
            "NNAPI" -> {
                try {
                    so.addNnapi()
                    actual = "NNAPI"
                } catch (e: Exception) {
                    reason = e.message
                    Log.w(TAG, "NNAPI unavailable, falling back to CPU: ${e.message}")
                }
            }
        }
        return so to ProviderStatus(
            requested = provider,
            actual = actual,
            fellBackToCpu = provider != "CPU" && actual == "CPU",
            reason = reason,
        )
    }

    private fun is64Bit(): Boolean = android.os.Process.is64Bit()

    /** Port 1:1 `sessionOptionsForProvider(provider) -> Pair<SessionOptions, String>` cho harness. */
    fun sessionOptionsForProvider(provider: String): Pair<OrtSession.SessionOptions, String> {
        val (so, status) = providerStatusFor(provider)
        return so to status.actual
    }
}
