package com.example.localnewsassistant.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object ModelStorageManager {

    private const val PREFS_NAME = "model_storage_prefs"
    private const val KEY_ORIGINAL_NAME = "original_name"
    private const val KEY_SIZE_BYTES = "size_bytes"
    private const val KEY_INTERNAL_FILENAME = "internal_filename"

    private const val BUFFER_SIZE = 65536 // 64 KB
    private const val PROGRESS_UPDATE_INTERVAL_MS = 100L

    fun getModelsDir(context: Context): File {
        val dir = File(context.noBackupFilesDir, "models")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    suspend fun cleanTempFiles(context: Context, activeFileName: String? = null) {
        withContext(Dispatchers.IO) {
            val modelsDir = getModelsDir(context)
            modelsDir.listFiles()?.forEach { file ->
                if (file.name != activeFileName && file.name.startsWith("temp_")) {
                    runCatching { file.delete() }
                }
            }
        }
    }

    suspend fun loadMetadata(context: Context): ModelLoadResult {
        return withContext(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val hasOriginalName = prefs.contains(KEY_ORIGINAL_NAME)
                val hasSizeBytes = prefs.contains(KEY_SIZE_BYTES)
                val hasInternalFileName = prefs.contains(KEY_INTERNAL_FILENAME)

                if (!hasOriginalName && !hasSizeBytes && !hasInternalFileName) {
                    return@withContext ModelLoadResult.NoModel
                }

                val originalName = prefs.getString(KEY_ORIGINAL_NAME, null)
                val sizeBytes = prefs.getLong(KEY_SIZE_BYTES, -1L)
                val internalFileName = prefs.getString(KEY_INTERNAL_FILENAME, null)

                if (originalName.isNullOrBlank() || sizeBytes <= 0L || internalFileName.isNullOrBlank()) {
                    return@withContext ModelLoadResult.Corrupted(
                        originalName = originalName,
                        expectedSize = if (sizeBytes > 0) sizeBytes else null,
                        message = "Метаданные модели неполны или повреждены. Требуется повторный импорт."
                    )
                }

                if (!ModelImportValidator.isSafeFileName(internalFileName)) {
                    return@withContext ModelLoadResult.Corrupted(
                        originalName = originalName,
                        expectedSize = sizeBytes,
                        message = "Обнаружено небезопасное имя файла в метаданных. Требуется повторный импорт."
                    )
                }

                val modelFile = File(getModelsDir(context), internalFileName)

                if (!modelFile.exists()) {
                    return@withContext ModelLoadResult.Corrupted(
                        originalName = originalName,
                        expectedSize = sizeBytes,
                        message = "Файл импортированной модели «$originalName» не найден во внутреннем хранилище. Требуется повторный импорт."
                    )
                }

                if (modelFile.length() != sizeBytes) {
                    return@withContext ModelLoadResult.Corrupted(
                        originalName = originalName,
                        expectedSize = sizeBytes,
                        message = "Размер файла модели «$originalName» (${modelFile.length()} Б) не совпадает с сохранёнными данными ($sizeBytes Б). Требуется повторный импорт."
                    )
                }

                ModelLoadResult.Valid(ModelMetadata(originalName, sizeBytes, internalFileName))
            } catch (e: Exception) {
                ModelLoadResult.Corrupted(
                    originalName = null,
                    expectedSize = null,
                    message = "Ошибка чтения метаданных: ${e.localizedMessage ?: "Неизвестная ошибка"}. Требуется повторный импорт."
                )
            }
        }
    }

    suspend fun saveMetadataCommit(context: Context, metadata: ModelMetadata?): Boolean {
        return withContext(Dispatchers.IO) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            if (metadata == null) {
                editor.clear()
            } else {
                editor.putString(KEY_ORIGINAL_NAME, metadata.originalName)
                    .putLong(KEY_SIZE_BYTES, metadata.sizeBytes)
                    .putString(KEY_INTERNAL_FILENAME, metadata.internalFileName)
            }
            editor.commit()
        }
    }

    suspend fun queryUriDetails(context: Context, uri: Uri): Pair<String?, Long> {
        return withContext(Dispatchers.IO) {
            var displayName: String? = null
            var sizeBytes: Long = -1L

            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)

                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) {
                            displayName = cursor.getString(nameIndex)
                        }
                        if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
                            sizeBytes = cursor.getLong(sizeIndex)
                        }
                    }
                }
            } catch (_: Exception) {
                // ContentResolver query failed
            }

            Pair(displayName, sizeBytes)
        }
    }

    suspend fun copyModelStream(
        context: Context,
        uri: Uri,
        displayName: String,
        totalSize: Long,
        currentMetadata: ModelMetadata?,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit
    ): ModelMetadata {
        return withContext(Dispatchers.IO) {
            if (displayName.isBlank()) {
                throw IllegalArgumentException("Не удалось определить имя документа. Расширение не проверено.")
            }

            if (!ModelImportValidator.isValidExtension(displayName)) {
                throw IllegalArgumentException("Выбран неверный формат файла. Требуется файл с расширением ${ModelImportValidator.REQUIRED_EXTENSION}")
            }

            val modelsDir = getModelsDir(context)
            if (totalSize > 0) {
                val usableSpace = modelsDir.usableSpace
                if (usableSpace < totalSize) {
                    val neededStr = ModelImportValidator.formatFileSize(totalSize)
                    val freeStr = ModelImportValidator.formatFileSize(usableSpace)
                    throw IOException("Недостаточно свободного места на устройстве (требуется $neededStr, свободно $freeStr)")
                }
            }

            val uniqueId = "${System.currentTimeMillis()}_${(1000..9999).random()}"
            val newInternalFileName = "model_$uniqueId.bin"
            val tempFileName = "temp_$uniqueId.bin"

            val tempFile = File(modelsDir, tempFileName)
            val newFile = File(modelsDir, newInternalFileName)

            val fileOps = object : FileOperations {
                override fun createTempFile(): Boolean {
                    if (tempFile.exists()) runCatching { tempFile.delete() }
                    return true
                }

                override suspend fun writeData(): Boolean {
                    val inputStream = context.contentResolver.openInputStream(uri) ?: return false
                    inputStream.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesRead: Int
                            var totalCopied = 0L
                            var lastUpdate = System.currentTimeMillis()

                            onProgress(0L, totalSize)

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                currentCoroutineContext().ensureActive()
                                output.write(buffer, 0, bytesRead)
                                totalCopied += bytesRead

                                val now = System.currentTimeMillis()
                                if (now - lastUpdate >= PROGRESS_UPDATE_INTERVAL_MS) {
                                    lastUpdate = now
                                    onProgress(totalCopied, totalSize)
                                }
                            }

                            output.flush()
                            onProgress(totalCopied, totalSize)
                        }
                    }

                    if (!tempFile.exists() || tempFile.length() <= 0L) return false
                    if (totalSize > 0 && tempFile.length() != totalSize) return false
                    return true
                }

                override fun finalizeNewFile(): Boolean {
                    if (!tempFile.exists() || tempFile.length() <= 0L) return false
                    val renamed = tempFile.renameTo(newFile)
                    if (!renamed) {
                        runCatching {
                            tempFile.copyTo(newFile, overwrite = true)
                            tempFile.delete()
                        }
                    }
                    return newFile.exists() && newFile.length() > 0L
                }

                override fun deleteTempFile() {
                    if (tempFile.exists()) runCatching { tempFile.delete() }
                }

                override fun deleteNewFile() {
                    if (newFile.exists()) runCatching { newFile.delete() }
                }

                override fun deleteOldFile(oldFileName: String): Boolean {
                    if (!ModelImportValidator.isSafeFileName(oldFileName)) return false
                    val oldFile = File(getModelsDir(context), oldFileName)
                    return if (oldFile.exists()) oldFile.delete() else true
                }
            }

            val metaOps = object : MetadataOperations {
                override fun saveMetadataCommit(metadata: ModelMetadata?): Boolean {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val editor = prefs.edit()
                    if (metadata == null) {
                        editor.clear()
                    } else {
                        editor.putString(KEY_ORIGINAL_NAME, metadata.originalName)
                            .putLong(KEY_SIZE_BYTES, metadata.sizeBytes)
                            .putString(KEY_INTERNAL_FILENAME, metadata.internalFileName)
                    }
                    return editor.commit()
                }

                override fun getActiveMetadata(): ModelMetadata? {
                    return currentMetadata
                }
            }

            var replacementResult: ReplacementResult? = null

            try {
                // Critical Section: Execute model replacement with NonCancellable protection
                withContext(NonCancellable) {
                    val expectedMetadata = ModelMetadata(
                        originalName = displayName,
                        sizeBytes = if (tempFile.exists()) tempFile.length() else totalSize,
                        internalFileName = newInternalFileName
                    )

                    val engine = ModelReplacementEngine()
                    replacementResult = engine.executeReplacement(expectedMetadata, fileOps, metaOps)
                }

            } catch (e: CancellationException) {
                if (replacementResult == null) {
                    fileOps.deleteTempFile()
                    fileOps.deleteNewFile()
                }
                throw e
            } catch (e: Exception) {
                if (replacementResult == null) {
                    fileOps.deleteTempFile()
                    fileOps.deleteNewFile()
                }
                throw e
            }

            when (val res = replacementResult) {
                is ReplacementResult.Success -> res.metadata
                is ReplacementResult.RollbackSucceeded -> throw IOException(res.error)
                is ReplacementResult.IndeterminateStorageError -> throw IndeterminateStorageException(res.error)
                null -> throw IOException("Не удалось завершить замену файла модели")
            }
        }
    }
}
