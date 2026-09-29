package com.vieneu.mobile.api

/**
 * Metrics metadata-only (không text/audio) — 01-kien-truc-runtime §1.10.
 * Xuất JSON qua [toJson] cho log / MetricsCollector.
 */
data class Metrics(
    val rtf: Double? = null,
    val generationMs: Long? = null,
    val audioSeconds: Double? = null,
    val peakRssMb: Double? = null,
    val graphLoadMs: Long? = null,
    val nChunks: Int = 0,
    val provider: String? = null,
    val precision: String? = null,
    val steps: Int? = null,
    val thermalBucket: String? = null,
    val cacheHit: Int = 0,
    val cacheMiss: Int = 0,
    val errorCode: String? = null,
    val hardFail: Boolean = false,
) {
    fun toJson(): String {
        val parts = ArrayList<String>()
        rtf?.let { parts.add("\"rtf\":$it") }
        generationMs?.let { parts.add("\"generation_ms\":$it") }
        audioSeconds?.let { parts.add("\"audio_seconds\":$it") }
        peakRssMb?.let { parts.add("\"peak_rss_mb\":$it") }
        graphLoadMs?.let { parts.add("\"graph_load_ms\":$it") }
        if (nChunks != 0) parts.add("\"n_chunks\":$nChunks")
        provider?.let { parts.add("\"provider\":\"$it\"") }
        precision?.let { parts.add("\"precision\":\"$it\"") }
        steps?.let { parts.add("\"steps\":$it") }
        thermalBucket?.let { parts.add("\"thermal_bucket\":\"$it\"") }
        if (cacheHit != 0) parts.add("\"cache_hit\":$cacheHit")
        if (cacheMiss != 0) parts.add("\"cache_miss\":$cacheMiss")
        errorCode?.let { parts.add("\"error_code\":\"$it\"") }
        if (hardFail) parts.add("\"hard_fail\":true")
        return parts.joinToString(",", prefix = "{", postfix = "}")
    }
}
