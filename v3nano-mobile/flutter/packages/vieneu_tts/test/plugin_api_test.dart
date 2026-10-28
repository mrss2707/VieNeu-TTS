import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:vieneu_tts/vieneu_tts.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const methodsChannel = MethodChannel('vieneu_tts/methods');
  const eventsChannel = MethodChannel('vieneu_tts/events');

  setUp(() {
    // EventChannel không có host trong unit test — trả null cho 'listen'.
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(eventsChannel, (call) async => null);
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodsChannel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(eventsChannel, null);
  });

  test('EngineError.fromCode maps native codes', () {
    expect(EngineError.fromCode('E_MODEL_CHECKSUM', 'x'), isA<ChecksumError>());
    expect(EngineError.fromCode('E_MODEL_CORRUPT', 'x'), isA<CorruptError>());
    expect(EngineError.fromCode('E_COMPAT', 'x'), isA<CompatError>());
    expect(EngineError.fromCode('E_CANCELED', 'x'), isA<CanceledError>());
    expect(EngineError.fromCode('E_MODEL_MISSING', 'x'), isA<MissingError>());
    expect(EngineError.fromCode('E_UNDERRUN', 'x'), isA<UnderrunError>());
    expect(EngineError.fromCode('E_THERMAL_T3', 'x'), isA<ThermalT3Error>());
    expect(EngineError.fromCode('E_UNKNOWN', 'x'), isA<InternalError>());
  });

  test('GenerationProfile defaults match locked D1 + comfort rev 4', () {
    const p = GenerationProfile();
    expect(p.steps, 8);
    expect(p.sway, -1.0);
    expect(p.cfg, 3.0);
    expect(p.trebleFilterEnabled, isTrue);
    expect(p.comfortFilterEnabled, isTrue);
    expect(p.maxCharsPerChunk, 140);
    // rev 5: deEsserEnabled default ON; rev 4: spectralDenoiseEnabled default ON (khớp GenerationProfile Kotlin).
    expect(p.profileRevision, 6);
    expect(p.spectralDenoiseEnabled, isTrue);
    expect(p.deEsserEnabled, isTrue);
    expect(p.toMap()['deEsserEnabled'], isTrue);
    expect(p.toMap()['comfortFilterEnabled'], isTrue);
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
    await tts.dispose();
  });

  test('default invokeHandler routes through MethodChannel', () async {
    final calls = <MethodCall>[];
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(methodsChannel, (call) async {
      calls.add(call);
      return null;
    });

    final tts = VieNeuTts(); // không set invokeHandler → dùng channel thật
    await tts.load();
    final reader = await tts.createReader();
    await reader.metrics();

    expect(calls.map((c) => c.method), containsAll(['load', 'createReader', 'metrics']));
    // load phải gửi kèm profile map (host dựng GenerationProfile theo đó).
    final loadCall = calls.firstWhere((c) => c.method == 'load');
    expect((loadCall.arguments as Map)['profile'], isA<Map>());
    await tts.dispose();
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
