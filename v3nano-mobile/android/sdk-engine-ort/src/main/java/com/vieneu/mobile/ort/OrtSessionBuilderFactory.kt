package com.vieneu.mobile.ort

import com.vieneu.mobile.engine.ProviderStatus
import com.vieneu.mobile.engine.SessionBuilderFactory

/**
 * [SessionBuilderFactory] dựa trên [OrtSessionOptions] — thử ladder theo [create.order]
 * (mặc định CPU → XNNPACK), trả provider THẬT + lý do fallback.
 */
class OrtSessionBuilderFactory : SessionBuilderFactory {
    override fun create(order: List<String>): ProviderStatus {
        val ladder = order.ifEmpty { listOf("CPU") }
        var lastReason: String? = null
        for (provider in ladder) {
            val (so, status) = OrtSessionOptions.providerStatusFor(provider)
            so.close()
            if (!status.fellBackToCpu || provider == "CPU") return status
            lastReason = status.reason
        }
        return ProviderStatus(
            requested = ladder.first(),
            actual = "CPU",
            fellBackToCpu = ladder.first() != "CPU",
            reason = lastReason,
        )
    }
}
