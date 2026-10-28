package com.vieneu.mobile.validation

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.vieneu.mobile.core.ChunkJoiner
import com.vieneu.mobile.ort.OrtGraphs
import com.vieneu.mobile.ort.OrtInference
import com.vieneu.mobile.ort.OrtSessionOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real inference core for v3 Nano's four ONNX graphs, mirroring OnnxV3NanoEngine.infer()
 * in src/vieneu/v3nano.py. Shared between the foreground MainActivity (real-device runs —
 * see its class doc for why a foreground Activity is needed instead of an instrumented
 * test process) and the androidTest NanoEngineTest (emulator/CI runs).
 *
 * This is a validation harness (docs/_supermeo/_summary/002), not the mobile SDK.
 */
object NanoBenchmarkCore {
    const val TAG = "NanoEngineTest"
    const val SMOKE_RUNS_PER_CASE = 3
    val PROVIDERS = listOf("CPU", "XNNPACK", "NNAPI")

    /** Ưu tiên file push qua adb (external-files/nano_graphs/) hơn assets —
     *  cho phép so FP32/FP16/INT8 không phải build lại APK. */
    fun resolveModelFile(context: Context, name: String): File {
        val base = name.substringAfterLast('/')
        val candidates = listOf(
            File(context.getExternalFilesDir(null), "nano_graphs/$base"),
            File(context.filesDir, "nano_graphs/$base"),
            File(context.cacheDir, name.replace('/', '_')),
        )
        candidates.firstOrNull { it.isFile && it.length() > 0 }?.let { return it }
        // fallback: extract asset → cache
        val outFile = File(context.cacheDir, name.replace('/', '_'))
        context.assets.open(name).use { input ->
            outFile.outputStream().use { output -> input.copyTo(output, bufferSize = 1 shl 20) }
        }
        return outFile
    }

    fun assetBytes(context: Context, name: String): ByteArray {
        // Corpus tensors luôn từ APK assets; chỉ graph (.onnx) mới cho override qua
        // external-files/nano_graphs (so FP32/FP16/INT8 không rebuild).
        if (name.endsWith(".onnx") || name.startsWith("nano_graphs/")) {
            return resolveModelFile(context, name).readBytes()
        }
        context.assets.open(name).use { return it.readBytes() }
    }

    fun assetJson(context: Context, name: String): JSONObject = JSONObject(String(assetBytes(context, name)))

    /** Copies an asset (or on-disk graph) to the app's cache dir and returns its filesystem path — ORT's
     * native session loader reads straight off disk, avoiding a Java-heap byte[] copy
     * (large graphs like vector_estimator.onnx OOM the ~192MB Dalvik heap otherwise). */
    fun assetToFile(context: Context, name: String): String {
        return resolveModelFile(context, name).absolutePath
    }

    fun assetFloatArray(context: Context, name: String): FloatArray {
        val bytes = assetBytes(context, name)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(bytes.size / 4)
        buf.asFloatBuffer().get(out)
        return out
    }

    fun assetLongArray(context: Context, name: String): LongArray {
        val bytes = assetBytes(context, name)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = LongArray(bytes.size / 8)
        buf.asLongBuffer().get(out)
        return out
    }

    /**
     * Giữ API `NanoBenchmarkCore.Graphs(env, so, base)` — nay là lớp con của
     * [OrtGraphs] (engine dùng chung ở module `:sdk-engine-ort`). Không nhân bản logic.
     */
    class Graphs(env: OrtEnvironment, so: OrtSession.SessionOptions, base: (String) -> String) :
        OrtGraphs(env, so, base)

    /** Delegate sang module ORT — xem [OrtSessionOptions.sessionOptionsForProvider]. */
    fun sessionOptionsForProvider(provider: String): Pair<OrtSession.SessionOptions, String> =
        OrtSessionOptions.sessionOptionsForProvider(provider)

