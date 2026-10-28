package com.vieneu.mobile.flutter

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.vieneu.mobile.api.EngineError
import com.vieneu.mobile.api.Metrics
import com.vieneu.mobile.api.ModelManager
import com.vieneu.mobile.api.Reader
import com.vieneu.mobile.api.VieNeuTts
import com.vieneu.mobile.core.GenerationProfile
import com.vieneu.mobile.core.ChunkPhonemizer
import com.vieneu.mobile.core.G2pBackend
import com.vieneu.mobile.core.ModelConstants
import com.vieneu.mobile.core.ModelRegistry
import com.vieneu.mobile.core.PhoneEncoder
import com.vieneu.mobile.core.SeaTextNormalizer
import com.vieneu.mobile.core.VoiceIndex
import com.vieneu.mobile.g2p.SeaG2pAssets
import com.vieneu.mobile.g2p.SeaG2pBackend
import com.vieneu.mobile.ort.OrtSynthesisEngine
import com.vieneu.mobile.player.AndroidAudioPlayer
import com.vieneu.mobile.player.AudioPlayer
import com.vieneu.mobile.player.RecordingAudioPlayer
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Flutter plugin host Android (06-sdk-flutter.md §6.4) — cầu MỎNG sang SDK native.
 *
 * MethodChannel `vieneu_tts/methods` + EventChannel `vieneu_tts/events` (thủ công, không
 * Pigeon). KHÔNG inference trong Dart; playback/DSP do SDK lo.
 *
 * Bắc cầu lệch API (host xử lý, KHÔNG sửa SDK cho khớp Dart):
 * - Dart `install{assetPath}` ≠ SDK `install(packageDir, revision)`: host copy thư mục
 *   asset ra filesDir rồi gọi [ModelManager.install], nhớ revision dir làm `modelDir`.
 * - Dart `load()` ≠ SDK `load(modelDir)`: host dựng [GenerationProfile] từ map Dart gửi.
 * - Registry giọng trong gói dùng schema `voice_id`/`display_name` + file `.bin` riêng,
 *   KHÁC `VoiceManager.Voice` của SDK → host tự đọc index + nạp embedding từ `.bin`.
 */
class VieNeuTtsPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    private companion object {
        const val TAG = "VieNeuTtsPlugin"
        const val METHODS = "vieneu_tts/methods"
        const val EVENTS = "vieneu_tts/events"
        const val FLUTTER_ASSET_PREFIX = "flutter_assets"
    }

    private lateinit var context: Context
    private val main = Handler(Looper.getMainLooper())

    private var methodChannel: MethodChannel? = null
    private var eventChannel: EventChannel? = null
    private var eventSink: EventChannel.EventSink? = null

    private var modelDir: File? = null     // revisions/<revision>
    private var tts: VieNeuTts? = null
    private var g2p: G2pBackend? = null
    private var player: AndroidAudioPlayer? = null
    private var recorder: RecordingAudioPlayer? = null
    private var reader: Reader? = null

    private var voices: List<VoiceEntry> = emptyList()
    private var selectedVoiceId: String? = null
    private val embCache = HashMap<String, FloatArray>()

    private data class VoiceEntry(
        val id: String,
        val name: String,
        val werReference: Double?,
        val spkFile: File?,
        val styleFile: File?,
    )

    // ── FlutterPlugin ─────────────────────────────────────────────────────────

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        methodChannel = MethodChannel(binding.binaryMessenger, METHODS).apply { setMethodCallHandler(this@VieNeuTtsPlugin) }
        eventChannel = EventChannel(binding.binaryMessenger, EVENTS).apply { setStreamHandler(this@VieNeuTtsPlugin) }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel?.setMethodCallHandler(null)
        methodChannel = null
        eventChannel?.setStreamHandler(null)
        eventChannel = null
        eventSink = null
        reader = null
        tts?.close()
        tts = null
        g2p?.close()
        g2p = null
        recorder?.close()
        recorder = null
        player?.release()
        player = null
    }

    // ── EventChannel ──────────────────────────────────────────────────────────

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) { eventSink = events }

    override fun onCancel(arguments: Any?) { eventSink = null }

    // ── MethodChannel ─────────────────────────────────────────────────────────

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "install" -> result.success(install(requireAssetPath(call)))
                "load" -> result.success(load(call.argument<Map<String, Any?>>("profile")))
                "createReader" -> { createReader(); result.success(null) }
                "enqueue" -> { enqueue(call.argument<String>("text").orEmpty()); result.success(null) }
                "play" -> { reader?.play(); result.success(null) }
                "pause" -> { reader?.pause(); result.success(null) }
                "cancel" -> { reader?.cancel(); result.success(null) }
                "setPrefetchDepth" -> {
                    reader?.setPrefetchDepth(call.argument<Int>("depth") ?: 3)
                    result.success(null)
                }
                "metrics" -> result.success(metricsMap())
                "listPresets" -> result.success(listPresets())
                "selectVoice" -> { selectVoice(call.argument<String>("id").orEmpty()); result.success(null) }
                else -> result.notImplemented()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onMethodCall ${call.method} failed", t)
            result.error(codeOf(t), t.message ?: t.toString(), null)
        }
    }

    // ── install / load ────────────────────────────────────────────────────────

    private fun requireAssetPath(call: MethodCall): String =
        call.argument<String>("assetPath")?.takeIf { it.isNotBlank() }
            ?: throw EngineError.InvalidInput("install cần assetPath")

    /** Copy asset bundle → filesDir, cài qua [ModelManager], nhớ `modelDir`. */
    private fun install(assetPath: String): Map<String, Any?> {
        val root = File(context.filesDir, "vieneu_tts")
        root.mkdirs()
        val staging = File(root, "staging")
        if (staging.exists()) staging.deleteRecursively()
        val assetRoot = if (assetPath.startsWith("$FLUTTER_ASSET_PREFIX/")) assetPath else "$FLUTTER_ASSET_PREFIX/$assetPath"
        copyAssetDir(assetRoot, staging)

        val registry = ModelRegistry(File(root, "models"))
        val revision = readRevision(staging) ?: "default"
        val installed = ModelManager(registry).install(staging, revision)
        val dir = File(File(root, "models/revisions"), installed)
        modelDir = dir

        loadTokenizer(dir) ?: Log.w(TAG, "tokenizer.json không thấy trong $dir — phoneEncoder sẽ trống")
        loadVoices(dir)
        // Mọi method của channel trả Map (khớp kiểu của cầu Dart) — không trả String trần.
        return mapOf("revision" to installed, "modelDir" to dir.absolutePath)
    }

    private fun readRevision(pkg: File): String? = try {
        val m = JSONObject(File(pkg, "manifest.json").readText())
        m.optString("model_revision").takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    private fun loadTokenizer(modelDir: File): PhoneEncoder? {
        val f = modelDir.walkTopDown().firstOrNull { it.isFile && it.name == "tokenizer.json" } ?: return null
        return PhoneEncoder.fromTokenizerJson(f.readText())
    }

    /**
     * Nạp index giọng của gói qua [VoiceIndex] (đường dẫn tính từ `assets/`, thiếu file = lỗi).
     * Đọc trước TOÀN BỘ vector để gói hỏng lộ ra ngay lúc install — không phải giữa lúc đang đọc,
     * và không bao giờ rơi về vector 0 (model sẽ tự chọn giọng → lẫn nam/nữ).
     */
    private fun loadVoices(modelDir: File) {
        embCache.clear()
        val f = modelDir.walkTopDown().firstOrNull { it.isFile && it.name == "voices_v3_nano_mobile.json" }
            ?: throw EngineError.Missing("Gói model thiếu voices_v3_nano_mobile.json")
        val index = try {
            VoiceIndex.load(f)
        } catch (e: IllegalArgumentException) {
            throw EngineError.Corrupt("Index giọng không hợp lệ: ${e.message}", e)
        }
        if (index.voices.isEmpty()) throw EngineError.Corrupt("Index giọng rỗng")
        val out = ArrayList<VoiceEntry>(index.voices.size)
        for (v in index.voices) {
            try {
                embCache[v.speakerEmbFile.absolutePath] = VoiceIndex.readEmbedding(v.speakerEmbFile, VoiceIndex.SPK_DIM)
                embCache[v.styleFile.absolutePath] = VoiceIndex.readEmbedding(v.styleFile, VoiceIndex.STYLE_DIM)
            } catch (e: IllegalArgumentException) {
                throw EngineError.Corrupt("Giọng ${v.id}: ${e.message}", e)
            }
            out.add(VoiceEntry(v.id, v.name, v.werReference, v.speakerEmbFile, v.styleFile))
        }
        voices = out
        selectedVoiceId = index.defaultVoiceId
    }

    /**
     * null_spk / null_style (CFG) + flow_fps lấy từ GÓI, không dùng mặc định của engine.
     * Mặc định (0 / 0 / 12,5) làm CFG sai hướng và lệch số frame → giọng lẫn nam/nữ.
     */
    private fun loadConstants(modelDir: File): ModelConstants {
        val npz = modelDir.walkTopDown().firstOrNull { it.isFile && it.name == "constants.npz" }
            ?: throw EngineError.Missing("Gói model thiếu constants.npz (null_spk/null_style)")
        val tok = modelDir.walkTopDown().firstOrNull { it.isFile && it.name == "tokenizer.json" }
            ?: throw EngineError.Missing("Gói model thiếu tokenizer.json (flow_fps)")
        val fps = JSONObject(tok.readText()).optDouble("flow_fps", Double.NaN)
        if (fps.isNaN()) throw EngineError.Corrupt("tokenizer.json thiếu flow_fps")
        return try {
            ModelConstants.load(npz, fps)
        } catch (e: IllegalArgumentException) {
            throw EngineError.Corrupt("constants.npz không hợp lệ: ${e.message}", e)
        }
    }

    private fun load(profileMap: Map<String, Any?>?): Map<String, Any?> {
        val dir = modelDir ?: throw EngineError.Internal("install(assetPath) trước khi load()")
        val profile = profileFromMap(profileMap)
        val constants = loadConstants(dir)
        val tokenizer = loadTokenizer(dir) ?: PhoneEncoder(emptyMap(), emptyMap(), 1, 2, 0)
        // G2P thật (sea-g2p Rust): text → normalize → phoneme ngay trên máy. Thiếu từ điển/thư viện
        // thì load() thất bại rõ ràng — KHÔNG lùi về đẩy chữ thô vào PhoneEncoder (rơi ~25% ký tự).
        val backend: G2pBackend = try {
            SeaG2pBackend(SeaG2pAssets.install(context).absolutePath)
        } catch (e: Exception) {
            throw EngineError.Missing("Không khởi tạo được G2P (sea-g2p): ${e.message}", e)
        }
        val oldPlayer = player
        val androidPlayer = AndroidAudioPlayer(context)
        // Chẩn đoán (mặc định TẮT): ghi đúng PCM mà player nhận = audio SAU INT8 + DSP, thứ người nghe nghe thật.
        val recordPcm = (profileMap?.get("debugRecordPcm") as? Boolean) ?: false
        recorder?.close()
        recorder = if (recordPcm) {
            val dir = File(context.filesDir, "debug_pcm").apply { mkdirs() }
            RecordingAudioPlayer(androidPlayer, File(dir, "played.f32"), File(dir, "played_index.csv"))
        } else null
        val p: AudioPlayer = recorder ?: androidPlayer
        player = androidPlayer
        val chunkPhonemizer = ChunkPhonemizer(backend)
        val instance = VieNeuTts.Builder()
            .profile(profile)
            .modelRegistry(ModelRegistry(File(File(context.filesDir, "vieneu_tts"), "models")))
            .engine(
                OrtSynthesisEngine(
                    threads = profile.threads,
                    provider = profile.providerOrder.firstOrNull() ?: "CPU",
                    steps = profile.steps,
                    cfg = profile.cfg,
                    sway = profile.sway,
                    fps = constants.flowFps,
                    nullSpk = constants.nullSpk,
                    nullStyle = constants.nullStyle,
                ),
            )
            .phoneEncoder(tokenizer)
            .normalizer(SeaTextNormalizer(backend))
            .phonemizer(chunkPhonemizer::phonemize)
            .audioPlayer(p)
            .streaming(prefetchDepth = 3)
            .build()
        try {
            instance.load(dir.absolutePath)
        } catch (t: Throwable) {
            backend.close()
            throw t
        }
        tts?.close()
        g2p?.close()
        oldPlayer?.release() // load() lại (A/B profile) không được rò AudioTrack của lần trước
        tts = instance
        g2p = backend
        reader = null
        return mapOf("ok" to true, "voices" to voices.size, "modelDir" to dir.absolutePath)
    }

    private fun profileFromMap(m: Map<String, Any?>?): GenerationProfile {
        if (m == null) return GenerationProfile()
        fun bool(k: String, d: Boolean) = (m[k] as? Boolean) ?: d
        fun int(k: String, d: Int) = (m[k] as? Number)?.toInt() ?: d
        fun dbl(k: String, d: Double) = (m[k] as? Number)?.toDouble() ?: d
        return GenerationProfile(
            profileRevision = int("profileRevision", GenerationProfile.PROFILE_REVISION),
            // Demo Flutter truyền 12 tường minh; default SDK vẫn 8 (không đổi).
            steps = int("steps", 8),
            sway = dbl("sway", -1.0),
            cfg = dbl("cfg", 3.0),
            maxCharsPerChunk = int("maxCharsPerChunk", 140),
            trebleFilterEnabled = bool("trebleFilterEnabled", true),
            comfortFilterEnabled = bool("comfortFilterEnabled", true),
            noiseFloorEnabled = bool("noiseFloorEnabled", true),
            spectralDenoiseEnabled = bool("spectralDenoiseEnabled", true),
            deEsserEnabled = bool("deEsserEnabled", true),
        )
    }

    // ── reader / enqueue ──────────────────────────────────────────────────────

    private fun createReader() {
        val t = tts ?: throw EngineError.Internal("load() trước khi createReader()")
        val r = t.newReader()
        r.onProgress { idx, n -> emit(mapOf("type" to "progress", "chunk_index" to idx, "n_chunks" to n)) }
        r.onDone { emit(mapOf("type" to "done", "metrics" to metricsMap())) }
        r.onError { e -> emit(mapOf("type" to "error", "code" to codeOf(e), "message" to (e.message ?: e.toString()))) }
        r.setOnUnderrunListener { emit(mapOf("type" to "underrun")) }
        reader = r
    }

    private fun enqueue(text: String) {
        val r = reader ?: throw EngineError.Internal("createReader() trước khi enqueue()")
        if (text.isBlank()) throw EngineError.InvalidInput("text rỗng")
        val voice = resolveVoice() ?: throw EngineError.Missing("Chưa có giọng nào — gọi install() trước")
        r.enqueue(text, loadEmbedding(voice.spkFile), loadEmbedding(voice.styleFile), seed = 0L)
    }

    private fun resolveVoice(): VoiceEntry? =
        voices.find { it.id == selectedVoiceId } ?: voices.firstOrNull()

    /** Vector giọng đã nạp sẵn lúc install; thiếu thì là lỗi, KHÔNG trả vector 0. */
    private fun loadEmbedding(file: File?): FloatArray =
        embCache[file?.absolutePath]
            ?: throw EngineError.Internal("Giọng chưa được nạp: ${file?.path ?: "(không có file)"}")

    private fun listPresets(): Map<String, Any?> = mapOf(
        "voices" to voices.map {
            mapOf(
                "id" to it.id,
                "name" to it.name,
                "wer_reference" to it.werReference,
            )
        },
    )

    private fun selectVoice(id: String) {
        if (voices.none { it.id == id }) throw EngineError.InvalidInput("Không có voice id=$id")
        selectedVoiceId = id
    }

    private fun metricsMap(): Map<String, Any?> {
        val m: Metrics = reader?.metrics() ?: Metrics()
        val out = HashMap<String, Any?>()
        m.rtf?.let { out["rtf"] = it }
        m.generationMs?.let { out["generation_ms"] = it }
        m.audioSeconds?.let { out["audio_seconds"] = it }
        m.peakRssMb?.let { out["peak_rss_mb"] = it }
        if (m.nChunks != 0) out["n_chunks"] = m.nChunks
        m.provider?.let { out["provider"] = it }
        m.thermalBucket?.let { out["thermal_bucket"] = it }
        if (m.cacheHit != 0) out["cache_hit"] = m.cacheHit
        if (m.cacheMiss != 0) out["cache_miss"] = m.cacheMiss
        m.errorCode?.let { out["error_code"] = it }
        if (m.hardFail) out["hard_fail"] = true
        return out
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun emit(payload: Map<String, Any?>) {
        main.post { eventSink?.success(payload) }
    }

    private fun codeOf(t: Throwable): String = when (t) {
        is EngineError -> t.code
        else -> "E_UNKNOWN"
    }

    private fun copyAssetDir(assetPath: String, dest: File) {
        val children = context.assets.list(assetPath)
        if (children.isNullOrEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                dest.outputStream().use { output -> input.copyTo(output, bufferSize = 1 shl 20) }
            }
            return
        }
        dest.mkdirs()
        for (child in children) copyAssetDir("$assetPath/$child", File(dest, child))
    }
}
