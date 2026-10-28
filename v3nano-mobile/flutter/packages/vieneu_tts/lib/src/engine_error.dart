/// Map 1:1 mã lỗi native `08-ci-cd-phat-hanh.md` §8.4 — không để PlatformException thô lọt ra UI.
sealed class EngineError implements Exception {
  final String code;
  final String message;
  const EngineError(this.code, this.message);

  @override
  String toString() => '$runtimeType($code): $message';

  static EngineError fromCode(String code, String message) => switch (code) {
        'E_COMPAT' => CompatError(message),
        'E_MODEL_CHECKSUM' => ChecksumError(message),
        'E_MODEL_CORRUPT' => CorruptError(message),
        'E_MODEL_MISSING' => MissingError(message),
        'E_PROVIDER_INIT' => ProviderInitError(message),
        'E_CACHE_FULL' => CacheFullError(message),
        'E_DISK_FULL' => DiskFullError(message),
        'E_CANCELED' => CanceledError(message),
        'E_UNDERRUN' => UnderrunError(message),
        'E_THERMAL_T3' => ThermalT3Error(message),
        'E_INVALID_INPUT' => InvalidInputError(message),
        _ => InternalError(message),
      };
}

// Lưu ý: base dùng positional `EngineError(code, message)` — subclass phải truyền
// TƯỜNG MINH cả hai vị trí (không dùng `super.message` cùng lúc với positional khác).
final class CompatError extends EngineError {
  const CompatError(String message) : super('E_COMPAT', message);
}

final class ChecksumError extends EngineError {
  const ChecksumError(String message) : super('E_MODEL_CHECKSUM', message);
}

final class CorruptError extends EngineError {
  const CorruptError(String message) : super('E_MODEL_CORRUPT', message);
}

final class MissingError extends EngineError {
  const MissingError(String message) : super('E_MODEL_MISSING', message);
}

final class ProviderInitError extends EngineError {
  const ProviderInitError(String message) : super('E_PROVIDER_INIT', message);
}

final class CacheFullError extends EngineError {
  const CacheFullError(String message) : super('E_CACHE_FULL', message);
}

final class DiskFullError extends EngineError {
  const DiskFullError(String message) : super('E_DISK_FULL', message);
}

final class CanceledError extends EngineError {
  const CanceledError(String message) : super('E_CANCELED', message);
}

final class UnderrunError extends EngineError {
  const UnderrunError(String message) : super('E_UNDERRUN', message);
}

final class ThermalT3Error extends EngineError {
  const ThermalT3Error(String message) : super('E_THERMAL_T3', message);
}

final class InvalidInputError extends EngineError {
  const InvalidInputError(String message) : super('E_INVALID_INPUT', message);
}

final class InternalError extends EngineError {
  const InternalError(String message) : super('E_INTERNAL', message);
}
