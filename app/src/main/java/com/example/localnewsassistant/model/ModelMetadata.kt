package com.example.localnewsassistant.model

import java.io.IOException
import java.util.Locale

class IndeterminateStorageException(message: String) : IOException(message)

data class ModelMetadata(
    val originalName: String,
    val sizeBytes: Long,
    val internalFileName: String
)

sealed interface ModelLoadResult {
    object NoModel : ModelLoadResult
    data class Valid(val metadata: ModelMetadata) : ModelLoadResult
    data class Corrupted(
        val originalName: String?,
        val expectedSize: Long?,
        val message: String
    ) : ModelLoadResult
}

object ModelImportValidator {
    const val REQUIRED_EXTENSION = ".litertlm"

    fun isValidExtension(fileName: String?): Boolean {
        if (fileName.isNullOrBlank()) return false
        return fileName.endsWith(REQUIRED_EXTENSION, ignoreCase = true)
    }

    fun isSafeFileName(fileName: String?): Boolean {
        if (fileName.isNullOrBlank()) return false
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) return false
        return true
    }

    fun formatFileSize(sizeBytes: Long): String {
        if (sizeBytes <= 0) return "0 Б"
        val k = 1024.0
        val m = k * 1024.0
        val g = m * 1024.0

        return when {
            sizeBytes >= g -> String.format(Locale.getDefault(), "%.2f ГБ", sizeBytes / g)
            sizeBytes >= m -> String.format(Locale.getDefault(), "%.1f МБ", sizeBytes / m)
            sizeBytes >= k -> String.format(Locale.getDefault(), "%.1f КБ", sizeBytes / k)
            else -> "$sizeBytes Б"
        }
    }
}