    /** One flow-matching Euler inference, matching OnnxV3NanoEngine.infer (src/vieneu/v3nano.py).
     * `sway` warps the Euler time schedule: tg = u + sway*(cos(pi/2*u) - 1 + u) — NOT a no-op
     * when nonzero, so it must be applied to both t and dt, not just passed through as u.
     * Duration/sway/RNG logic live in the SDK core (com.vieneu.mobile.core.*) — JVM-tested,
     * golden-locked against v3nano-mobile/host/export_golden_contract.py. */
    fun runInference(
        g: OrtGraphs, env: OrtEnvironment,
        idsLong: LongArray, spk: FloatArray, style: FloatArray,
        nullSpk: FloatArray, nullStyle: FloatArray,
        padId: Long, bosId: Long, eosId: Long, fps: Double, steps: Int, cfg: Double,
        sway: Double = 0.0,
    ): Triple<FloatArray, Long, Long> =
        OrtInference.run(
            g, env, idsLong, spk, style, nullSpk, nullStyle,
            padId, bosId, eosId, fps, steps, cfg, sway,
        )

    // ── Multi-chunk audio join — logic ở SDK core ChunkJoiner (JVM-tested, golden-locked).
    // Chỉ phần GHÉP được port — chunk *splitting* (normalize_to_chunks_v3_with_gaps) đã chạy
    // sẵn trong v3nano-mobile/host/export_corpus_tensors.py (chunks_meta.json + chunk<k>/ids.bin).
    // Xem docs/_supermeo/_summary/001-v3-nano-mobile.md §0.8.

    val V3_GAP_SILENCE: Map<String, Double> get() = ChunkJoiner.V3_GAP_SILENCE

    fun edgeSilence(wav: FloatArray, sr: Int, threshDb: Double = ChunkJoiner.EDGE_THRESH_DB,
                    winS: Double = ChunkJoiner.EDGE_WIN_S): Pair<Int, Int> =
        ChunkJoiner.edgeSilence(wav, sr, threshDb, winS)

    fun pausePadSamples(prevWav: FloatArray, nextWav: FloatArray, sr: Int, pauseS: Double): Int =
        ChunkJoiner.pausePadSamples(prevWav, nextWav, sr, pauseS)

    fun joinAudioChunks(chunks: List<FloatArray>, sr: Int, gaps: List<String>): FloatArray =
        ChunkJoiner.joinAudioChunks(chunks, sr, gaps) { line -> Log.w(TAG, line) }

    /** Runs inference for every chunk of a (possibly multi-chunk) case, reading
     * `chunks_meta.json` + `chunk<k>/ids.bin` under [caseAssetDir], then joins with
     * [joinAudioChunks] — mirrors V3NanoVieNeuTTS.infer()'s chunking+join path so long
     * sentences (> max_chars=140) don't get force-fed to the flow model in one shot
     * (root cause of the "nuốt chữ" bug — see §0.8). Single-chunk cases still take the
     * same code path (chunk0/) so callers don't need a branch. */
    fun runInferenceMultiChunk(
        context: Context, caseAssetDir: String,
        g: OrtGraphs, env: OrtEnvironment,
        spk: FloatArray, style: FloatArray, nullSpk: FloatArray, nullStyle: FloatArray,
        padId: Long, bosId: Long, eosId: Long, fps: Double, steps: Int, cfg: Double, sway: Double,
        sampleRate: Int,
    ): Triple<FloatArray, Long, Int> {
        val chunksMeta = assetJson(context, "$caseAssetDir/chunks_meta.json")
        val nChunks = chunksMeta.getInt("n_chunks")
        val gapsArr = chunksMeta.getJSONArray("gaps")
        val gaps = (0 until gapsArr.length()).map { gapsArr.getString(it) }

        val wavs = mutableListOf<FloatArray>()
        var totalGenMs = 0L
        for (k in 0 until nChunks) {
            val ids = assetLongArray(context, "$caseAssetDir/chunk$k/ids.bin")
            val (wav, genMs, _) = runInference(g, env, ids, spk, style, nullSpk, nullStyle,
                padId, bosId, eosId, fps, steps, cfg, sway)
            wavs.add(wav)
            totalGenMs += genMs
        }
        val joined = joinAudioChunks(wavs, sampleRate, gaps)
        return Triple(joined, totalGenMs, nChunks)
    }

