import 'dart:async';

import 'package:flutter/services.dart' show rootBundle;
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:vieneu_tts/vieneu_tts.dart';

/// Đọc ĐẦU chương (24 chunk; 20 chunk đầu GIỐNG HỆT cả chương) và GHI đúng PCM mà player nhận
/// (audio thật SAU INT8 + DSP) để đo chói bằng số.
///
/// File: `files/debug_pcm/played.f32` (float32 LE mono 24 kHz) + `played_index.csv` (chunk,samples):
///   adb exec-out run-as com.example.vieneu_tts_example cat files/debug_pcm/played.f32 > played.f32
///
///   flutter test integration_test/record_pcm_test.dart -d <device> --dart-define=VOICE=my_duyen
const _voice = String.fromEnvironment('VOICE', defaultValue: 'my_duyen');

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('ghi PCM thật của đầu chương', (tester) async {
    final text = await rootBundle.loadString('assets/story/chuong_1_head.txt');
    final tts = VieNeuTts(profile: const GenerationProfile(steps: 12));
    await tts.install(assetPath: 'assets/pkg');
    await tts.load(debugRecordPcm: true);
    await tts.voiceManager.select(_voice);
    final reader = await tts.createReader();
    await reader.setPrefetchDepth(3);

    final done = Completer<OnDone>();
    final sub = reader.events.listen((e) {
      if (e is OnDone && !done.isCompleted) done.complete(e);
      if (e is OnErrorEvent && !done.isCompleted) done.completeError(e.error);
    });

    await reader.enqueue(text);
    await reader.play();
    final finished = await done.future.timeout(const Duration(minutes: 10));
    await sub.cancel();
    // OnDone bắn khi chunk cuối đã đẩy vào player; chờ cho ghi/phát xong trước khi test dọn app.
    await Future<void>.delayed(const Duration(seconds: 8));
    // ignore: avoid_print
    print('RECORDED chunks=${finished.metrics.nChunks} audio=${finished.metrics.audioSeconds}s voice=$_voice');
    await tts.dispose();
  }, timeout: const Timeout(Duration(minutes: 12)));
}
