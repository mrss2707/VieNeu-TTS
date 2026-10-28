import 'dart:async';

import 'package:flutter/services.dart';

import 'engine_error.dart';
import 'generation_profile.dart';
import 'metrics.dart';
import 'reader.dart';
import 'voice_manager.dart';

/// Entry Flutter — thin wrapper (06 §6.1): không inference trong Dart.
///
/// Mặc định nói chuyện với host qua `MethodChannel('vieneu_tts/methods')` +
/// `EventChannel('vieneu_tts/events')`. [invokeHandler] vẫn ghi đè được để unit-test
/// không cần platform.
class VieNeuTts {
  static const MethodChannel _methodChannel = MethodChannel('vieneu_tts/methods');
  static const EventChannel _eventChannel = EventChannel('vieneu_tts/events');

  final GenerationProfile profile;
  final List<String> providerOrder;

  VieNeuTts({
    this.profile = const GenerationProfile(),
    this.providerOrder = const ['CPU', 'XNNPACK'],
  }) {
    _nativeSub = _eventChannel.receiveBroadcastStream().listen(
          _onNativeEvent,
          onError: (Object _) {
            // Lỗi thiết lập kênh (host chưa gắn) — bỏ qua; lỗi runtime đi qua data event.
          },
        );
  }

  final _events = StreamController<ReaderEvent>.broadcast();
  StreamSubscription<dynamic>? _nativeSub;

  /// Ghi đè trong unit test; null → dùng MethodChannel thật.
  Future<Map<Object?, Object?>?> Function(String method, [Map<String, Object?>? args])?
      invokeHandler;

  Future<Map<Object?, Object?>?> _invoke(String method, [Map<String, Object?>? args]) async {
    final h = invokeHandler;
    if (h != null) return h(method, args);
    return _methodChannel.invokeMethod<Map<Object?, Object?>>(method, args);
  }

  Future<void> install({required String assetPath}) =>
      _invoke('install', {'assetPath': assetPath}).then((_) {});

  /// Gửi kèm [profile] để host dựng đúng GenerationProfile (demo truyền steps=12).
  /// [override] nạp lại model với profile khác (vd. bật/tắt de-esser để A/B); sau đó phải
  /// gọi [createReader] lại vì host huỷ reader cũ.
  ///
  /// [debugRecordPcm] (chẩn đoán, mặc định tắt): host ghi đúng PCM mà player nhận (audio SAU model + DSP)
  /// vào `files/debug_pcm/played.f32` (float32 LE mono 24 kHz) + `played_index.csv` (chunk,samples).
  Future<void> load({GenerationProfile? override, bool debugRecordPcm = false}) => _invoke('load', {
        'profile': {
          ...(override ?? profile).toMap(),
          if (debugRecordPcm) 'debugRecordPcm': true,
        },
      }).then((_) {});

  Future<Reader> createReader() async {
    await _invoke('createReader');
    return Reader(_invoke, _events.stream);
  }

  VoiceManager get voiceManager => VoiceManager(_invoke);

  Stream<ReaderEvent> get events => _events.stream;

  /// Test hook: đẩy event từ host mock.
  void debugEmit(ReaderEvent e) => _events.add(e);

  void _onNativeEvent(dynamic raw) {
    if (raw is! Map) return;
    final m = raw.cast<Object?, Object?>();
    switch (m['type'] as String?) {
      case 'progress':
        _events.add(OnProgress(
          (m['chunk_index'] as num?)?.toInt() ?? 0,
          (m['n_chunks'] as num?)?.toInt() ?? 0,
        ));
      case 'done':
        final mm = (m['metrics'] as Map?)?.cast<Object?, Object?>() ?? const {};
        _events.add(OnDone(Metrics.fromMap(mm)));
      case 'underrun':
        _events.add(const OnUnderrunWarning());
      case 'error':
        _events.add(OnErrorEvent(EngineError.fromCode(
          m['code'] as String? ?? 'E_UNKNOWN',
          m['message'] as String? ?? '',
        )));
    }
  }

  Future<void> dispose() async {
    await _nativeSub?.cancel();
    _nativeSub = null;
    await _events.close();
  }
}
