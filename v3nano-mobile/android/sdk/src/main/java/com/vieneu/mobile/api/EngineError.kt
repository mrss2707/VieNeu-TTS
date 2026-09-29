package com.vieneu.mobile.api

/**
 * Mã lỗi SDK — khớp bảng `08-ci-cd-phat-hanh.md` §8.4 (3 platform thống nhất).
 * Integrator map sang UI; không expose exception nội bộ.
 *
 * | Code | Hành vi chuẩn |
 * |---|---|
 * | E_COMPAT | Từ chối trước khi load, kèm revision cần |
 * | E_MODEL_CHECKSUM | Không kích hoạt; giữ bản cũ |
 * | E_MODEL_CORRUPT | Rollback pointer về revision cũ |
 * | E_MODEL_MISSING | Hướng dẫn install; không tự download khi offline |
 * | E_PROVIDER_INIT | Fallback CPU + log lý do vào metrics |
 * | E_CACHE_FULL / E_DISK_FULL | Dọn LRU → nếu vẫn thiếu → lỗi rõ |
 * | E_CANCELED | Không tính lỗi; dọn queue/cache |
 * | E_UNDERRUN | Warning + tăng ưu tiên chunk gần |
 * | E_THERMAL_T3 | Chỉ báo cáo, không crash |
 */
sealed class EngineError(
    open val code: String,
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause) {

    data class Compat(
        override val message: String = "ABI/model/runtime/sdk không tương thích",
        override val cause: Throwable? = null,
    ) : EngineError("E_COMPAT", message, cause)

    data class Checksum(
        override val message: String = "Checksum/signature gói model sai",
        override val cause: Throwable? = null,
    ) : EngineError("E_MODEL_CHECKSUM", message, cause)

    data class Corrupt(
        override val message: String = "File model hỏng hoặc không phải ONNX",
        override val cause: Throwable? = null,
    ) : EngineError("E_MODEL_CORRUPT", message, cause)

    data class Missing(
        override val message: String = "Không tìm thấy file model",
        override val cause: Throwable? = null,
    ) : EngineError("E_MODEL_MISSING", message, cause)

    data class ProviderInit(
        val provider: String,
        override val message: String = "Không khởi tạo được provider $provider",
        override val cause: Throwable? = null,
    ) : EngineError("E_PROVIDER_INIT", message, cause)

    data class CacheFull(
        override val message: String = "Cache audio đầy",
    ) : EngineError("E_CACHE_FULL", message)

    data class DiskFull(
        override val message: String = "Không đủ dung lượng đĩa",
    ) : EngineError("E_DISK_FULL", message)

    /** E_CANCELED (08 §8.4 — chính tả Mỹ) — không tính là lỗi. */
    data class Canceled(
        override val message: String = "Job đã bị hủy",
    ) : EngineError("E_CANCELED", message)

    data class Underrun(
        override val message: String = "Buffer playback cạn",
    ) : EngineError("E_UNDERRUN", message)

    data class ThermalT3(
        override val message: String = "Thermal SEVERE — đã chuyển tầng 3",
    ) : EngineError("E_THERMAL_T3", message)

    data class InvalidInput(
        override val message: String = "Input không hợp lệ",
    ) : EngineError("E_INVALID_INPUT", message)

    data class Internal(
        override val message: String,
        override val cause: Throwable? = null,
    ) : EngineError("E_INTERNAL", message, cause)
}
