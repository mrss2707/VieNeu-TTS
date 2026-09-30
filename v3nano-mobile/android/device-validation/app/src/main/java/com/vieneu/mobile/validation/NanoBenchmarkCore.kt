package com.vieneu.mobile.validation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.vieneu.mobile.core.ChunkJoiner
import com.vieneu.mobile.core.DeterministicRng
import com.vieneu.mobile.core.FlowScheduler
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

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

    class Graphs(env: OrtEnvironment, so: OrtSession.SessionOptions, base: (String) -> String) {
        val text = env.createSession(base("nano_graphs/text_encoder.onnx"), so)
        val dur = env.createSession(base("nano_graphs/duration_predictor.onnx"), so)
        val ve = env.createSession(base("nano_graphs/vector_estimator.onnx"), so)
        val dec = env.createSession(base("nano_graphs/codec_decoder.onnx"), so)
        fun close() { text.close(); dur.close(); ve.close(); dec.close() }
    }

    fun sessionOptionsForProvider(provider: String): Pair<OrtSession.SessionOptions, String> {
        val so = OrtSession.SessionOptions()
        so.setIntraOpNumThreads(4)
        var actualProvider = "CPU"
        when (provider) {
            "XNNPACK" -> {
                try {
                    so.addXnnpack(emptyMap())
                    actualProvider = "XNNPACK"
                } catch (e: Exception) {
                    Log.w(TAG, "XNNPACK unavailable, falling back to CPU: ${e.message}")
                }
            }
            "NNAPI" -> {
                try {
                    so.addNnapi()
                    actualProvider = "NNAPI"
                } catch (e: Exception) {
                    Log.w(TAG, "NNAPI unavailable, falling back to CPU: ${e.message}")
                }
            }
        }
        return Pair(so, actualProvider)
    }

    /** One flow-matching Euler inference, matching OnnxV3NanoEngine.infer (src/vieneu/v3nano.py).
     * `sway` warps the Euler time schedule: tg = u + sway*(cos(pi/2*u) - 1 + u) — NOT a no-op
     * when nonzero, so it must be applied to both t and dt, not just passed through as u.
     * Duration/sway/RNG logic live in the SDK core (com.vieneu.mobile.core.*) — JVM-tested,
     * golden-locked against v3nano-mobile/host/export_golden_contract.py. */
    fun runInference(
        g: Graphs, env: OrtEnvironment,
        idsLong: LongArray, spk: FloatArray, style: FloatArray,
        nullSpk: FloatArray, nullStyle: FloatArray,
        padId: Long, bosId: Long, eosId: Long, fps: Double, steps: Int, cfg: Double,
        sway: Double = 0.0,
    ): Triple<FloatArray, Long, Long> {
        val L = idsLong.size
        val ids2d = arrayOf(idsLong)
        val idsTensor = OnnxTensor.createTensor(env, ids2d)
        val styleShape = longArrayOf(1, 50, 256)
        val styleTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(style), styleShape)
        val nullStyleTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(nullStyle), styleShape)
        val spkShape = longArrayOf(1, 192)
        val spkTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(spk), spkShape)
        val nullSpkTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(nullSpk), spkShape)
        val mask = Array(1) { BooleanArray(L) { idx -> idsLong[idx] != padId } }
        val maskTensor = OnnxTensor.createTensor(env, mask)
        val nullIds = arrayOf(longArrayOf(bosId, eosId))
        val nullIdsTensor = OnnxTensor.createTensor(env, nullIds)
        val nullMask = Array(1) { BooleanArray(2) { true } }
        val nullMaskTensor = OnnxTensor.createTensor(env, nullMask)

        val t0 = System.currentTimeMillis()
        val ctxOut = g.text.run(mapOf("ids" to idsTensor, "style" to styleTensor))
        val ctxTensor = ctxOut[0] as OnnxTensor
        val nullCtxOut = g.text.run(mapOf("ids" to nullIdsTensor, "style" to nullStyleTensor))
        val nullCtxTensor = nullCtxOut[0] as OnnxTensor

        val durOut = g.dur.run(mapOf("ctx" to ctxTensor, "ctx_mask" to maskTensor, "spk" to spkTensor))
        val logS = (durOut[0] as OnnxTensor).floatBuffer.get(0)
        val secs = FlowScheduler.durationSeconds(logS.toDouble())
        val T = FlowScheduler.frames(secs, fps)

        val rng = DeterministicRng(0)
        val C = 144
        var x = FloatArray(C * T) { rng.nextGaussian() }
        val xShape = longArrayOf(1, C.toLong(), T.toLong())
        val n = steps
        val sch = FlowScheduler.schedule(n, sway)

        for (i in 0 until n) {
            val tVal = floatArrayOf(sch.t[i])
            val tTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(tVal), longArrayOf(1))
            val xTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), xShape)
            val vOut = g.ve.run(mapOf(
                "x" to xTensor, "t" to tTensor, "ctx" to ctxTensor, "ctx_mask" to maskTensor,
                "spk" to spkTensor, "style" to styleTensor))
            val vBuf = (vOut[0] as OnnxTensor).floatBuffer
            val v = FloatArray(vBuf.remaining()); vBuf.get(v)

            val vFinal: FloatArray
            if (cfg > 0) {
                val vuOut = g.ve.run(mapOf(
                    "x" to xTensor, "t" to tTensor, "ctx" to nullCtxTensor, "ctx_mask" to nullMaskTensor,
                    "spk" to nullSpkTensor, "style" to nullStyleTensor))
                val vuBuf = (vuOut[0] as OnnxTensor).floatBuffer
                val vu = FloatArray(vuBuf.remaining()); vuBuf.get(vu)
                vFinal = FloatArray(v.size) { j -> vu[j] + cfg.toFloat() * (v[j] - vu[j]) }
                vuOut.close()
            } else {
                vFinal = v
            }
            val dt = sch.dt[i]
            x = FloatArray(x.size) { j -> x[j] + dt * vFinal[j] }
            vOut.close(); xTensor.close(); tTensor.close()
        }

        val xFinalTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), xShape)
        val decOut = g.dec.run(mapOf("x" to xFinalTensor))
        val wavBuf = (decOut[0] as OnnxTensor).floatBuffer
        val wav = FloatArray(wavBuf.remaining()); wavBuf.get(wav)
        val genMs = System.currentTimeMillis() - t0

        idsTensor.close(); styleTensor.close(); nullStyleTensor.close(); spkTensor.close()
        nullSpkTensor.close(); maskTensor.close(); nullIdsTensor.close(); nullMaskTensor.close()
        ctxOut.close(); nullCtxOut.close(); decOut.close(); xFinalTensor.close()

        return Triple(wav, genMs, T.toLong())
    }

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
        g: Graphs, env: OrtEnvironment,
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
