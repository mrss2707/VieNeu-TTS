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

final class CompatError extends EngineError {
  const CompatError(super.message) : super('E_COMPAT');
}

final class ChecksumError extends EngineError {
  const ChecksumError(super.message) : super('E_MODEL_CHECKSUM');
}

final class CorruptError extends EngineError {
  const CorruptError(super.message) : super('E_MODEL_CORRUPT');
}

final class MissingError extends EngineError {
  const MissingError(super.message) : super('E_MODEL_MISSING');
}

final class ProviderInitError extends EngineError {
  const ProviderInitError(super.message) : super('E_PROVIDER_INIT');
}

final class CacheFullError extends EngineError {
  const CacheFullError(super.message) : super('E_CACHE_FULL');
}

final class DiskFullError extends EngineError {
  const DiskFullError(super.message) : super('E_DISK_FULL');
}

final class CanceledError extends EngineError {
  const CanceledError(super.message) : super('E_CANCELED');
}

final class UnderrunError extends EngineError {
  const UnderrunError(super.message) : super('E_UNDERRUN');
}

final class ThermalT3Error extends EngineError {
  const ThermalT3Error(super.message) : super('E_THERMAL_T3');
}

final class InvalidInputError extends EngineError {
  const InvalidInputError(super.message) : super('E_INVALID_INPUT');
}

final class InternalError extends EngineError {
  const InternalError(super.message) : super('E_INTERNAL');
}
