package com.example.localnewsassistant.model

sealed interface ReplacementResult {
    data class Success(val metadata: ModelMetadata) : ReplacementResult
    data class RollbackSucceeded(val error: String, val activeMetadata: ModelMetadata?) : ReplacementResult
    data class IndeterminateStorageError(val error: String) : ReplacementResult
}

interface FileOperations {
    fun createTempFile(): Boolean
    suspend fun writeData(): Long // returns actual copied size in bytes
    fun finalizeNewFile(expectedSize: Long): Boolean
    fun deleteTempFile()
    fun deleteNewFile()
    fun deleteOldFile(oldFileName: String): Boolean
}

interface MetadataOperations {
    fun saveMetadataCommit(metadata: ModelMetadata?): Boolean
    fun getActiveMetadata(): ModelMetadata?
}

class ModelReplacementEngine {

    fun finalizeAndCommit(
        newMetadata: ModelMetadata,
        fileOps: FileOperations,
        metaOps: MetadataOperations
    ): ReplacementResult {
        val oldMetadata = metaOps.getActiveMetadata()

        if (!fileOps.finalizeNewFile(newMetadata.sizeBytes)) {
            fileOps.deleteTempFile()
            fileOps.deleteNewFile()
            return ReplacementResult.RollbackSucceeded(
                error = "Ошибка финализации нового файла модели",
                activeMetadata = oldMetadata
            )
        }

        // Commit new metadata with exception handling
        val commitSuccess = try {
            metaOps.saveMetadataCommit(newMetadata)
        } catch (e: Exception) {
            return ReplacementResult.IndeterminateStorageError(
                error = "Сбой при записи метаданных: ${e.localizedMessage ?: "Неизвестная ошибка"}. Неопределённое состояние хранилища."
            )
        }

        if (!commitSuccess) {
            // Commit returned false! Attempt rollback
            val restoreSuccess = try {
                metaOps.saveMetadataCommit(oldMetadata)
            } catch (_: Exception) {
                false
            }

            return if (restoreSuccess) {
                fileOps.deleteNewFile()
                ReplacementResult.RollbackSucceeded(
                    error = "Ошибка сохранения метаданных на диск (выполнен откат к предыдущему состоянию)",
                    activeMetadata = oldMetadata
                )
            } else {
                // Indeterminate state: commit returned false AND restore returned false/failed!
                ReplacementResult.IndeterminateStorageError(
                    error = "Неопределённое состояние хранилища: произошла ошибка записи и отката метаданных на диск."
                )
            }
        }

        // CONFIRMED COMMIT SUCCESS!
        if (oldMetadata != null && oldMetadata.internalFileName != newMetadata.internalFileName) {
            try {
                fileOps.deleteOldFile(oldMetadata.internalFileName)
            } catch (_: Exception) {
                // Failure or exception when deleting old file post-replacement is non-fatal.
            }
        }

        return ReplacementResult.Success(newMetadata)
    }
}
