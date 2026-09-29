package com.vieneu.mobile.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** ModelRegistry: install/verify/rollback — theo 07-kiem-thu.md §7.4.
 * Probe/compat là lambda injectable nên KHÔNG cần ORT thật để test. */
class ModelRegistryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var registryRoot: File
    private lateinit var registry: ModelRegistry

    private fun newPackage(payload: String = "weights-bytes", graphContent: String = "graph"): File {
        val pkg = tmp.newFolder()  // tên ngẫu nhiên — nhiều package cùng test không đụng nhau
        File(pkg, "graphs").mkdirs()
        File(pkg, "graphs").resolve("text_encoder.onnx").writeText(graphContent)
        File(pkg, "assets").mkdirs()
        File(pkg, "assets").resolve("tokenizer.json").writeText("""{"schema_version":"1"}""")
        File(pkg, "manifest.json").writeText(manifestJson(graphContent))
        writeChecksums(pkg)
        return pkg
    }

    private fun manifestJson(graphContent: String): String {
        val graphSha = sha(graphContent.toByteArray())
        return """{
          "schema_version": "1",
          "model_id": "v3-nano-mobile",
          "model_revision": "0.1.0",
          "base_model_revision": "aba295eb96a6fa6003ebe417cc1f2802a7adc1dc",
          "precision": "fp16",
          "min_sdk": {"android": 26},
          "compatibility": {"sdk_revision_min": "0.1.0"},
          "graphs": {"text_encoder": {"file": "graphs/text_encoder.onnx", "sha256": "$graphSha"}}
        }""".trimIndent()
    }

    private fun writeChecksums(pkg: File) {
        val lines = mutableListOf<String>()
        pkg.walkTopDown().filter { it.isFile }.forEach { f ->
            if (f.name == "checksums.txt") return@forEach
            lines.add("${ModelRegistry.sha256(f)}  ${f.relativeTo(pkg).path}")
        }
        File(pkg, "checksums.txt").writeText(lines.joinToString("\n") + "\n")
    }

    private fun sha(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun setUpRegistry(probe: (File) -> Unit = {}, compat: (ManifestInfo) -> EngineError? = { null }) {
        registryRoot = tmp.newFolder("registry")
        registry = ModelRegistry(registryRoot, probe, compat)
    }

    @Test
    fun installChecksumOkProbeOk() {
        setUpRegistry()
        val result = registry.install(newPackage(), "0.1.0")
        assertTrue("expected Success, got $result", result is InstallResult.Success)
        assertEquals("0.1.0", registry.activeRevision())
        assertTrue(File(registryRoot, "revisions/0.1.0/graphs/text_encoder.onnx").isFile)
    }

    @Test
    fun installWrongChecksumRejectedBeforeProbe() {
        var probeCalled = false
        setUpRegistry(probe = { probeCalled = true })
        val pkg = newPackage()
        // sửa nội dung SAU khi checksums đã ghi -> checksum lệch
        File(pkg, "graphs/text_encoder.onnx").writeText("tampered")
        val result = registry.install(pkg, "0.1.0")
        assertTrue(result is InstallResult.Failure && result.error == EngineError.E_MODEL_CHECKSUM)
        assertFalse(probeCalled)                    // từ chối TRƯỚC probe-load (H11)
        assertNull(registry.activeRevision())
        assertFalse(File(registryRoot, "revisions").exists())
    }

    @Test
    fun installProbeThrowMeansCorruptNoHalfState() {
        setUpRegistry(probe = { throw IllegalStateException("InvalidProtobuf") })
        val result = registry.install(newPackage(), "0.1.0")
        assertTrue(result is InstallResult.Failure && result.error == EngineError.E_MODEL_CORRUPT)
        assertNull(registry.activeRevision())
        assertFalse(File(registryRoot, "revisions").exists())
    }

    @Test
    fun installCompatRejectedEarly() {
        setUpRegistry(compat = { EngineError.E_COMPAT })
        val result = registry.install(newPackage(), "0.1.0")
        assertTrue(result is InstallResult.Failure && result.error == EngineError.E_COMPAT)
        assertNull(registry.activeRevision())
    }

    @Test
    fun rollbackRestoresPreviousRevision() {
        setUpRegistry()
        assertTrue(registry.install(newPackage(graphContent = "g1"), "rev1") is InstallResult.Success)
        assertTrue(registry.install(newPackage(graphContent = "g2"), "rev2") is InstallResult.Success)
        assertEquals("rev2", registry.activeRevision())
        assertEquals("rev1", registry.rollback())
        assertEquals("rev1", registry.activeRevision())
        assertEquals("g2", File(registryRoot, "revisions/rev2/graphs/text_encoder.onnx").readText())
    }

    @Test
    fun rollbackWithoutPreviousIsNull() {
        setUpRegistry()
        assertNull(registry.rollback())
        registry.install(newPackage(), "rev1")
        // chỉ có 1 revision -> prev rỗng -> rollback trả null, pointer giữ nguyên
        assertEquals("rev1", registry.activeRevision())
        assertNull(registry.rollback())
        assertEquals("rev1", registry.activeRevision())
    }

    @Test
    fun missingPackageDirNotFound() {
        setUpRegistry()
        val result = registry.install(File(tmp.root, "does-not-exist"), "x")
        assertTrue(result is InstallResult.Failure && result.error == EngineError.E_MODEL_NOT_FOUND)
    }

    @Test
    fun interruptDuringCommitLeavesNoHalfState() {
        setUpRegistry()
        assertTrue(registry.install(newPackage(graphContent = "g1"), "rev1") is InstallResult.Success)
        // Mô phỏng disk-full/interrupt giữa commit: thay thư mục staging bằng FILE trùng tên
        val staging = File(registryRoot, ".staging")
        if (staging.isDirectory) staging.deleteRecursively()
        staging.writeText("blocked")
        val result = registry.install(newPackage(graphContent = "g2"), "rev2")
        assertTrue(result is InstallResult.Failure)
        assertEquals("rev1", registry.activeRevision())     // pointer KHÔNG đổi
        assertFalse(File(registryRoot, "revisions/rev2").exists())  // không revision nửa vời
        // gỡ chặn -> install lại thành công
        staging.delete()
        assertTrue(registry.install(newPackage(graphContent = "g2"), "rev2") is InstallResult.Success)
        assertEquals("rev2", registry.activeRevision())
    }
}