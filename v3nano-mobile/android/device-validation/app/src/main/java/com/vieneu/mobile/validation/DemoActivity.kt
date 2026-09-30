package com.vieneu.mobile.validation

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.util.Log
import com.vieneu.mobile.core.AudioComfortFilter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Supermeo Mobile TTS demo (mirror iOS ViewController):
 * đoạn văn *Đấu phá thương khung* + nút FP32 / INT8 — cả hai chạy comfort DSP chain
 * (treble + presence dip + shelf + soft-clip) trước khi phát.
 *
 * Graph đọc từ external-files/nano_graphs_fp32 hoặc nano_graphs_int8 (adb push).
 */
class DemoActivity : Activity() {
    companion object {
        const val TAG = "SupermeoDemo"
    }

    private lateinit var status: TextView
    private lateinit var btnFp32: Button
    private lateinit var btnInt8: Button
    private val io = Executors.newSingleThreadExecutor()
    private var playing: AudioTrack? = null

    private val paragraph = """
        Năm mười lăm tuổi, Tiêu Viêm từng là thiên tài trẻ nhất của gia tộc Tiêu.
        Thế nhưng, chỉ sau một đêm, đấu khí trong cơ thể hắn đột nhiên biến mất không dấu vết.
        Bốn năm sau, thiếu niên năm nào đứng giữa quảng trường, mặc cho ánh mắt chế giễu của tộc nhân,
        hắn siết chặt nắm đấm — nhất định phải lấy lại tất cả những gì đã mất.
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val title = TextView(this).apply {
            text = "Supermeo Mobile TTS"
            textSize = 20f
            gravity = Gravity.CENTER
        }
        val label = TextView(this).apply {
            text = "Đoạn văn (Đấu phá thương khung)"
            textSize = 14f
        }
        val body = TextView(this).apply {
            text = paragraph
            textSize = 15f
        }
        btnFp32 = Button(this).apply {
            text = "Đọc — FP32"
            setOnClickListener { speak("fp32") }
        }
        btnInt8 = Button(this).apply {
            text = "Đọc — INT8"
            setOnClickListener { speak("int8") }
        }
        status = TextView(this).apply {
            text = "Chọn FP32 hoặc INT8 · comfort DSP ON · giọng Anh Khôi"
            textSize = 13f
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(title)
            addView(label)
            addView(body)
            addView(btnFp32)
            addView(btnInt8)
            addView(status)
        }
        setContentView(ScrollView(this).apply { addView(col) })

        // Test hook: adb shell am start ... --es autoSpeak fp32|int8
        intent.getStringExtra("autoSpeak")?.let { speak(it) }
    }

    private fun speak(precision: String) {
        Log.i(TAG, "speak precision=$precision")
        btnFp32.isEnabled = false
        btnInt8.isEnabled = false
        status.text = "Đang sinh ($precision)…"
        io.execute {
            try {
                val result = synthesize(precision)
                Log.i(TAG, "speak done: $result")
                runOnUiThread {
                    status.text = result
                    btnFp32.isEnabled = true
                    btnInt8.isEnabled = true
                }
            } catch (t: Throwable) {
                Log.e(TAG, "speak failed", t)
                runOnUiThread {
                    status.text = "Lỗi: ${t.message}"
                    btnFp32.isEnabled = true
                    btnInt8.isEnabled = true
                }
            }
        }
    }

    private fun synthesize(precision: String): String {
        val dirName = if (precision == "int8") "nano_graphs_int8" else "nano_graphs_fp32"
        val graphDir = File(getExternalFilesDir(null), dirName)
        if (!graphDir.isDirectory || graphDir.listFiles { f -> f.name.endsWith(".onnx") }?.size != 4) {
            return "Thiếu graph $dirName — adb push 4 onnx vào ${graphDir.absolutePath}"
        }

        val env = ai.onnxruntime.OrtEnvironment.getEnvironment()
        val so = ai.onnxruntime.OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        val tLoad = System.currentTimeMillis()
        val g = NanoBenchmarkCore.Graphs(env, so) { name ->
            File(graphDir, name.substringAfterLast('/')).absolutePath
        }
        val loadMs = System.currentTimeMillis() - tLoad

        val demo = "nano_test_input/demo"
        val meta = NanoBenchmarkCore.assetJson(this, "$demo/global_meta.json")
        val padId = meta.getLong("pad_id")
        val bosId = meta.getLong("bos_id")
        val eosId = meta.getLong("eos_id")
        val fps = meta.getDouble("flow_fps")
        val steps = meta.getInt("steps")
        val cfg = meta.getDouble("cfg")
        val sway = meta.optDouble("sway", -1.0)
        val sr = meta.getInt("sample_rate")

        val spk = NanoBenchmarkCore.assetFloatArray(this, "$demo/voices/Anh_Khoi/spk.bin")
        val style = NanoBenchmarkCore.assetFloatArray(this, "$demo/voices/Anh_Khoi/style.bin")
        val nullSpk = NanoBenchmarkCore.assetFloatArray(this, "$demo/null_spk.bin")
        val nullStyle = NanoBenchmarkCore.assetFloatArray(this, "$demo/null_style.bin")

        val caseDir = "$demo/cases/demo_para"
        val (raw, genMs, nChunks) = NanoBenchmarkCore.runInferenceMultiChunk(
            this, caseDir, g, env,
            spk, style, nullSpk, nullStyle,
            padId, bosId, eosId, fps, steps, cfg, sway, sr,
        )
        g.close()

        val comfortMs0 = System.currentTimeMillis()
        val out = AudioComfortFilter.apply(raw)
        val comfortMs = System.currentTimeMillis() - comfortMs0

        var peak = 0f
        var nan = false
        for (v in out) {
            if (v.isNaN() || v.isInfinite()) nan = true
            val a = kotlin.math.abs(v)
            if (a > peak) peak = a
        }
        playPcm(out, sr)
        val audioSec = out.size.toDouble() / sr
        val rtf = if (audioSec > 0) genMs / 1000.0 / audioSec else 0.0
        return "OK $precision — " +
            "%.1fs audio · %.0f ms sinh · %d chunk · load %.0f ms · comfort %d ms · RTF %.2f · peak %.3f".format(
                audioSec, genMs.toDouble(), nChunks, loadMs.toDouble(), comfortMs, rtf, peak,
            ) + if (nan) " · CÓ NaN!" else " · comfort ON"
    }

    private fun playPcm(pcm: FloatArray, sampleRate: Int) {
        playing?.stop()
        playing?.release()
        val bytes = ByteArray(pcm.size * 2)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (v in pcm) {
            val s = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            bb.putShort(s)
        }
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, bytes.size))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(bytes, 0, bytes.size)
        track.play()
        playing = track
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
        playing?.release()
        playing = null
    }
}
