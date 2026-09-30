package com.vieneu.mobile.core

/**
 * Cấu hình sinh một lần (04-sdk-android.md §4.2): mọi thứ rẻ-để-sửa đi qua đây và có
 * [profileRevision] — khi đổi default, bump revision để integrator phát hiện cấu hình
 * cũ qua log/metrics (không âm thầm đổi hành vi).
 *
 * [comfortFilterEnabled] (rev 2, default ON): chuỗi [AudioComfortFilter] sau join —
 * treble + presence dip + de-ess shelf + soft-clip. [trebleFilterEnabled] giữ lại để
 * compat API cũ; khi comfort ON thì treble nằm trong chain.
 * [noiseFloorEnabled] (rev 3, default ON): downward expander −48 dBFS — hết “lạo xạo” nền.
 */
data class GenerationProfile(
    val profileRevision: Int = PROFILE_REVISION,
    val steps: Int = 8,
    val sway: Double = -1.0,
    val cfg: Double = 3.0,
    val threads: Int = 4,
    val providerOrder: List<String> = listOf("CPU", "XNNPACK"),
    val maxCharsPerChunk: Int = 140,
    val trebleFilterEnabled: Boolean = true,
    val comfortFilterEnabled: Boolean = true,
    val noiseFloorEnabled: Boolean = true,
) {
    init {
        require(steps >= 1) { "steps must be >= 1, got $steps" }
        require(maxCharsPerChunk > 0) { "maxCharsPerChunk must be > 0, got $maxCharsPerChunk" }
        require(cfg >= 0.0) { "cfg must be >= 0, got $cfg" }
    }

    companion object {
        /** rev 3: noise-floor downward expander default ON (bỏ “lạo xạo” nền INT8). */
        const val PROFILE_REVISION = 3
    }
}