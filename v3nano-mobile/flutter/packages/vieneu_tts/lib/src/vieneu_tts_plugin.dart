import 'dart:async';

import 'generation_profile.dart';
import 'reader.dart';
import 'voice_manager.dart';

/// Entry Flutter — thin wrapper (06 §6.1): không inference trong Dart.
class VieNeuTts {
  final GenerationProfile profile;
  final List<String> providerOrder;

  VieNeuTts({
    this.profile = const GenerationProfile(),
    this.providerOrder = const ['CPU', 'XNNPACK'],
  });

  final _events = StreamController<ReaderEvent>.broadcast();

  /// Giao tiếp native — inject được trong unit test (mock Pigeon/host).
  Future<Map<Object?, Object?>?> Function(String method, [Map<String, Object?>? args])?
      invokeHandler;

  Future<Map<Object?, Object?>?> _invoke(String method, [Map<String, Object?>? args]) async {
    final h = invokeHandler;
    if (h == null) {
      throw StateError('VieNeuTts.invokeHandler chưa gắn — plugin native chưa ready');
    }
    return h(method, args);
  }

  Future<void> install({required String assetPath}) =>
      _invoke('install', {'assetPath': assetPath}).then((_) {});

  Future<void> load() => _invoke('load').then((_) {});

  Future<Reader> createReader() async {
    await _invoke('createReader');
    return Reader((m, [a]) {
      _invoke(m, a);
    }, _events.stream);
  }

  VoiceManager get voiceManager => VoiceManager(_invoke);

  Stream<ReaderEvent> get events => _events.stream;

  /// Test hook: đẩy event từ host mock.
  void debugEmit(ReaderEvent e) => _events.add(e);

  Future<void> dispose() => _events.close();
}
