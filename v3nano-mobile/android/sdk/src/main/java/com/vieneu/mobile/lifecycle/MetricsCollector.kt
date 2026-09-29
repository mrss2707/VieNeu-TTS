package com.vieneu.mobile.lifecycle

import com.vieneu.mobile.api.Metrics

/**
 * Gom metrics metadata-only (01 §1.10) — không text/audio.
 * [drain] trả snapshot rồi reset accumulator của turn.
 */
class MetricsCollector {
    private val lock = Any()
    private var rtfSum = 0.0
    private var rtfN = 0
    private var genMs = 0L
    private var audioSec = 0.0
    private var chunks = 0
    private var peakRssMb: Double? = null
    private var thermalBucket: String? = null
    private var provider: String? = null
    private var precision: String? = null
    private var steps: Int? = null
    private var cacheHit = 0
    private var cacheMiss = 0
    private var errorCode: String? = null
    private var hardFail = false

    fun recordChunk(generationMs: Long, audioSeconds: Double, rtf: Double?) {
        synchronized(lock) {
            genMs += generationMs
            audioSec += audioSeconds
            chunks++
            if (rtf != null) {
                rtfSum += rtf
                rtfN++
            }
        }
    }

    fun setProvider(provider: String?, precision: String?, steps: Int?) {
        synchronized(lock) {
            this.provider = provider
            this.precision = precision
            this.steps = steps
        }
    }

    fun setPeakRssMb(mb: Double?) {
        synchronized(lock) { peakRssMb = mb }
    }

    fun setThermalBucket(bucket: String?) {
        synchronized(lock) { thermalBucket = bucket }
    }

    fun recordCache(hit: Boolean) {
        synchronized(lock) {
            if (hit) cacheHit++ else cacheMiss++
        }
    }

    fun recordError(code: String?, hard: Boolean = false) {
        synchronized(lock) {
            errorCode = code
            if (hard) hardFail = true
        }
    }

    fun drain(): Metrics = synchronized(lock) {
        val m = Metrics(
            rtf = if (rtfN > 0) rtfSum / rtfN else null,
            generationMs = genMs.takeIf { it > 0 },
            audioSeconds = audioSec.takeIf { it > 0 },
            peakRssMb = peakRssMb,
            nChunks = chunks,
            provider = provider,
            precision = precision,
            steps = steps,
            thermalBucket = thermalBucket,
            cacheHit = cacheHit,
            cacheMiss = cacheMiss,
            errorCode = errorCode,
            hardFail = hardFail,
        )
        rtfSum = 0.0; rtfN = 0; genMs = 0; audioSec = 0.0; chunks = 0
        cacheHit = 0; cacheMiss = 0; errorCode = null; hardFail = false
        m
    }
}
