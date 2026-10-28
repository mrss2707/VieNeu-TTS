import 'dart:async';

import 'package:flutter/services.dart' show rootBundle;
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:vieneu_tts/vieneu_tts.dart';

/// Đọc MỘT CHƯƠNG truyện hoàn chỉnh bằng một giọng, trên thiết bị thật.
///
/// Phát RA LOA thật (~10+ phút). Chạy:
///   flutter test integration_test/chapter_integration_test.dart -d <device> \
///     --dart-define=VOICE=my_duyen
///
/// In dòng `CHAPTER ...` tổng hợp: số chunk, thời gian giữa các chunk (đo độ trễ sinh âm),
/// số lần underrun, RTF. Không khẳng định chất lượng âm thanh — phải nghe trực tiếp.
const _voice = String.fromEnvironment('VOICE', defaultValue: 'my_duyen');

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('đọc trọn chương truyện', (tester) async {
    final text = await rootBundle.loadString('assets/story/chuong_1.txt');

    final tts = VieNeuTts(profile: const GenerationProfile(steps: 12));
    await tts.install(assetPath: 'assets/pkg');
    await tts.load();

    final voices = await tts.voiceManager.listPresets();
    final voice = voices.firstWhere((v) => v.id == _voice,
        orElse: () => throw StateError('Không có giọng "$_voice" trong ${voices.map((v) => v.id)}'));
    await tts.voiceManager.select(voice.id);

    final reader = await tts.createReader();
    await reader.setPrefetchDepth(3);

    final sw = Stopwatch();
    final progressAt = <int, int>{}; // chunk_index -> ms kể từ lúc play
    var underruns = 0;
    var nChunks = 0;
    final errors = <Object>[];
    final done = Completer<OnDone>();
    final sub = reader.events.listen((e) {
      switch (e) {
        case OnProgress(:final chunkIndex, nChunks: final total):
          progressAt[chunkIndex] = sw.elapsedMilliseconds;
          nChunks = total;
        case OnUnderrunWarning():
          underruns++;
        case OnErrorEvent(:final error):
          errors.add(error);
          if (!done.isCompleted) done.completeError(error);
        case OnDone():
          if (!done.isCompleted) done.complete(e);
      }
    });

    // enqueue = tách câu + normalize (Rust) cả chương: tính vào thời gian chờ của người nghe.
    final enqSw = Stopwatch()..start();
    await reader.enqueue(text);
    final enqueueMs = enqSw.elapsedMilliseconds;
    sw.start();
    await reader.play();
    final finished = await done.future.timeout(const Duration(minutes: 45));
    final wallMs = sw.elapsedMilliseconds;
    await sub.cancel();

    final order = progressAt.keys.toList()..sort();
    final gaps = <int>[
      for (var i = 1; i < order.length; i++) progressAt[order[i]]! - progressAt[order[i - 1]]!
    ]..sort();
    int pct(double p) => gaps.isEmpty ? 0 : gaps[((gaps.length - 1) * p).round()];
    final m = finished.metrics;
    // ignore: avoid_print
    print('CHAPTER voice=$_voice chars=${text.length} chunksReported=${order.length}/$nChunks '
        'wall=${(wallMs / 1000).toStringAsFixed(0)}s audio=${m.audioSeconds?.toStringAsFixed(1)}s '
        'gen=${m.generationMs}ms rtf=${m.rtf?.toStringAsFixed(3)} underruns=$underruns '
        'enqueue=${enqueueMs}ms firstChunkReadyAfterPlay=${order.isEmpty ? -1 : progressAt[order.first]}ms '
        'timeToFirstChunk=${enqueueMs + (order.isEmpty ? 0 : progressAt[order.first]!)}ms '
        'interChunk_ms p50=${pct(0.5)} p95=${pct(0.95)} max=${gaps.isEmpty ? 0 : gaps.last} '
        'thermal=${m.thermalBucket}');

    expect(errors, isEmpty);
    expect(order.length, greaterThan(50));
    expect(m.audioSeconds, greaterThan(300));

    await tts.dispose();
  }, timeout: const Timeout(Duration(minutes: 50)));
}
