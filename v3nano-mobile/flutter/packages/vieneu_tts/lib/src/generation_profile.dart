/// Cấu hình sinh — mirror `GenerationProfile` Kotlin (profileRevision).
class GenerationProfile {
  final int profileRevision;
  final int steps;
  final double sway;
  final double cfg;
  final bool trebleFilterEnabled;
  final int maxCharsPerChunk;

  const GenerationProfile({
    this.profileRevision = 1,
    this.steps = 8,
    this.sway = -1.0,
    this.cfg = 3.0,
    this.trebleFilterEnabled = true,
    this.maxCharsPerChunk = 140,
  });

  Map<String, Object> toMap() => {
        'profileRevision': profileRevision,
        'steps': steps,
        'sway': sway,
        'cfg': cfg,
        'trebleFilterEnabled': trebleFilterEnabled,
        'maxCharsPerChunk': maxCharsPerChunk,
      };
}
