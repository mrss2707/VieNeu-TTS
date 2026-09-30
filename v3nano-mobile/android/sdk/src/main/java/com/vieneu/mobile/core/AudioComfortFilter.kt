package com.vieneu.mobile.core

/**
 * Comfort DSP chain — hậu kỳ sau khi join PCM (mặc định ON trong [GenerationProfile]).
 *
 * Mục tiêu nghe lâu không gắt / không xé (validated trên iPhone loa ngoài, 2026-09-30):
 *  1. [TrebleFilter] — LP 6 kHz mix 0.5 (bù steps=8, §0.8)
 *  2. Presence dip 3 kHz −3 dB Q=1 — cắt band loa phone bơm (“gắt”)
 *  3. High-shelf 5.5 kHz −2.5 dB — de-ess / bớt “xé” phụ âm
 *  4. tanh soft-clip + peak-normalize −1 dBFS — hết clip cứng
 *
 * Hệ số biquad tính sẵn (RBJ, fs=24 kHz) — khớp NanoTtsEngine.mm trên iOS.
 * Chi phí ~vài ms / 30 s audio (không đáng kể RTF).
 *
 * Sửa hệ số = đổi audio contract → bump [GenerationProfile.PROFILE_REVISION].
 */
object AudioComfortFilter {
    const val SAMPLE_RATE = 24_000

    // Presence peaking 3 kHz, gain −3 dB, Q=1
    const val PRES_B0 = 0.9135889493293752
    const val PRES_B1 = -0.9957854991465043
    const val PRES_B2 = 0.49466440877807316
    const val PRES_A1 = -0.9957854991465043
    const val PRES_A2 = 0.4082533581074483

    // High-shelf 5.5 kHz, −2.5 dB
    const val SHELF_B0 = 0.8564610806506162
    const val SHELF_B1 = -0.05949127659576462
    const val SHELF_B2 = 0.14761344731755777
    const val SHELF_A1 = -0.23685227176481605
    const val SHELF_A2 = 0.18143552313722544

    const val SOFT_CLIP_DRIVE = 1.15f
    /** 10^(−1/20) — peak mục tiêu −1 dBFS. */
    const val TARGET_PEAK = 0.8912509381337456f

    // Noise-floor gate (rev 3) — downward expander mềm, bỏ “lạo xạo” nền / quantization.
    /** −48 dBFS — dưới ngưỡng này coi là noise floor. */
    const val NF_THRESHOLD = 0.003981071705534973f
    /** Gain tối thiểu khi env ≈ 0 (mute phần lặng). */
    const val NF_FLOOR_GAIN = 0.0f
    /** Envelope attack 1 ms / release 12 ms — bám mức thật, không treo gate sau câu. */
    const val NF_ENV_ATTACK_SEC = 0.001
    const val NF_ENV_RELEASE_SEC = 0.012
    /** Gain mở 5 ms (speech lên) / đóng 30 ms (hết chữ, không cắt đuôi). */
    const val NF_ATTACK_SEC = 0.005
    const val NF_RELEASE_SEC = 0.030
    /** Hold 20 ms — không pump giữa các âm tiết. */
    const val NF_HOLD_SEC = 0.020

    // Spectral subtraction (rev 3b) — khớp NanoTtsEngine.mm / ApplySpectralDenoise iOS.
    const val SPEC_NFFT = 512
    const val SPEC_HOP = 256
    const val SPEC_OVER_SUB = 1.8f
    const val SPEC_GAIN_FLOOR = 0.22f
    const val SPEC_NOISE_DOWN = 0.92f
    const val SPEC_NOISE_UP = 0.997f
    const val SPEC_GAIN_SMOOTH = 0.65f

    fun biquad(
        wav: FloatArray,
        b0: Double, b1: Double, b2: Double, a1: Double, a2: Double,
    ): FloatArray {
        if (wav.isEmpty()) return wav
        val out = FloatArray(wav.size)
        var z1 = 0.0
        var z2 = 0.0
        for (i in wav.indices) {
            val x = wav[i].toDouble()
            val y = b0 * x + z1
            z1 = b1 * x + z2 - a1 * y
            z2 = b2 * x - a2 * y
            out[i] = y.toFloat()
        }
        return out
    }

    /**
     * Soft downward expander — giảm gain khi envelope dưới [NF_THRESHOLD].
     * Quadratic knee từ [NF_FLOOR_GAIN] → 1.0; attack/release one-pole.
     * Chặn “lạo xạo” nền (INT8 quantization / residual) mà không đổi giọng chữ.
     */
    fun applyNoiseFloor(
        wav: FloatArray,
        threshold: Float = NF_THRESHOLD,
        floorGain: Float = NF_FLOOR_GAIN,
        envAttackSec: Double = NF_ENV_ATTACK_SEC,
        envReleaseSec: Double = NF_ENV_RELEASE_SEC,
        openSec: Double = NF_ATTACK_SEC,
        closeSec: Double = NF_RELEASE_SEC,
        holdSec: Double = NF_HOLD_SEC,
        sampleRate: Int = SAMPLE_RATE,
    ): FloatArray {
        if (wav.isEmpty()) return wav
        val envAtk = Math.exp(-1.0 / (envAttackSec * sampleRate))
        val envRel = Math.exp(-1.0 / (envReleaseSec * sampleRate))
        val gOpen = Math.exp(-1.0 / (openSec * sampleRate))
        val gClose = Math.exp(-1.0 / (closeSec * sampleRate))
        val holdSamples = (holdSec * sampleRate).toInt().coerceAtLeast(1)
        val out = FloatArray(wav.size)
        var env = 0.0
        var gain = 1.0
        var holdLeft = 0
        val invThresh = 1.0 / threshold
        for (i in wav.indices) {
            val x = wav[i]
            val ax = kotlin.math.abs(x)
            env = if (ax > env) envAtk * env + (1.0 - envAtk) * ax
            else envRel * env + (1.0 - envRel) * ax

            if (env >= threshold) holdLeft = holdSamples
            else if (holdLeft > 0) holdLeft--

            val target = if (holdLeft > 0) {
                1.0
            } else {
                val r = (env * invThresh).coerceIn(0.0, 1.0)
                floorGain + (1.0 - floorGain) * (r * r)
            }
            // open = speech trở lại (nhanh) / close = hết chữ (vừa đủ, không cắt đuôi)
            gain = if (target > gain) gOpen * gain + (1.0 - gOpen) * target
            else gClose * gain + (1.0 - gClose) * target
            out[i] = (x * gain).toFloat()
        }
        return out
    }

