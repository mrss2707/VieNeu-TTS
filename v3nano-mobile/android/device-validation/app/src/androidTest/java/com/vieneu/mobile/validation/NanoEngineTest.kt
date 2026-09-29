package com.vieneu.mobile.validation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator/CI validation harness — full corpus x CPU/XNNPACK/NNAPI via
 * NanoBenchmarkCore (shared with MainActivity, which is what real-device runs use
 * instead of this instrumented test process — see MainActivity's class doc for why:
 * OEM background-process freezers on real devices like OnePlus/Oppo repeatedly freeze
 * instrumentation processes mid-benchmark, which does not happen to a foreground
 * Activity or, apparently, on an emulator).
 *
 * This is a validation harness (docs/_supermeo/_summary/002), not the mobile SDK.
 */
@RunWith(AndroidJUnit4::class)
class NanoEngineTest {

    @Test
    fun runFullCorpusAcrossProviders() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = NanoBenchmarkCore.runFullCorpusAcrossProviders(
            context,
            "Android Emulator ARM64 (arm64-v8a, API 34) — NOT a physical device",
        )
        NanoBenchmarkCore.logResultInChunks(result)
        assert(!result.getBoolean("any_hard_fail")) { "hard fail detected (NaN/Inf or exception) — see logcat RESULT_JSON" }
    }
}
