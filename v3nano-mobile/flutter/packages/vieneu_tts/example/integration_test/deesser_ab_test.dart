import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:vieneu_tts/vieneu_tts.dart';

/// A/B de-esser TRÊN LOA THẬT: cùng câu, cùng giọng, chỉ khác de-esser TẮT / BẬT.
///
/// Mỗi câu phát hai lần liên tiếp:  A = de-esser TẮT (rev 4, như bản đã nghe)  rồi  B = BẬT (rev 5).
/// Dòng `AB_MARK` trong log cho biết đang nghe cái nào.
///
///   flutter test integration_test/deesser_ab_test.dart -d <device> --dart-define=VOICE=my_duyen
const _voice = String.fromEnvironment('VOICE', defaultValue: 'my_duyen');

// Câu có chỗ chói đã nghe được + vài câu nhiều âm xát/bật (s, x, ch, t, k) để thấy rõ khác biệt.
const _lines = <String>[
  'Mười năm rồi, cô mới trở về.',
  'Chiếc vali nhỏ trong tay Lam nặng hơn cô tưởng.',
  'Xe khách chạy xuôi sông Lục, sáng sớm sương giăng trắng xóa.',
  'Cô chầm chậm bước xuống, chạm tay vào cánh cổng cũ kỹ.',
];

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('A/B de-esser tắt / bật', (tester) async {
    final tts = VieNeuTts();
    await tts.install(assetPath: 'assets/pkg');

    for (var i = 0; i < _lines.length; i++) {
      for (final deEsser in [false, true]) {
        // load() lại với profile khác: dùng đúng đường chạy thật của từng cấu hình.
        await tts.load(override: GenerationProfile(steps: 12, deEsserEnabled: deEsser));
        await tts.voiceManager.select(_voice);
        final reader = await tts.createReader();

        final done = Completer<OnDone>();
        final sub = reader.events.listen((e) {
          if (e is OnDone && !done.isCompleted) done.complete(e);
          if (e is OnErrorEvent && !done.isCompleted) done.completeError(e.error);
        });
        // ignore: avoid_print
        print('AB_MARK ${DateTime.now().toIso8601String()} '
            '${deEsser ? "B_DEESSER_ON " : "A_DEESSER_OFF"} line=$i "${_lines[i]}"');
        await reader.enqueue(_lines[i]);
        await reader.play();
        await done.future.timeout(const Duration(minutes: 2));
        await sub.cancel();
        // OnDone bắn khi chunk cuối đã ĐẨY vào player; âm thanh còn đang phát → chờ cho xong.
        await Future<void>.delayed(const Duration(milliseconds: 3000));
      }
    }

    await tts.dispose();
  }, timeout: const Timeout(Duration(minutes: 20)));
}
