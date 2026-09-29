package com.vieneu.mobile.lifecycle

/**
 * Policy 3 tầng (01 §1.7) — **bắt buộc**, không nice-to-have.
 *
 * Không đổi `steps` (quality contract); chỉ giảm thread / prefetch / thêm idle.
 * Tín hiệu Android: PowerManager thermal (API 29+) — inject qua [onThermalStatus].
 */
data class ThermalBudget(
    val tierName: String,
    val threads: Int,
    val prefetchDepth: Int,
    val idleBetweenChunksMs: Long,
)

object ThermalPolicy {
    /** Android thermal status constants (PowerManager) — map sang bucket. */
    const val STATUS_NONE = 0
    const val STATUS_LIGHT = 1
    const val STATUS_MODERATE = 2
    const val STATUS_SEVERE = 3
    const val STATUS_CRITICAL = 4
    const val STATUS_EMERGENCY = 5
    const val STATUS_SHUTDOWN = 6

    fun defaultTiers(baseThreads: Int = 4, basePrefetch: Int = 3): List<ThermalBudget> = listOf(
        ThermalBudget("normal", baseThreads, basePrefetch, idleBetweenChunksMs = 0),
        ThermalBudget("warm", maxOf(1, baseThreads / 2), maxOf(2, basePrefetch - 1), idleBetweenChunksMs = 20),
        ThermalBudget("hot", threads = 1, prefetchDepth = 2, idleBetweenChunksMs = 80),
    )

    fun bucketFor(androidThermalStatus: Int): String = when (androidThermalStatus) {
        STATUS_NONE -> "none"
        STATUS_LIGHT, STATUS_MODERATE -> "warm"
        STATUS_SEVERE, STATUS_CRITICAL, STATUS_EMERGENCY, STATUS_SHUTDOWN -> "hot"
        else -> "none"
    }

    fun budgetFor(androidThermalStatus: Int, tiers: List<ThermalBudget> = defaultTiers()): ThermalBudget {
        val name = when (bucketFor(androidThermalStatus)) {
            "warm" -> "warm"
            "hot" -> "hot"
            else -> "normal"
        }
        return tiers.find { it.tierName == name } ?: tiers.first()
    }
}

/**
 * Memory pressure (04 §4.5): giảm prefetch, flush cache xa — KHÔNG đóng session (đắt).
 */
interface MemoryPressureHandler {
    fun onTrimMemory(level: Int)

    companion object {
        const val TRIM_RUNNING_MODERATE = 5
        const val TRIM_RUNNING_LOW = 10
        const val TRIM_RUNNING_CRITICAL = 15
        const val TRIM_UI_HIDDEN = 20
        const val TRIM_BACKGROUND = 40
        const val TRIM_MODERATE = 60
        const val TRIM_COMPLETE = 80
    }
}
