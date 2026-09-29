/// Metadata-only metrics (không text/audio).
class Metrics {
  final double? rtf;
  final int? generationMs;
  final double? audioSeconds;
  final double? peakRssMb;
  final int nChunks;
  final String? provider;
  final String? thermalBucket;
  final int cacheHit;
  final int cacheMiss;
  final String? errorCode;
  final bool hardFail;

  const Metrics({
    this.rtf,
    this.generationMs,
    this.audioSeconds,
    this.peakRssMb,
    this.nChunks = 0,
    this.provider,
    this.thermalBucket,
    this.cacheHit = 0,
    this.cacheMiss = 0,
    this.errorCode,
    this.hardFail = false,
  });

  factory Metrics.fromMap(Map<Object?, Object?> m) => Metrics(
        rtf: (m['rtf'] as num?)?.toDouble(),
        generationMs: (m['generation_ms'] as num?)?.toInt(),
        audioSeconds: (m['audio_seconds'] as num?)?.toDouble(),
        peakRssMb: (m['peak_rss_mb'] as num?)?.toDouble(),
        nChunks: (m['n_chunks'] as num?)?.toInt() ?? 0,
        provider: m['provider'] as String?,
        thermalBucket: m['thermal_bucket'] as String?,
        cacheHit: (m['cache_hit'] as num?)?.toInt() ?? 0,
        cacheMiss: (m['cache_miss'] as num?)?.toInt() ?? 0,
        errorCode: m['error_code'] as String?,
        hardFail: m['hard_fail'] == true,
      );
}
