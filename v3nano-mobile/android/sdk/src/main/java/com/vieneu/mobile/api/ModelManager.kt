package com.vieneu.mobile.api

import com.vieneu.mobile.core.InstallResult
import com.vieneu.mobile.core.ModelRegistry
import java.io.File

/**
 * ModelManager public façade (01 §1.9) — install/load/rollback.
 * Logic checksum/atomic nằm ở [ModelRegistry] (pure JVM, đã test).
 */
class ModelManager(
    private val registry: ModelRegistry,
) {
    @Throws(EngineError::class)
    fun install(packageDir: File, revision: String): String {
        return when (val r = registry.install(packageDir, revision)) {
            is InstallResult.Success -> r.revision
            is InstallResult.Failure -> throw r.error.toApiError(r.detail)
        }
    }

    fun activeRevision(): String? = registry.activeRevision()

    @Throws(EngineError::class)
    fun rollback(): String? = registry.rollback()

}

private fun com.vieneu.mobile.core.EngineError.toApiError(detail: String): EngineError = when (this) {
    com.vieneu.mobile.core.EngineError.E_MODEL_CHECKSUM -> EngineError.Checksum(detail)
    com.vieneu.mobile.core.EngineError.E_MODEL_CORRUPT -> EngineError.Corrupt(detail)
    com.vieneu.mobile.core.EngineError.E_COMPAT -> EngineError.Compat(detail)
    com.vieneu.mobile.core.EngineError.E_MODEL_NOT_FOUND -> EngineError.Corrupt(detail)
}
