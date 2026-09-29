/// Map 1:1 mã lỗi native (08-ci-cd) — không để PlatformException thô lọt ra UI.
sealed class EngineError implements Exception {
  final String code;
  final String message;
  const EngineError(this.code, this.message);

  @override
  String toString() => '$runtimeType($code): $message';

  static EngineError fromCode(String code, String message) => switch (code) {
        'E_MODEL_CHECKSUM' => ChecksumError(message),
        'E_MODEL_CORRUPT' => CorruptError(message),
        'E_COMPAT' => CompatError(message),
        'E_PROVIDER_INIT' => ProviderInitError(message),
        'E_CANCELLED' => CancelledError(message),
        'E_INVALID_INPUT' => InvalidInputError(message),
        _ => InternalError(message),
      };
}

final class ChecksumError extends EngineError {
  const ChecksumError(super.message) : super('E_MODEL_CHECKSUM');
}

final class CorruptError extends EngineError {
  const CorruptError(super.message) : super('E_MODEL_CORRUPT');
}

final class CompatError extends EngineError {
  const CompatError(super.message) : super('E_COMPAT');
}

final class ProviderInitError extends EngineError {
  const ProviderInitError(super.message) : super('E_PROVIDER_INIT');
}

final class CancelledError extends EngineError {
  const CancelledError(super.message) : super('E_CANCELLED');
}

final class InvalidInputError extends EngineError {
  const InvalidInputError(super.message) : super('E_INVALID_INPUT');
}

final class InternalError extends EngineError {
  const InternalError(super.message) : super('E_INTERNAL');
}
