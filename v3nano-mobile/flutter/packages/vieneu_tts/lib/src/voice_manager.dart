/// 11 preset voice — metadata index; select không tạo session mới.
class Voice {
  final String id;
  final String name;
  final double? werReference;

  const Voice({required this.id, required this.name, this.werReference});

  factory Voice.fromMap(Map<Object?, Object?> m) => Voice(
        id: m['id'] as String? ?? '',
        name: m['name'] as String? ?? '',
        werReference: (m['wer_reference'] as num?)?.toDouble(),
      );
}

class VoiceManager {
  final Future<Map<Object?, Object?>?> Function(String method,
      [Map<String, Object?>? args]) _invoke;

  VoiceManager(this._invoke);

  Future<List<Voice>> listPresets() async {
    final raw = await _invoke('listPresets');
    final list = raw?['voices'] as List? ?? const [];
    return [
      for (final v in list)
        Voice.fromMap((v as Map).cast<Object?, Object?>()),
    ];
  }

  Future<void> select(String id) => _invoke('selectVoice', {'id': id}).then((_) {});
}
