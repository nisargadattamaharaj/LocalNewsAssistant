package com.example.localnewsassistant.model

data class SyntheticNewsMessage(
    val id: String,
    val source: String,
    val publishedAt: String,
    val text: String
)

data class SyntheticNewsDataset(
    val datasetId: String,
    val synthetic: Boolean,
    val messages: List<SyntheticNewsMessage>
) {
    val messageIndex: Map<String, SyntheticNewsMessage> by lazy {
        messages.associateBy { it.id }
    }
}

object SyntheticNewsParser {

    const val MAX_ASSET_BYTES = 51200 // 50 KB
    const val MAX_MESSAGES_COUNT = 10
    const val MAX_SINGLE_TEXT_CHARS = 1000
    const val MAX_TOTAL_TEXT_CHARS = 3000

    fun parseAndValidate(jsonString: String): SyntheticNewsDataset {
        if (jsonString.isBlank()) {
            throw IllegalArgumentException("Тестовый JSON пуст.")
        }

        val bytesSize = jsonString.toByteArray().size
        if (bytesSize > MAX_ASSET_BYTES) {
            throw IllegalArgumentException("Превышен прототипный лимит размера JSON файла ($bytesSize B > $MAX_ASSET_BYTES B).")
        }

        val datasetId = extractStringValue(jsonString, "datasetId")
            ?: throw IllegalArgumentException("Поле datasetId в JSON не может быть пустым.")

        val syntheticStr = extractPrimitiveValue(jsonString, "synthetic")
        if (syntheticStr != "true") {
            throw IllegalArgumentException("Тестовый набор данных должен содержать параметр synthetic: true.")
        }

        val messageObjects = extractJsonObjectsFromArray(jsonString, "messages")
        if (messageObjects.isEmpty()) {
            throw IllegalArgumentException("Список сообщений messages в JSON не может быть пустым.")
        }

        if (messageObjects.size > MAX_MESSAGES_COUNT) {
            throw IllegalArgumentException("Превышено максимальное прототипное количество сообщений (${messageObjects.size} > $MAX_MESSAGES_COUNT).")
        }

        val messagesList = mutableListOf<SyntheticNewsMessage>()
        val seenIds = mutableSetOf<String>()
        var totalTextLength = 0

        for ((index, objStr) in messageObjects.withIndex()) {
            val id = extractStringValue(objStr, "id")?.trim()
            if (id.isNullOrBlank()) {
                throw IllegalArgumentException("Сообщение #$index имеет пустое поле id.")
            }
            if (seenIds.contains(id)) {
                throw IllegalArgumentException("Обнаружен дубликат идентификатора новости: $id.")
            }
            seenIds.add(id)

            val source = extractStringValue(objStr, "source")?.trim()
            if (source.isNullOrBlank()) {
                throw IllegalArgumentException("Сообщение $id имеет пустое поле source.")
            }

            val publishedAt = extractStringValue(objStr, "publishedAt")?.trim()
            if (publishedAt.isNullOrBlank()) {
                throw IllegalArgumentException("Сообщение $id имеет пустое поле publishedAt.")
            }
            if (!publishedAt.contains("T")) {
                throw IllegalArgumentException("Сообщение $id имеет некорректный формат даты publishedAt (ожидается формат ISO-8601).")
            }

            val text = extractStringValue(objStr, "text")?.trim()
            if (text.isNullOrBlank()) {
                throw IllegalArgumentException("Сообщение $id имеет пустой текст text.")
            }
            if (text.length > MAX_SINGLE_TEXT_CHARS) {
                throw IllegalArgumentException("Длина текста новости $id превышает прототипный лимит (${text.length} > $MAX_SINGLE_TEXT_CHARS символов).")
            }

            totalTextLength += text.length
            messagesList.add(
                SyntheticNewsMessage(
                    id = id,
                    source = source,
                    publishedAt = publishedAt,
                    text = text
                )
            )
        }

        if (totalTextLength > MAX_TOTAL_TEXT_CHARS) {
            throw IllegalArgumentException("Суммарная длина текстов новостей ($totalTextLength символов) превышает прототипный лимит ($MAX_TOTAL_TEXT_CHARS символов).")
        }

        return SyntheticNewsDataset(
            datasetId = datasetId,
            synthetic = true,
            messages = messagesList
        )
    }

    private fun extractStringValue(json: String, key: String): String? {
        val pattern = Regex("\"$key\"\\s*:\\s*\"((?:\\\\\"|[^\"])*)\"")
        val match = pattern.find(json) ?: return null
        return match.groupValues[1]
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }

    private fun extractPrimitiveValue(json: String, key: String): String? {
        val pattern = Regex("\"$key\"\\s*:\\s*([true|false|\\d|.-]+)")
        val match = pattern.find(json) ?: return null
        return match.groupValues[1].trim()
    }

    private fun extractJsonObjectsFromArray(json: String, arrayKey: String): List<String> {
        val arrayStartKey = "\"$arrayKey\"\\s*:\\s*\\["
        val match = Regex(arrayStartKey).find(json) ?: return emptyList()
        val startIndex = match.range.last + 1

        var depth = 0
        var arrayEndIndex = -1
        for (i in startIndex until json.length) {
            val char = json[i]
            if (char == '[') depth++
            else if (char == ']') {
                if (depth == 0) {
                    arrayEndIndex = i
                    break
                } else {
                    depth--
                }
            }
        }

        if (arrayEndIndex == -1) return emptyList()
        val arrayContent = json.substring(startIndex, arrayEndIndex)

        val objects = mutableListOf<String>()
        var objDepth = 0
        var objStart = -1

        for (i in arrayContent.indices) {
            val char = arrayContent[i]
            if (char == '{') {
                if (objDepth == 0) {
                    objStart = i
                }
                objDepth++
            } else if (char == '}') {
                objDepth--
                if (objDepth == 0 && objStart != -1) {
                    objects.add(arrayContent.substring(objStart, i + 1))
                    objStart = -1
                }
            }
        }

        return objects
    }
}
