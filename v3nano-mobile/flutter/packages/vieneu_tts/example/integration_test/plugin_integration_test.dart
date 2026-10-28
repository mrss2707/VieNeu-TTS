import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:vieneu_tts/vieneu_tts.dart';

/// Integration test trên THIẾT BỊ THẬT/emulator arm64 (cần gói model trong assets).
///
/// Chạy: `flutter test integration_test -d <device>`
///
/// Chữ thô tiếng Việt phải đi qua G2P on-device (sea-g2p) rồi mới tới model — nếu G2P hỏng,
/// luồng sự kiện có OnErrorEvent hoặc không có chunk nào, test này thất bại.
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('install → load → enqueue(text thô) → play → OnDone', (tester) async {
    final tts = VieNeuTts(profile: const GenerationProfile(steps: 12));
    await tts.install(assetPath: 'assets/pkg');
    await tts.load();

    final voices = await tts.voiceManager.listPresets();
    expect(voices, isNotEmpty);
    await tts.voiceManager.select(voices.first.id);

    final reader = await tts.createReader();
    await reader.setPrefetchDepth(3);

    final events = <ReaderEvent>[];
    final sw = Stopwatch()..start();
    final done = Completer<OnDone>();
    final sub = reader.events.listen((e) {
      events.add(e);
      if (e is OnDone && !done.isCompleted) done.complete(e);
      if (e is OnErrorEvent && !done.isCompleted) done.completeError(e.error);
    });

    await reader.enqueue(
      'Năm mười lăm tuổi, Tiêu Viêm từng là thiên tài trẻ nhất của gia tộc Tiêu. '
      'Ngày 12/03/2024 anh ấy trả 1.500.000đ cho 3kg gạo.',
    );
    await reader.play();
    final finished = await done.future.timeout(const Duration(minutes: 8));
    await sub.cancel();

    final m = finished.metrics;
    // ignore: avoid_print
    print('METRICS wall=${sw.elapsedMilliseconds}ms nChunks=${m.nChunks} '
        'audio=${m.audioSeconds}s gen=${m.generationMs}ms rtf=${m.rtf} '
        'underruns=${events.whereType<OnUnderrunWarning>().length}');

    expect(events.whereType<OnErrorEvent>(), isEmpty);
    expect(finished.metrics.nChunks, greaterThan(0));
    expect(finished.metrics.audioSeconds, greaterThan(0));

    await tts.dispose();
  });
}
