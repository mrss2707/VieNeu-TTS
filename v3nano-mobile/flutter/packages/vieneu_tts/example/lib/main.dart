import 'dart:async';

import 'package:flutter/material.dart';
import 'package:vieneu_tts/vieneu_tts.dart';

/// Demo tối giản — chỉ là cầu nối UI → plugin; KHÔNG inference trong Dart.
///
/// Profile truyền `steps: 12` tường minh để khớp production (default SDK vẫn 8).
/// Cần gói model tại `assets/pkg/` (build bằng host/build_mobile_package.py).
void main() => runApp(const VieNeuTtsDemoApp());

class VieNeuTtsDemoApp extends StatelessWidget {
  const VieNeuTtsDemoApp({super.key});

  @override
  Widget build(BuildContext context) => const MaterialApp(
        title: 'VieNeu-TTS demo',
        home: DemoPage(),
      );
}

class DemoPage extends StatefulWidget {
  const DemoPage({super.key});

  @override
  State<DemoPage> createState() => _DemoPageState();
}

class _DemoPageState extends State<DemoPage> {
  // Production: INT8 · 12 steps · comfort + gate + spectral — khớp Android/iOS.
  final _tts = VieNeuTts(profile: const GenerationProfile(steps: 12));

  final _text = TextEditingController(
    text: 'Năm mười lăm tuổi, Tiêu Viêm từng là thiên tài trẻ nhất của gia tộc Tiêu.',
  );

  Reader? _reader;
  StreamSubscription<ReaderEvent>? _sub;
  List<Voice> _voices = const [];
  String? _voiceId;
  String _status = 'chưa install';
  String _metricsLine = '—';

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    try {
      _setStatus('đang install…');
      await _tts.install(assetPath: 'assets/pkg');
      _setStatus('đang load model…');
      await _tts.load();

      final voices = await _tts.voiceManager.listPresets();
      final reader = await _tts.createReader();
      _sub = reader.events.listen(_onEvent);

      setState(() {
        _voices = voices;
        _voiceId = voices.isNotEmpty ? voices.first.id : null;
        _reader = reader;
        _status = 'sẵn sàng (${voices.length} giọng)';
      });
    } catch (e) {
      _setStatus('Lỗi khởi tạo: $e');
    }
  }

  void _onEvent(ReaderEvent e) {
    switch (e) {
      case OnProgress(:final chunkIndex, :final nChunks):
        _setStatus('chunk ${chunkIndex + 1}/$nChunks');
      case OnDone(:final metrics):
        setState(() {
          _metricsLine = 'RTF ${metrics.rtf?.toStringAsFixed(2) ?? '—'} · '
              'gen ${metrics.generationMs ?? '—'} ms · '
              '${metrics.audioSeconds?.toStringAsFixed(1) ?? '—'} s · '
              '${metrics.nChunks} chunk';
          _status = 'xong';
        });
      case OnUnderrunWarning():
        _setStatus('cảnh báo underrun (buffer cạn)');
      case OnErrorEvent(:final error):
        _setStatus('lỗi: $error');
    }
  }

  Future<void> _speak() async {
    final reader = _reader;
    if (reader == null) return;
    try {
      if (_voiceId != null) await _tts.voiceManager.select(_voiceId!);
      await reader.cancel();
      await reader.setPrefetchDepth(3);
      await reader.enqueue(_text.text);
      await reader.play();
      _setStatus('đang phát…');
    } catch (e) {
      _setStatus('lỗi: $e');
    }
  }

  void _setStatus(String s) {
    if (!mounted) return;
    setState(() => _status = s);
  }

  @override
  void dispose() {
    _sub?.cancel();
    _tts.dispose();
    _text.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('VieNeu-TTS v3 Nano — Android')),
        body: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              TextField(
                controller: _text,
                minLines: 3,
                maxLines: 6,
                decoration: const InputDecoration(border: OutlineInputBorder()),
              ),
              const SizedBox(height: 12),
              DropdownButtonFormField<String>(
                initialValue: _voiceId,
                decoration: const InputDecoration(labelText: 'Giọng'),
                items: [
                  for (final v in _voices)
                    DropdownMenuItem(value: v.id, child: Text(v.name)),
                ],
                onChanged: (v) => setState(() => _voiceId = v),
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: FilledButton.icon(
                      onPressed: _speak,
                      icon: const Icon(Icons.play_arrow),
                      label: const Text('Phát'),
                    ),
                  ),
                  const SizedBox(width: 8),
                  IconButton.filledTonal(
                    onPressed: () => _reader?.pause(),
                    icon: const Icon(Icons.pause),
                  ),
                  const SizedBox(width: 8),
                  IconButton.filledTonal(
                    onPressed: () => _reader?.cancel(),
                    icon: const Icon(Icons.stop),
                  ),
                ],
              ),
              const SizedBox(height: 16),
              Text('Trạng thái: $_status'),
              const SizedBox(height: 4),
              Text('Metrics: $_metricsLine', style: Theme.of(context).textTheme.bodySmall),
            ],
          ),
        ),
      );
}
