/// Cấu hình sinh — mirror `GenerationProfile` Kotlin (profileRevision).
///
/// rev 2: [comfortFilterEnabled] default ON (treble + presence dip 3kHz +
/// de-ess shelf 5.5kHz + soft-clip / peak −1 dBFS) — khớp AudioComfortFilter.
/// rev 3: [noiseFloorEnabled] default ON — downward expander −48 dBFS.
class GenerationProfile {
  final int profileRevision;
  final int steps;
  final double sway;
  final double cfg;
  final bool trebleFilterEnabled;
  final bool comfortFilterEnabled;
  final bool noiseFloorEnabled;
  final int maxCharsPerChunk;

  const GenerationProfile({
    this.profileRevision = 3,
    this.steps = 8,
    this.sway = -1.0,
    this.cfg = 3.0,
    this.trebleFilterEnabled = true,
    this.comfortFilterEnabled = true,
    this.noiseFloorEnabled = true,
    this.maxCharsPerChunk = 140,
  });

  Map<String, Object> toMap() => {
        'profileRevision': profileRevision,
        'steps': steps,
        'sway': sway,
        'cfg': cfg,
        'trebleFilterEnabled': trebleFilterEnabled,
        'comfortFilterEnabled': comfortFilterEnabled,
        'noiseFloorEnabled': noiseFloorEnabled,
        'maxCharsPerChunk': maxCharsPerChunk,
      };
}
