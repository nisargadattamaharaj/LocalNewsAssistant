package com.example.localnewsassistant.model

sealed interface ReplacementResult {
    data class Success(val metadata: ModelMetadata) : ReplacementResult
    data class RollbackSucceeded(val error: String, val activeMetadata: ModelMetadata?) : ReplacementResult
    data class IndeterminateStorageError(val error: String) : ReplacementResult
}

interface FileOperations {
    fun createTempFile(): Boolean
    suspend fun writeData(): Boolean
    fun finalizeNewFile(): Boolean
    fun deleteTempFile()
    fun deleteNewFile()
    fun deleteOldFile(oldFileName: String): Boolean
}

interface MetadataOperations {
    fun saveMetadataCommit(metadata: ModelMetadata?): Boolean
    fun getActiveMetadata(): ModelMetadata?
}

class ModelReplacementEngine {

    suspend fun executeReplacement(
        newMetadata: ModelMetadata,
        fileOps: FileOperations,
        metaOps: MetadataOperations
    ): ReplacementResult {
        val oldMetadata = metaOps.getActiveMetadata()

        if (!fileOps.createTempFile() || !fileOps.writeData()) {
            fileOps.deleteTempFile()
            return ReplacementResult.RollbackSucceeded(
                error = "Ошибка записи временного файла",
                activeMetadata = oldMetadata
            )
        }

        if (!fileOps.finalizeNewFile()) {
            fileOps.deleteTempFile()
            fileOps.deleteNewFile()
            return ReplacementResult.RollbackSucceeded(
                error = "Ошибка завершения создания нового файла",
                activeMetadata = oldMetadata
            )
        }

        // Critical section: commit new metadata
        val commitSuccess = metaOps.saveMetadataCommit(newMetadata)

        if (!commitSuccess) {
            // Commit failed. Attempt to restore old metadata state
            val restoreSuccess = metaOps.saveMetadataCommit(oldMetadata)

            return if (restoreSuccess) {
                fileOps.deleteNewFile()
                ReplacementResult.RollbackSucceeded(
                    error = "Ошибка сохранения метаданных на диск (выполнен откат к предыдущему состоянию)",
                    activeMetadata = oldMetadata
                )
            } else {
                // NEITHER new metadata commit NOR rollback commit was confirmed on disk!
                // Do NOT delete newFile or oldFile!
                ReplacementResult.IndeterminateStorageError(
                    error = "Неопределённое состояние хранилища: произошла ошибка записи и отката метаданных на диск."
                )
            }
        }

        // Commit of new metadata succeeded
        if (oldMetadata != null && oldMetadata.internalFileName != newMetadata.internalFileName) {
            fileOps.deleteOldFile(oldMetadata.internalFileName)
            // Failure deleting old file post-replacement is non-fatal: new model is active.
        }

        return ReplacementResult.Success(newMetadata)
    }
}
