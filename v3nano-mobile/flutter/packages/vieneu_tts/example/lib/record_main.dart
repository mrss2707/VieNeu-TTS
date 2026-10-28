import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show rootBundle;
import 'package:vieneu_tts/vieneu_tts.dart';

/// Entrypoint CHẨN ĐOÁN (không UI): đọc đầu chương, ghi đúng PCM mà player nhận (audio SAU INT8 + DSP)
/// vào `files/debug_pcm/played.f32` rồi ghi cờ `files/debug_pcm/done.txt`.
///
/// Chạy bằng adb (KHÔNG qua `flutter test`, vốn tự gỡ app khi kết thúc và xoá luôn file ghi):
///   flutter build apk --debug --target lib/record_main.dart --target-platform android-arm64
///   adb install -r build/app/outputs/flutter-apk/app-debug.apk
///   adb shell am start -n com.example.vieneu_tts_example/.MainActivity
///   # chờ done.txt, rồi: adb exec-out run-as com.example.vieneu_tts_example cat files/debug_pcm/played.f32
const _voice = String.fromEnvironment('VOICE', defaultValue: 'my_duyen');
const _storyAsset = String.fromEnvironment('STORY', defaultValue: 'assets/story/chuong_1_head.txt');
const _deEsser = bool.fromEnvironment('DEESSER', defaultValue: true);
/// DSP=false → tắt TOÀN BỘ comfort DSP và treble: ghi đầu ra THÔ của model INT8 (để biết chói/xung đến từ model hay DSP).
const _dsp = bool.fromEnvironment('DSP', defaultValue: true);
/// Số bước Euler của flow-matching (production hiện chốt 12).
const _steps = int.fromEnvironment('STEPS', defaultValue: 12);

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MaterialApp(home: Scaffold(body: Center(child: Text('đang ghi PCM…')))));
  final dir = Directory('/data/data/com.example.vieneu_tts_example/files/debug_pcm')..createSync(recursive: true);
  final flag = File('${dir.path}/done.txt');
  if (flag.existsSync()) flag.deleteSync();
  try {
    final text = await rootBundle.loadString(_storyAsset);
    final tts = VieNeuTts();
    await tts.install(assetPath: 'assets/pkg');
    await tts.load(
      override: const GenerationProfile(
        steps: _steps,
        deEsserEnabled: _deEsser,
        comfortFilterEnabled: _dsp,
        trebleFilterEnabled: _dsp,
      ),
      debugRecordPcm: true,
    );
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
    final m = (await done.future.timeout(const Duration(minutes: 15))).metrics;
    await sub.cancel();
    await Future<void>.delayed(const Duration(seconds: 6)); // chờ player/ghi xong
    flag.writeAsStringSync('OK chunks=${m.nChunks} audio=${m.audioSeconds} gen=${m.generationMs}ms rtf=${m.rtf} '
        'steps=$_steps voice=$_voice deEsser=$_deEsser dsp=$_dsp');
  } catch (e, st) {
    flag.writeAsStringSync('FAIL $e\n$st');
  }
}
