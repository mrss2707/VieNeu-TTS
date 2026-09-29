import 'metrics.dart';

/// Event playback/generation (06 §6.3).
sealed class ReaderEvent {
  const ReaderEvent();
}

class OnProgress extends ReaderEvent {
  final int chunkIndex;
  final int nChunks;
  const OnProgress(this.chunkIndex, this.nChunks);
}

class OnDone extends ReaderEvent {
  final Metrics metrics;
  const OnDone(this.metrics);
}

class OnUnderrunWarning extends ReaderEvent {
  const OnUnderrunWarning();
}

class OnErrorEvent extends ReaderEvent {
  final Object error;
  const OnErrorEvent(this.error);
}

/// Facade Reader — gọi MethodChannel/EventChannel native.
/// Playback nằm hoàn toàn phía native (PCM không qua channel).
class Reader {
  final void Function(String method, [Map<String, Object?>? args]) _invoke;
  final Stream<ReaderEvent> events;

  Reader(this._invoke, this.events);

  Future<void> enqueue(String text) =>
      _call('enqueue', {'text': text});

  Future<void> play() => _call('play');
  Future<void> pause() => _call('pause');
  Future<void> cancel() => _call('cancel');

  Future<void> setPrefetchDepth(int depth) =>
      _call('setPrefetchDepth', {'depth': depth});

  Future<Metrics> metrics() async {
    final raw = await _invokeWithResult('metrics');
    return Metrics.fromMap(raw ?? const {});
  }

  Future<void> _call(String method, [Map<String, Object?>? args]) async {
    _invoke(method, args);
  }

  Future<Map<Object?, Object?>?> _invokeWithResult(String method) async {
    // Placeholder: real impl trả result từ channel; mock trong test.
    return null;
  }
}
