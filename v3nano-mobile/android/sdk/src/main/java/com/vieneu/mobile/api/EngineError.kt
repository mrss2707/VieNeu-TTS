package com.vieneu.mobile.api

/**
 * Mã lỗi SDK — khớp contract ModelRegistry (03-model-package.md §3.4) và
 * 07-kiem-thu.md §7.4. Integrator map sang UI; không expose exception nội bộ.
 */
sealed class EngineError(
    open val code: String,
    override val message: String,
    override val cause: Throwable? = null,
) : Exception(message, cause) {
    data class Checksum(
        override val message: String = "Checksum gói model không khớp",
        override val cause: Throwable? = null,
    ) : EngineError("E_MODEL_CHECKSUM", message, cause)

    data class Corrupt(
        override val message: String = "File model hỏng hoặc thiếu",
        override val cause: Throwable? = null,
    ) : EngineError("E_MODEL_CORRUPT", message, cause)

    data class Compat(
        override val message: String = "Gói model không tương thích runtime/SDK",
        override val cause: Throwable? = null,
    ) : EngineError("E_COMPAT", message, cause)

    data class ProviderInit(
        val provider: String,
        override val message: String = "Không khởi tạo được provider $provider",
        override val cause: Throwable? = null,
    ) : EngineError("E_PROVIDER_INIT", message, cause)

    data class Cancelled(
        override val message: String = "Job đã bị hủy",
    ) : EngineError("E_CANCELLED", message)

    data class InvalidInput(
        override val message: String = "Input không hợp lệ",
    ) : EngineError("E_INVALID_INPUT", message)

    data class Internal(
        override val message: String,
        override val cause: Throwable? = null,
    ) : EngineError("E_INTERNAL", message, cause)
}