    /**
     * Runs the full smoke+stress corpus across all PROVIDERS, calling [onProgress] after
     * every case (case description, current/total) so a caller can render live progress —
     * this also serves as evidence of forward progress if the OS freezes/throttles the
     * process (see MainActivity doc).
     */
    fun runFullCorpusAcrossProviders(
        context: Context,
        deviceLabel: String,
        smokeRunsPerCase: Int = SMOKE_RUNS_PER_CASE,
        providers: List<String> = PROVIDERS,
        smokeOnly: Boolean = false,
        onProgress: (String) -> Unit = {},
    ): JSONObject {
        val env = OrtEnvironment.getEnvironment()
        val globalMeta = assetJson(context, "nano_test_input/corpus/global_meta.json")
        val padId = globalMeta.getLong("pad_id")
        val bosId = globalMeta.getLong("bos_id")
        val eosId = globalMeta.getLong("eos_id")
        val fps = globalMeta.getDouble("flow_fps")
        val steps = globalMeta.getInt("steps")
        val cfg = globalMeta.getDouble("cfg")
        val sway = globalMeta.optDouble("sway", 0.0)
        val voiceName = globalMeta.getString("voice").replace(" ", "_")

        val spk = assetFloatArray(context, "nano_test_input/corpus/voices/$voiceName/spk.bin")
        val style = assetFloatArray(context, "nano_test_input/corpus/voices/$voiceName/style.bin")
        val nullSpk = assetFloatArray(context, "nano_test_input/corpus/null_spk.bin")
        val nullStyle = assetFloatArray(context, "nano_test_input/corpus/null_style.bin")

        val casesIndex = assetJson(context, "nano_test_input/corpus/cases_index.json").getJSONArray("cases")
        val smokeCaseKeys = mutableListOf<String>()
        val stressCaseKeys = mutableListOf<String>()
        for (i in 0 until casesIndex.length()) {
            val key = casesIndex.getString(i)
            if (key.startsWith("smoke_")) smokeCaseKeys.add(key) else stressCaseKeys.add(key)
        }
        val stressKeysUsed = if (smokeOnly) emptyList() else stressCaseKeys
        val totalCases = providers.size * (smokeCaseKeys.size + stressKeysUsed.size)
        var doneCases = 0

        val perProviderResults = JSONObject()
        var anyHardFail = false

        for (providerRequested in providers) {
            val (so, actualProvider) = sessionOptionsForProvider(providerRequested)
            val t0 = System.currentTimeMillis()
            val g = Graphs(env, so) { assetToFile(context, it) }
            val loadMs = System.currentTimeMillis() - t0
            onProgress("provider=$providerRequested actual=$actualProvider graph_load_ms=$loadMs")

            val sampleRate = globalMeta.getInt("sample_rate")
            val caseResults = JSONArray()
            for (caseKey in smokeCaseKeys) {
                val caseDir = "nano_test_input/corpus/cases/$caseKey"
                val meta = assetJson(context, "$caseDir/chunk0/meta.json")
                val genMsList = JSONArray()
                var nanAny = false
                var audioSeconds = 0.0
                var nChunksSeen = 1
                for (run in 0 until smokeRunsPerCase) {
                    val (wav, genMs, nChunks) = runInferenceMultiChunk(context, caseDir, g, env,
                        spk, style, nullSpk, nullStyle, padId, bosId, eosId, fps, steps, cfg, sway, sampleRate)
                    genMsList.put(genMs)
                    if (wav.any { it.isNaN() || it.isInfinite() }) nanAny = true
                    audioSeconds = wav.size.toDouble() / sampleRate
                    nChunksSeen = nChunks
                }
                if (nanAny) anyHardFail = true
                val entry = JSONObject()
                entry.put("case_id", meta.getString("case_id"))
                entry.put("category", meta.optString("category"))
                entry.put("n_chunks", nChunksSeen)
                entry.put("generation_ms_per_run", genMsList)
                entry.put("audio_seconds", audioSeconds)
                entry.put("nan_or_inf", nanAny)
                caseResults.put(entry)
                doneCases++
                onProgress("progress provider=$providerRequested smoke ${doneCases}/${totalCases} case=${meta.getString("case_id")} chunks=$nChunksSeen nan=$nanAny")
            }

            // stress corpus: 1 run each, hard-fail check only (no timing emphasis)
            val stressResults = JSONArray()
            for (caseKey in stressKeysUsed) {
                val caseDir = "nano_test_input/corpus/cases/$caseKey"
                val meta = assetJson(context, "$caseDir/chunk0/meta.json")
                val entry = JSONObject()
                entry.put("case_id", meta.getString("case_id"))
                entry.put("category", meta.optString("category"))
                try {
                    val (wav, genMs, nChunks) = runInferenceMultiChunk(context, caseDir, g, env,
                        spk, style, nullSpk, nullStyle, padId, bosId, eosId, fps, steps, cfg, sway, sampleRate)
                    val nanAny = wav.any { it.isNaN() || it.isInfinite() }
                    if (nanAny) anyHardFail = true
                    entry.put("outcome", "ran")
                    entry.put("n_chunks", nChunks)
                    entry.put("generation_ms", genMs)
                    entry.put("audio_seconds", wav.size.toDouble() / sampleRate)
                    entry.put("nan_or_inf", nanAny)
                } catch (e: Exception) {
                    anyHardFail = true
                    entry.put("outcome", "exception")
                    entry.put("exception", e.message ?: e.toString())
                }
                stressResults.put(entry)
                doneCases++
                onProgress("progress provider=$providerRequested stress ${doneCases}/${totalCases} case=${meta.getString("case_id")}")
            }

            g.close()

            val providerEntry = JSONObject()
            providerEntry.put("provider_requested", providerRequested)
            providerEntry.put("provider_actual", actualProvider)
            providerEntry.put("fallback_to_cpu", actualProvider != providerRequested && providerRequested != "CPU")
            providerEntry.put("graph_load_ms", loadMs)
            providerEntry.put("smoke_cases", caseResults)
            providerEntry.put("stress_cases", stressResults)
            perProviderResults.put(providerRequested, providerEntry)

            Log.i(TAG, "provider=$providerRequested actual=$actualProvider load_ms=$loadMs " +
                    "smoke_cases=${caseResults.length()} stress_cases=${stressResults.length()}")
        }

        val result = JSONObject()
        result.put("device", deviceLabel)
        result.put("smoke_runs_per_case", smokeRunsPerCase)
        result.put("voice", globalMeta.getString("voice"))
        result.put("steps", steps)
        result.put("cfg", cfg)
        result.put("sway", sway)
        result.put("any_hard_fail", anyHardFail)
        result.put("providers", perProviderResults)
        return result
    }

    /** Logs a JSON result via RESULT_JSON_CHUNK_N lines — logcat has a per-line size limit
     * (~4KB), so long results must be split and reassembled by the reader. */
    fun logResultInChunks(result: JSONObject) {
        val resultStr = result.toString()
        val chunkSize = 3500
        var idx = 0
        var chunkNum = 0
        while (idx < resultStr.length) {
            val end = minOf(idx + chunkSize, resultStr.length)
            Log.i(TAG, "RESULT_JSON_CHUNK_$chunkNum=${resultStr.substring(idx, end)}")
            idx = end
            chunkNum++
        }
        Log.i(TAG, "RESULT_JSON_CHUNKS_TOTAL=$chunkNum")
    }
}
