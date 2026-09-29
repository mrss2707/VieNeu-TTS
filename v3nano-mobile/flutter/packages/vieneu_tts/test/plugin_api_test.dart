import 'package:flutter_test/flutter_test.dart';
import 'package:vieneu_tts/vieneu_tts.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('EngineError.fromCode maps native codes', () {
    expect(EngineError.fromCode('E_MODEL_CHECKSUM', 'x'), isA<ChecksumError>());
    expect(EngineError.fromCode('E_MODEL_CORRUPT', 'x'), isA<CorruptError>());
    expect(EngineError.fromCode('E_COMPAT', 'x'), isA<CompatError>());
    expect(EngineError.fromCode('E_CANCELLED', 'x'), isA<CancelledError>());
    expect(EngineError.fromCode('E_UNKNOWN', 'x'), isA<InternalError>());
  });

  test('GenerationProfile defaults match locked D1', () {
    const p = GenerationProfile();
    expect(p.steps, 8);
    expect(p.sway, -1.0);
    expect(p.cfg, 3.0);
    expect(p.trebleFilterEnabled, isTrue);
    expect(p.maxCharsPerChunk, 140);
    expect(p.profileRevision, 1);
  });

  test('Metrics.fromMap parses native JSON keys', () {
    final m = Metrics.fromMap({
      'rtf': 0.32,
      'generation_ms': 1200,
      'n_chunks': 3,
      'provider': 'CPU',
      'thermal_bucket': 'warm',
      'hard_fail': true,
    });
    expect(m.rtf, 0.32);
    expect(m.nChunks, 3);
    expect(m.hardFail, isTrue);
  });

  test('VieNeuTts routes commands to invokeHandler', () async {
    final calls = <String>[];
    final tts = VieNeuTts();
    tts.invokeHandler = (method, [args]) async {
      calls.add(method);
      if (method == 'listPresets') {
        return {
          'voices': [
            {'id': 'duc_tri', 'name': 'Đức Trí', 'wer_reference': 3.12}
          ]
        };
      }
      return null;
    };

    await tts.install(assetPath: 'assets/pkg');
    await tts.load();
    final reader = await tts.createReader();
    await reader.enqueue('Xin chào.');
    await reader.play();
    await reader.cancel();

    final voices = await tts.voiceManager.listPresets();
    expect(voices.single.id, 'duc_tri');
    expect(calls, containsAll(['install', 'load', 'createReader', 'enqueue', 'play', 'cancel']));
  });

  test('Reader events stream emits OnDone', () async {
    final tts = VieNeuTts();
    tts.invokeHandler = (m, [a]) async => null;
    final reader = await tts.createReader();
    final future = reader.events.first;
    tts.debugEmit(OnDone(const Metrics(nChunks: 1)));
    final ev = await future;
    expect(ev, isA<OnDone>());
    await tts.dispose();
  });
}
