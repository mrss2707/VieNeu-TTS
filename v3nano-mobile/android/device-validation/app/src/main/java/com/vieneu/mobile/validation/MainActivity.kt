package com.vieneu.mobile.validation

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * Real-device validation harness UI (docs/_supermeo/_summary/002) — runs the full
 * smoke+stress corpus across CPU/XNNPACK/NNAPI providers and shows live progress.
 *
 * Why a foreground Activity instead of an instrumented test (the approach used for the
 * emulator/CI harness in androidTest/NanoEngineTest.kt): on a real OnePlus/Oppo device
 * (ColorOS/OxygenOS), `connectedDebugAndroidTest` runs as an instrumentation process with
 * no UI. The OS's aggressive background-process manager (`OplusHansManager`, a
 * "StrictMode" heuristic that flags processes using >300% CPU continuously as abnormal)
 * repeatedly freezes/unfreezes that process mid-benchmark, even after whitelisting it in
 * `dumpsys deviceidle` and disabling `cached_apps_freezer` — because instrumentation
 * processes are still classified as background regardless of those settings. A foreground
 * Activity with a visible window gets a different (foreground) process priority class
 * that these OEM freezers do not target the same way.
 */
class MainActivity : Activity() {
    private lateinit var logView: TextView
    private val log = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Soak mode (Spike S2): `adb shell am start ... --ei loops 12` chạy 4x số lần
        // smoke mặc định để kéo dài ~30 phút; mặc định 3 = hành vi cũ.
        val loops = intent.getIntExtra("loops", 3)
        // Precision compare: --es providers CPU --ez smokeOnly true --es tag int8
        val providersCsv = intent.getStringExtra("providers")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: listOf("CPU", "XNNPACK", "NNAPI")
        val smokeOnly = intent.getBooleanExtra("smokeOnly", false)
        val tag = intent.getStringExtra("tag") ?: "real_device"

        logView = TextView(this).apply {
            textSize = 12f
            setPadding(24, 24, 24, 24)
        }
        val scroll = ScrollView(this).apply { addView(logView) }
        setContentView(scroll)

        appendLog("DeviceValidation — real-device validation")
        appendLog("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
        appendLog("starting ${if (smokeOnly) "smoke" else "smoke+stress"} x $providersCsv (smoke runs per case: $loops) tag=$tag")

        Thread {
            try {
                val deviceLabel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                        "(Android ${android.os.Build.VERSION.RELEASE}, real device — not emulator/simulator)"
                val result = NanoBenchmarkCore.runFullCorpusAcrossProviders(
                    applicationContext,
                    deviceLabel,
                    smokeRunsPerCase = loops,
                    providers = providersCsv,
                    smokeOnly = smokeOnly,
                ) { line -> runOnUiThread { appendLog(line) } }

                NanoBenchmarkCore.logResultInChunks(result)

                val outFile = File(getExternalFilesDir(null), "nano_android_result_$tag.json")
                outFile.writeText(result.toString(2))
                Log.i(NanoBenchmarkCore.TAG, "wrote result to ${outFile.absolutePath}")

                runOnUiThread {
                    appendLog("")
                    appendLog("DONE. any_hard_fail=${result.getBoolean("any_hard_fail")}")
                    appendLog("Result written to: ${outFile.absolutePath}")
                    appendLog("Full JSON logged via RESULT_JSON_CHUNK_N — see adb logcat -s ${NanoBenchmarkCore.TAG}:I")
                }
            } catch (e: Exception) {
                Log.e(NanoBenchmarkCore.TAG, "benchmark run failed", e)
                runOnUiThread { appendLog("ERROR: ${e.message}\n${Log.getStackTraceString(e)}") }
            }
        }.start()
    }

    private fun appendLog(line: String) {
        log.append(line).append('\n')
        logView.text = log.toString()
        Log.i(NanoBenchmarkCore.TAG, "[UI] $line")
    }
}
