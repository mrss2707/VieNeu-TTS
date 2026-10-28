package com.vieneu.mobile.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Chạy [AudioComfortFilter.apply] của SDK trên một file float32 (header int32 = số mẫu) và ghi kết quả.
 * Dùng cho so sánh từng mẫu với bản C++ của iOS (ios_dsp_cli). Chạy qua test để dùng classpath của :sdk.
 */
object DspCli {
    fun run(inFile: File, outFile: File, noiseFloor: Boolean, spectral: Boolean) {
        val bb = ByteBuffer.wrap(inFile.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val n = bb.int
        val wav = FloatArray(n).also { bb.asFloatBuffer().get(it) }
        val out = AudioComfortFilter.apply(wav, noiseFloorEnabled = noiseFloor, spectralEnabled = spectral)
        val ob = ByteBuffer.allocate(out.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        out.forEach { ob.putFloat(it) }
        outFile.writeBytes(ob.array())
    }
}
