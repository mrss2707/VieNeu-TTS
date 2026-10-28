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
 * [spectralDenoiseEnabled] (rev 4, default ON): STFT spectral subtraction — nhiễu trộn khi nói.
 * [deEsserEnabled] (rev 5, default ON): de-esser động 5–8 kHz — hạ phụ âm xát/bật chói bất chợt
 * (đỉnh ~6.6 kHz) mà EQ tĩnh không đủ sức; chỉ tác động khi dải đó trội hơn dải giọng.
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
    val spectralDenoiseEnabled: Boolean = true,
    val deEsserEnabled: Boolean = true,
) {
    init {
        require(steps >= 1) { "steps must be >= 1, got $steps" }
        require(maxCharsPerChunk > 0) { "maxCharsPerChunk must be > 0, got $maxCharsPerChunk" }
        require(cfg >= 0.0) { "cfg must be >= 0, got $cfg" }
    }

    companion object {
        /** rev 6: de-esser mở dải xuống 3.4 kHz; rev 5: de-esser 5–8 kHz + shelf −5 dB; rev 4: spectral denoise ON. */
        const val PROFILE_REVISION = 6
    }
}