    /** In-place iterative radix-2 FFT. inverse=true → inverse (không scale). */
    private fun fftRadix2(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = 2.0 * Math.PI / len * (if (inverse) 1.0 else -1.0)
            val wlenR = Math.cos(ang).toFloat()
            val wlenI = Math.sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wr = 1.0f
                var wi = 0.0f
                for (k in 0 until len / 2) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                    val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                    val nwr = wr * wlenR - wi * wlenI
                    wi = wr * wlenI + wi * wlenR
                    wr = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * STFT spectral subtraction — giảm “lạo xạo” TRỘN trong lúc nói
     * (gate chỉ phát huy ở khoảng trống). Tham số khớp iOS ApplySpectralDenoise.
     */
    fun applySpectralDenoise(
        wav: FloatArray,
        nfft: Int = SPEC_NFFT,
        hop: Int = SPEC_HOP,
        overSub: Float = SPEC_OVER_SUB,
        gainFloor: Float = SPEC_GAIN_FLOOR,
        noiseDown: Float = SPEC_NOISE_DOWN,
        noiseUp: Float = SPEC_NOISE_UP,
        gainSmooth: Float = SPEC_GAIN_SMOOTH,
    ): FloatArray {
        if (wav.size < nfft) return wav
        val bins = nfft / 2 + 1
        val nFrames = 1 + (wav.size - nfft) / hop

        val hann = FloatArray(nfft) { i ->
            (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / nfft)).toFloat()
        }
        val out = FloatArray(wav.size + nfft)
        val olaWeight = FloatArray(wav.size + nfft)
        val noise = FloatArray(bins)
        val gainSm = FloatArray(bins) { 1.0f }
        val re = FloatArray(nfft)
        val im = FloatArray(nfft)

        for (f in 0 until nFrames) {
            val off = f * hop
            for (i in 0 until nfft) {
                re[i] = wav[off + i] * hann[i]
                im[i] = 0f
            }
            fftRadix2(re, im, inverse = false)

            for (k in 0 until bins) {
                val p = re[k] * re[k] + im[k] * im[k]
                if (f == 0) noise[k] = p
                else if (p < noise[k]) noise[k] = noiseDown * noise[k] + (1 - noiseDown) * p
                else noise[k] = noiseUp * noise[k] + (1 - noiseUp) * p

                var diff = p - overSub * noise[k]
                if (diff < 0f) diff = 0f
                var g = Math.sqrt((diff / (p + 1e-12f)).toDouble()).toFloat()
                if (g < gainFloor) g = gainFloor
                if (g > 1f) g = 1f
                gainSm[k] = gainSmooth * gainSm[k] + (1 - gainSmooth) * g
                re[k] *= gainSm[k]
                im[k] *= gainSm[k]
            }
            // Hermitian mirror
            for (k in 1 until nfft / 2) {
                re[nfft - k] = re[k]
                im[nfft - k] = -im[k]
            }
            fftRadix2(re, im, inverse = true)
            val scale = 1f / nfft
            for (i in 0 until nfft) {
                out[off + i] += re[i] * scale * hann[i]
                olaWeight[off + i] += hann[i] * hann[i]
            }
        }

        val y = FloatArray(wav.size)
        for (i in y.indices) {
            val w = olaWeight[i]
            y[i] = if (w > 1e-8f) out[i] / w else out[i]
        }
        return y
    }

    /** Treble + presence dip + shelf + optional spectral + optional noise gate + soft-clip + peak-norm. */
    fun apply(
        wav: FloatArray,
        noiseFloorEnabled: Boolean = true,
        spectralEnabled: Boolean = false,
    ): FloatArray {
        if (wav.isEmpty()) return wav
        var x = TrebleFilter.apply(wav)
        x = biquad(x, PRES_B0, PRES_B1, PRES_B2, PRES_A1, PRES_A2)
        x = biquad(x, SHELF_B0, SHELF_B1, SHELF_B2, SHELF_A1, SHELF_A2)
        if (spectralEnabled) x = applySpectralDenoise(x)
        if (noiseFloorEnabled) x = applyNoiseFloor(x)

        val drive = SOFT_CLIP_DRIVE
        val norm = (1.0 / Math.tanh(drive.toDouble())).toFloat()
        var peak = 0f
        for (i in x.indices) {
            x[i] = Math.tanh((drive * x[i]).toDouble()).toFloat() * norm
            val a = kotlin.math.abs(x[i])
            if (a > peak) peak = a
        }
        if (peak > 1e-8f) {
            val g = TARGET_PEAK / peak
            for (i in x.indices) x[i] *= g
        }
        return x
    }
}
