import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:vieneu_tts/vieneu_tts.dart';

/// Kiểm tra trên thiết bị thật: giọng được chọn có THỰC SỰ đi tới model hay không.
///
/// Hồi quy: host từng đọc file giọng từ `assets/voices/voices/...` (không tồn tại), âm thầm dùng
/// vector 0, nên model tự chọn giọng cho từng chunk (lẫn nam/nữ). Test này chọn từng giọng, sinh
/// một câu ngắn và kiểm tra (1) nạp được 11 giọng, (2) sinh xong không lỗi, (3) cùng một câu bằng
/// hai giọng khác nhau phải cho audio dài/khác nhau (cùng vector 0 → cùng kết quả).
void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('mỗi giọng được nạp và dùng thật', (tester) async {
    final tts = VieNeuTts(profile: const GenerationProfile(steps: 12));
    await tts.install(assetPath: 'assets/pkg');
    await tts.load();

    final voices = await tts.voiceManager.listPresets();
    expect(voices.length, 11);

    final reader = await tts.createReader();
    await reader.setPrefetchDepth(3);

    Future<Metrics> speak(String id) async {
      await tts.voiceManager.select(id);
      final done = Completer<OnDone>();
      final sub = reader.events.listen((e) {
        if (e is OnDone && !done.isCompleted) done.complete(e);
        if (e is OnErrorEvent && !done.isCompleted) done.completeError(e.error);
      });
      await reader.enqueue('Xin chào, hôm nay trời đẹp quá, chúng ta cùng đi dạo nhé.');
      await reader.play();
      final r = await done.future.timeout(const Duration(minutes: 3));
      await sub.cancel();
      return r.metrics;
    }

    final secs = <String, double>{};
    for (final id in ['my_duyen', 'duc_tri', 'truc_ly', 'anh_khoi']) {
      final m = await speak(id);
      secs[id] = m.audioSeconds ?? 0;
      // ignore: avoid_print
      print('VOICE $id audio=${m.audioSeconds}s gen=${m.generationMs}ms');
      expect(m.audioSeconds, greaterThan(0), reason: id);
    }
    // Vector 0 cho mọi giọng → mọi giọng ra cùng một độ dài. Giọng thật → các độ dài khác nhau.
    expect(secs.values.toSet().length, greaterThan(1), reason: 'mọi giọng ra cùng độ dài: $secs');

    await tts.dispose();
  }, timeout: const Timeout(Duration(minutes: 20)));
}
