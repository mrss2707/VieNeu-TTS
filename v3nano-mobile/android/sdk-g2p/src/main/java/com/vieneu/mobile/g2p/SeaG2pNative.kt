package com.vieneu.mobile.g2p

/**
 * Cầu JNI sang lõi Rust sea-g2p (native/sea-g2p-mobile/src-upstream/src/jni_api.rs).
 * Tên hàm khớp `Java_com_vieneu_mobile_g2p_SeaG2pNative_*` — đổi package/tên phải đổi cả bên Rust.
 *
 * Handle là con trỏ native: KHÔNG thread-safe khi dùng chung, mỗi thread dùng handle riêng
 * ([SeaG2pBackend] lo việc này). Hàm native ném [IllegalStateException] khi lỗi.
 */
internal object SeaG2pNative {
    private val loaded: Result<Unit> = runCatching { System.loadLibrary("sea_g2p_rs") }

    fun ensureLoaded() {
        loaded.getOrElse { throw IllegalStateException("Không nạp được libsea_g2p_rs: ${it.message}", it) }
    }

    @JvmStatic external fun nativeOpen(dictPath: String): Long
    @JvmStatic external fun nativeClose(handle: Long)
    @JvmStatic external fun nativeNormalize(handle: Long, text: String, puncNorm: Int): String
    @JvmStatic external fun nativePhonemize(handle: Long, text: String, puncNorm: Int): String
    @JvmStatic external fun nativePuncNorm(text: String): String
}
