package com.vieneu.mobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Mã lỗi chuẩn hoá cho tầng model (03-model-package.md §3.4: "không bao giờ để
 * exception thô rơi tới app layer"). Bảng mã đầy đủ ở 08-ci-cd-phat-hanh.md;
 * đây là 4 mã cần thiết cho chu trình install/verify/rollback của P1.
 */
sealed class EngineError(val code: String, override val message: String) : Exception(message) {
    object E_MODEL_CHECKSUM : EngineError("E_MODEL_CHECKSUM", "checksum package sai — từ chối trước khi load trọng số")
    object E_MODEL_CORRUPT : EngineError("E_MODEL_CORRUPT", "file hỏng/không parse được (probe-load fail)")
    object E_COMPAT : EngineError("E_COMPAT", "revision không tương thích runtime/sdk hiện tại")
    object E_MODEL_NOT_FOUND : EngineError("E_MODEL_NOT_FOUND", "package dir/file không tồn tại")
}

/** Thông tin tối thiểu đọc từ manifest.json, dùng cho compatibility check (từ chối SỚM —
 * trước khi probe-load trọng số nào — đúng điều kiện H11 của kế hoạch). */
data class ManifestInfo(
    val schemaVersion: String,
    val modelId: String,
    val modelRevision: String,
    val baseModelRevision: String,
    val precision: String,
    val minSdkAndroid: Int,
    val sdkRevisionMin: String,
)

sealed class InstallResult {
    data class Success(val revision: String) : InstallResult()
    data class Failure(val error: EngineError, val detail: String) : InstallResult()
}

/**
 * Install/verify/rollback model package — port chu trình 03-model-package.md §3.4:
 *
 *  1. Verify checksums.txt (mọi entry tồn tại + sha256 khớp) + manifest.graphs sha khớp
 *     checksums.txt.
 *  2. Compatibility check qua [compatCheck] (manifest đã parse) — từ chối sớm, E_COMPAT.
 *  3. Probe-load hook [probe] — bắt lỗi corrupt trước khi "install xong", E_MODEL_CORRUPT.
 *  4. Commit atomic: copy vào staging (cùng filesystem) -> ATOMIC_MOVE vào
 *     revisions/<revision>/ -> ghi pointer active_revision (tmp + atomic move).
 *  5. Không bao giờ ghi đè bản đang dùng; nếu crash lần chạy đầu -> [rollback].
 *
 * Pure JVM (java.nio File/Path) — test được bằng JUnit4 trên CI; probe/compat là lambda
 * injectable nên TestModelRegistry không cần ORT thật (07-kiem-thu.md §7.4).
 */
class ModelRegistry(
    private val root: File,
    private val probe: (File) -> Unit = {},
    private val compatCheck: (ManifestInfo) -> EngineError? = { null },
) {
    private val revisionsDir = File(root, "revisions")
    private val pointerFile = File(root, "active_revision")
    private val stagingDir = File(root, ".staging")
    private val previousFile = File(root, "active_revision.prev")

    /** Revision hiện active, hoặc null nếu chưa install cái nào. */
    fun activeRevision(): String? =
        pointerFile.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    fun install(packageDir: File, revision: String): InstallResult {
        if (!packageDir.isDirectory) {
            return InstallResult.Failure(EngineError.E_MODEL_NOT_FOUND, "package dir missing: $packageDir")
        }
        val checksumErr = verifyChecksums(packageDir)
        if (checksumErr != null) return InstallResult.Failure(EngineError.E_MODEL_CHECKSUM, checksumErr)

        val manifest = parseManifest(packageDir)
            ?: return InstallResult.Failure(EngineError.E_MODEL_CORRUPT, "manifest.json không parse được (InvalidProtobuf-đẳng cấp lỗi tầng package)")
        val compatErr = compatCheck(manifest)
        if (compatErr != null) return InstallResult.Failure(compatErr, "compatibility từ chối revision=${manifest.modelRevision}")

        try {
            probe(packageDir)
        } catch (e: Exception) {
            return InstallResult.Failure(EngineError.E_MODEL_CORRUPT, e.message ?: e.toString())
        }

        // Commit: staging dir nằm CHUNG filesystem với revisions -> ATOMIC_MOVE khả thi.
        val tmp = File(stagingDir, "rev-$revision-${System.nanoTime()}")
        try {
            if (tmp.exists()) tmp.deleteRecursively()
            tmp.mkdirs()
            copyRecursively(packageDir, tmp)
            if (!revisionsDir.exists()) revisionsDir.mkdirs()
            val target = File(revisionsDir, revision)
            if (target.exists()) target.deleteRecursively()
            atomicMove(tmp.toPath(), target.toPath())
            writePointer(revision)
            return InstallResult.Success(revision)
        } catch (e: Exception) {
            tmp.deleteRecursively()
            return InstallResult.Failure(EngineError.E_MODEL_CORRUPT, "commit fail: ${e.message ?: e.toString()}")
        } finally {
            stagingDir.listFiles()?.forEach { it.deleteRecursively() }  // dọn residual, disk full/interrupt không để half-state
        }
    }

    /** Quay pointer về revision cũ (file active_revision.prev). */
    fun rollback(): String? {
        val prev = previousFile.takeIf { it.isFile }?.readText()?.trim()
        if (prev.isNullOrEmpty() || !File(revisionsDir, prev).isDirectory) return null
        writePointer(prev)
        return prev
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private fun writePointer(revision: String) {
        val cur = activeRevision()
        if (cur != null && cur != revision) {
            previousFile.writeText(cur)
        }
        val tmp = File(root, "active_revision.tmp")
        tmp.writeText(revision)
        atomicMove(tmp.toPath(), pointerFile.toPath())
    }

    private fun atomicMove(from: java.nio.file.Path, to: java.nio.file.Path) {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** True nếu mọi entry checksums.txt tồn tại + khớp sha256, và manifest.graphs đồng
     * nhất với checksums.txt. Trả detail lỗi hoặc null nếu OK. */
    fun verifyChecksums(packageDir: File): String? {
        val checksumsFile = File(packageDir, "checksums.txt")
        if (!checksumsFile.isFile) return "checksums.txt missing"
        val declared = HashMap<String, String>()
        for (line in checksumsFile.readLines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val sep = trimmed.indexOf("  ")
            if (sep < 0) return "checksums.txt line không đúng định dạng: $trimmed"
            declared[trimmed.substring(sep + 2).trim()] = trimmed.substring(0, sep)
        }
        for ((rel, expected) in declared) {
            val f = File(packageDir, rel)
            if (!f.isFile) return "checksums.txt khai file không tồn tại: $rel"
            if (sha256(f) != expected) return "checksum sai: $rel"
        }
        val manifestJson = parseManifestJson(packageDir) ?: return "manifest.json không parse được"
        val graphs = manifestJson["graphs"]?.jsonObject ?: return "manifest.graphs missing"
        for ((name, entryObj) in graphs) {
            val entry = entryObj.jsonObject
            val file = entry["file"]?.jsonPrimitive?.content ?: return "graphs.$name.file missing"
            val sha = entry["sha256"]?.jsonPrimitive?.content ?: return "graphs.$name.sha256 missing"
            if (declared[file] != sha) return "manifest.graphs.$name.sha256 != checksums.txt"
        }
        return null
    }

    fun parseManifestJson(packageDir: File): kotlinx.serialization.json.JsonObject? = try {
        Json.parseToJsonElement(File(packageDir, "manifest.json").readText()).jsonObject
    } catch (e: Exception) {
        null
    }

    fun parseManifest(packageDir: File): ManifestInfo? {
        val m = parseManifestJson(packageDir) ?: return null
        try {
            return ManifestInfo(
                schemaVersion = m["schema_version"]?.jsonPrimitive?.content ?: "",
                modelId = m["model_id"]?.jsonPrimitive?.content ?: "",
                modelRevision = m["model_revision"]?.jsonPrimitive?.content ?: "",
                baseModelRevision = m["base_model_revision"]?.jsonPrimitive?.content ?: "",
                precision = m["precision"]?.jsonPrimitive?.content ?: "",
                minSdkAndroid = (m["min_sdk"]?.jsonObject?.get("android")?.jsonPrimitive?.content
                    ?: "0").toIntOrNull() ?: 0,
                sdkRevisionMin = m["compatibility"]?.jsonObject?.get("sdk_revision_min")
                    ?.jsonPrimitive?.content ?: "",
            )
        } catch (e: Exception) {
            return null
        }
    }

    companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun copyRecursively(from: File, to: File) {
            from.walkTopDown().forEach { src ->
                if (src == from) return@forEach
                val rel = src.relativeTo(from)
                val dst = File(to, rel.path)
                if (src.isDirectory) dst.mkdirs() else {
                    dst.parentFile?.mkdirs()
                    Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }
}