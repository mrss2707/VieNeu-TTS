package com.vieneu.mobile.engine

/**
 * Reuse buffer input/output — tránh copy JNI (04 §4.3).
 * Một generation job active → pool nhỏ là đủ; mở rộng khi có concurrent jobs.
 */
class TensorPool(
    private val maxPhoneLen: Int = 512,
    private val speakerDim: Int = 192,
    private val styleDim: Int = 50 * 256,
) {
    private var phoneBuf: LongArray = LongArray(maxPhoneLen)
    private var speakerBuf: FloatArray = FloatArray(speakerDim)
    private var styleBuf: FloatArray = FloatArray(styleDim)

    /** Copy [src] vào buffer dùng chung, trả slice đúng độ dài. */
    fun borrowPhoneIds(src: LongArray): LongArray {
        require(src.size <= maxPhoneLen) { "phoneIds ${src.size} > pool $maxPhoneLen" }
        if (phoneBuf.size < src.size) phoneBuf = LongArray(maxPhoneLen)
        System.arraycopy(src, 0, phoneBuf, 0, src.size)
        return phoneBuf.copyOf(src.size)
    }

    fun borrowSpeaker(src: FloatArray): FloatArray {
        require(src.size == speakerDim) { "speakerEmb ${src.size} != $speakerDim" }
        System.arraycopy(src, 0, speakerBuf, 0, src.size)
        return speakerBuf
    }

    fun borrowStyle(src: FloatArray): FloatArray {
        require(src.size == styleDim) { "style ${src.size} != $styleDim" }
        System.arraycopy(src, 0, styleBuf, 0, src.size)
        return styleBuf
    }
}
