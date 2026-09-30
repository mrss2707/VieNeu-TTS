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
 * Supermeo Mobile TTS demo — A/B nhiễu (giống iOS) trên Android:
 *  - INT8 · 12 steps (chốt) · giọng Mỹ Duyên · comfort DSP
 *  1) chỉ comfort (EQ + soft-clip)
 *  2) + noise gate (khoảng trống)
 *  3) + gate + spectral STFT ★ (nhiễu TRONG lúc nói)
 *  - Graph từ external-files/nano_graphs_int8 (adb push)
 */
class DemoActivity : Activity() {
    companion object {
        const val TAG = "SupermeoDemo"
    }

    private lateinit var status: TextView
    private lateinit var btnComfort: Button
    private lateinit var btnGate: Button
    private lateinit var btnFull: Button
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
        btnComfort = Button(this).apply {
            text = "chỉ comfort"
            setOnClickListener { speak(noiseFloor = false, spectral = false) }
        }
        btnGate = Button(this).apply {
            text = "+ noise gate"
            setOnClickListener { speak(noiseFloor = true, spectral = false) }
        }
        btnFull = Button(this).apply {
            text = "+ gate + spectral ★"
            setOnClickListener { speak(noiseFloor = true, spectral = true) }
        }
        status = TextView(this).apply {
            text = "A/B nhiễu · INT8 12 steps · Mỹ Duyên (giống iOS)"
            textSize = 13f
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(title)
            addView(label)
            addView(body)
            addView(btnComfort)
            addView(btnGate)
            addView(btnFull)
            addView(status)
        }
        setContentView(ScrollView(this).apply { addView(col) })

        // Test hook: --es autoSpeak comfort|gate|full
        intent.getStringExtra("autoSpeak")?.let { mode ->
            when (mode.lowercase()) {
                "comfort", "off", "0" -> speak(false, false)
                "gate", "on", "1" -> speak(true, false)
                else -> speak(true, true)
            }
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        btnComfort.isEnabled = enabled
        btnGate.isEnabled = enabled
        btnFull.isEnabled = enabled
    }

    private fun speak(noiseFloor: Boolean, spectral: Boolean) {
        Log.i(TAG, "speak noiseFloor=$noiseFloor spectral=$spectral steps=12")
        setButtonsEnabled(false)
        status.text = "Đang sinh (NF=${if (noiseFloor) "ON" else "OFF"} · spec=${if (spectral) "ON" else "OFF"})…"
        io.execute {
            try {
                val result = synthesize(noiseFloor, spectral)
                Log.i(TAG, "speak done: $result")
                runOnUiThread {
                    status.text = result
                    setButtonsEnabled(true)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "speak failed", t)
                runOnUiThread {
                    status.text = "Lỗi: ${t.message}"
                    setButtonsEnabled(true)
                }
            }
        }
    }

    private fun synthesize(noiseFloor: Boolean, spectral: Boolean): String {
        val steps = 12
        val dirName = "nano_graphs_int8"
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
        val cfg = meta.getDouble("cfg")
        val sway = meta.optDouble("sway", -1.0)
        val sr = meta.getInt("sample_rate")

        val spk = NanoBenchmarkCore.assetFloatArray(this, "$demo/voices/My_Duyen/spk.bin")
        val style = NanoBenchmarkCore.assetFloatArray(this, "$demo/voices/My_Duyen/style.bin")
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
        val out = AudioComfortFilter.apply(
            raw,
            noiseFloorEnabled = noiseFloor,
            spectralEnabled = spectral,
        )
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
        val nfTag = if (noiseFloor) "NF ON" else "NF OFF"
        val spTag = if (spectral) "spec ON" else "spec OFF"
        return "OK INT8 · $steps steps · $nfTag · $spTag — " +
            "%.1fs audio · %.0f ms sinh · %d chunk · load %.0f ms · dsp %d ms · RTF %.2f · peak %.3f".format(
                audioSec, genMs.toDouble(), nChunks, loadMs.toDouble(), comfortMs, rtf, peak,
            ) + if (nan) " · CÓ NaN!" else " · Mỹ Duyên"
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
