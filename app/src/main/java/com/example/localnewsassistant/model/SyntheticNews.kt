package com.example.localnewsassistant.model

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

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

object BoundedAssetReader {

    const val MAX_ASSET_BYTES = 51200 // 50 KB limit
    private const val READ_BUFFER_SIZE = 8192

    fun readBoundedStreamBytes(inputStream: InputStream, maxBytes: Int = MAX_ASSET_BYTES): ByteArray {
        inputStream.use { input ->
            val buffer = ByteArray(READ_BUFFER_SIZE)
            var totalRead = 0
            val byteList = java.io.ByteArrayOutputStream()

            while (true) {
                val bytesToRead = minOf(buffer.size, (maxBytes + 1) - totalRead)
                if (bytesToRead <= 0) {
                    throw IllegalArgumentException("Превышен прототипный лимит размера JSON файла (более $maxBytes байт).")
                }

                val read = input.read(buffer, 0, bytesToRead)
                if (read == -1) break

                totalRead += read
                if (totalRead > maxBytes) {
                    throw IllegalArgumentException("Превышен прототипный лимит размера JSON файла (более $maxBytes байт).")
                }

                byteList.write(buffer, 0, read)
            }

            return byteList.toByteArray()
        }
    }

    fun decodeUtf8Strict(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            throw IllegalArgumentException("Некорректная кодировка UTF-8 в исходном файле: ${e.localizedMessage}")
        }
    }
}

object SyntheticNewsValidator {

    const val MAX_MESSAGES_COUNT = 10
    const val MAX_SINGLE_TEXT_CHARS = 1000
    const val MAX_TOTAL_TEXT_CHARS = 3000
    private val STRICT_ID_REGEX = Regex("^N\\d{3}$")

    fun validateDataset(dataset: SyntheticNewsDataset): SyntheticNewsDataset {
        if (dataset.datasetId.isBlank()) {
            throw IllegalArgumentException("Поле datasetId в JSON не может быть пустым.")
        }

        if (!dataset.synthetic) {
            throw IllegalArgumentException("Тестовый набор данных должен содержать параметр synthetic: true.")
        }

        if (dataset.messages.isEmpty()) {
            throw IllegalArgumentException("Список сообщений messages в JSON не может быть пустым.")
        }

        if (dataset.messages.size > MAX_MESSAGES_COUNT) {
            throw IllegalArgumentException("Превышено максимальное количество сообщений (${dataset.messages.size} > $MAX_MESSAGES_COUNT).")
        }

        val seenIds = mutableSetOf<String>()
        var totalTextLength = 0

        for ((index, msg) in dataset.messages.withIndex()) {
            if (msg.id.isBlank()) {
                throw IllegalArgumentException("Сообщение #$index имеет пустое поле id.")
            }

            if (!STRICT_ID_REGEX.matches(msg.id)) {
                throw IllegalArgumentException("Сообщение #$index имеет некорректный формат id '${msg.id}' (ожидается N***).")
            }

            if (seenIds.contains(msg.id)) {
                throw IllegalArgumentException("Обнаружен дубликат идентификатора новости: ${msg.id}.")
            }
            seenIds.add(msg.id)

            if (msg.source.isBlank()) {
                throw IllegalArgumentException("Сообщение ${msg.id} имеет пустое поле source.")
            }

            if (msg.publishedAt.isBlank()) {
                throw IllegalArgumentException("Сообщение ${msg.id} имеет пустое поле publishedAt.")
            }

            try {
                OffsetDateTime.parse(msg.publishedAt, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            } catch (_: Exception) {
                throw IllegalArgumentException("Сообщение ${msg.id} имеет некорректную дату/время '${msg.publishedAt}' (ожидается формат ISO-8601 с часовым поясом).")
            }

            if (msg.text.isBlank()) {
                throw IllegalArgumentException("Сообщение ${msg.id} имеет пустой текст text.")
            }

            if (msg.text.length > MAX_SINGLE_TEXT_CHARS) {
                throw IllegalArgumentException("Длина текста новости ${msg.id} превышает лимит (${msg.text.length} > $MAX_SINGLE_TEXT_CHARS символов).")
            }

            totalTextLength += msg.text.length
        }

        if (totalTextLength > MAX_TOTAL_TEXT_CHARS) {
            throw IllegalArgumentException("Суммарная длина текстов новостей ($totalTextLength символов) превышает лимит ($MAX_TOTAL_TEXT_CHARS символов).")
        }

        return dataset
    }
}

object AndroidJsonReaderParser {

    fun verifyNoSemicolonsOutsideStrings(json: String) {
        var inString = false
        var backslashCount = 0

        for (i in json.indices) {
            val char = json[i]

            if (inString) {
                if (char == '\\') {
                    backslashCount++
                } else if (char == '"') {
                    if (backslashCount % 2 == 0) {
                        inString = false
                    }
                    backslashCount = 0
                } else {
                    backslashCount = 0
                }
            } else {
                if (char == '"') {
                    inString = true
                    backslashCount = 0
                } else if (char == ';') {
                    throw IllegalArgumentException("Обнаружен недопустимый разделитель ';' вне строкового значения в позиции $i.")
                }
            }
        }
    }

    fun parseJson(jsonString: String): SyntheticNewsDataset {
        if (jsonString.isBlank()) {
            throw IllegalArgumentException("Тестовый JSON пуст.")
        }

        verifyNoSemicolonsOutsideStrings(jsonString)

        val reader = JsonReader(StringReader(jsonString)).apply {
            isLenient = false
        }

        return reader.use { jsonReader ->
            if (jsonReader.peek() != JsonToken.BEGIN_OBJECT) {
                throw IllegalArgumentException("Корневой элемент JSON должен быть объектом.")
            }

            var datasetId: String? = null
            var synthetic: Boolean? = null
            var messages: List<SyntheticNewsMessage>? = null
            val rootSeenKeys = mutableSetOf<String>()

            jsonReader.beginObject()
            while (jsonReader.hasNext()) {
                val key = jsonReader.nextName()
                if (rootSeenKeys.contains(key)) {
                    throw IllegalArgumentException("Обнаружено дублирующееся корневое поле: '$key'.")
                }
                rootSeenKeys.add(key)

                when (key) {
                    "datasetId" -> {
                        if (jsonReader.peek() != JsonToken.STRING) {
                            throw IllegalArgumentException("Поле datasetId должно быть строковым значением (STRING).")
                        }
                        datasetId = jsonReader.nextString()
                    }
                    "synthetic" -> {
                        if (jsonReader.peek() != JsonToken.BOOLEAN) {
                            throw IllegalArgumentException("Поле synthetic должно быть логическим значением (BOOLEAN).")
                        }
                        synthetic = jsonReader.nextBoolean()
                    }
                    "messages" -> {
                        if (jsonReader.peek() != JsonToken.BEGIN_ARRAY) {
                            throw IllegalArgumentException("Поле messages должно быть массивом (ARRAY).")
                        }
                        messages = parseMessagesArray(jsonReader)
                    }
                    else -> {
                        throw IllegalArgumentException("Обнаружено неизвестное корневое поле: '$key'.")
                    }
                }
            }
            jsonReader.endObject()

            if (jsonReader.peek() != JsonToken.END_DOCUMENT) {
                throw IllegalArgumentException("Обнаружено постороннее содержимое после завершения корневого JSON-объекта.")
            }

            if (datasetId == null) throw IllegalArgumentException("Обязательное поле datasetId отсутствует в JSON.")
            if (synthetic == null) throw IllegalArgumentException("Обязательное поле synthetic отсутствует в JSON.")
            if (messages == null) throw IllegalArgumentException("Обязательное поле messages отсутствует в JSON.")

            SyntheticNewsDataset(
                datasetId = datasetId,
                synthetic = synthetic,
                messages = messages
            )
        }
    }

    private fun parseMessagesArray(jsonReader: JsonReader): List<SyntheticNewsMessage> {
        val messages = mutableListOf<SyntheticNewsMessage>()
        jsonReader.beginArray()
        while (jsonReader.hasNext()) {
            if (jsonReader.peek() != JsonToken.BEGIN_OBJECT) {
                throw IllegalArgumentException("Элемент массива messages должен быть объектом.")
            }
            messages.add(parseMessageObject(jsonReader))
        }
        jsonReader.endArray()
        return messages
    }

    private fun parseMessageObject(jsonReader: JsonReader): SyntheticNewsMessage {
        var id: String? = null
        var source: String? = null
        var publishedAt: String? = null
        var text: String? = null
        val msgSeenKeys = mutableSetOf<String>()

        jsonReader.beginObject()
        while (jsonReader.hasNext()) {
            val key = jsonReader.nextName()
            if (msgSeenKeys.contains(key)) {
                throw IllegalArgumentException("Обнаружено дублирующееся поле сообщения: '$key'.")
            }
            msgSeenKeys.add(key)

            when (key) {
                "id" -> {
                    if (jsonReader.peek() != JsonToken.STRING) throw IllegalArgumentException("Поле id сообщения должно быть строкой.")
                    id = jsonReader.nextString()
                }
                "source" -> {
                    if (jsonReader.peek() != JsonToken.STRING) throw IllegalArgumentException("Поле source сообщения должно быть строкой.")
                    source = jsonReader.nextString()
                }
                "publishedAt" -> {
                    if (jsonReader.peek() != JsonToken.STRING) throw IllegalArgumentException("Поле publishedAt сообщения должно быть строкой.")
                    publishedAt = jsonReader.nextString()
                }
                "text" -> {
                    if (jsonReader.peek() != JsonToken.STRING) throw IllegalArgumentException("Поле text сообщения должно быть строкой.")
                    text = jsonReader.nextString()
                }
                else -> {
                    throw IllegalArgumentException("Обнаружено неизвестное поле в сообщении: '$key'.")
                }
            }
        }
        jsonReader.endObject()

        if (id == null) throw IllegalArgumentException("Обязательное поле id отсутствует в сообщении.")
        if (source == null) throw IllegalArgumentException("Обязательное поле source отсутствует в сообщении.")
        if (publishedAt == null) throw IllegalArgumentException("Обязательное поле publishedAt отсутствует в сообщении.")
        if (text == null) throw IllegalArgumentException("Обязательное поле text отсутствует в сообщении.")

        return SyntheticNewsMessage(
            id = id,
            source = source,
            publishedAt = publishedAt,
            text = text
        )
    }
}

object SyntheticNewsAssetLoader {

    fun loadAndValidateAsset(context: Context, assetName: String = "test_news.json"): SyntheticNewsDataset {
        val inputStream = context.assets.open(assetName)
        val bytes = BoundedAssetReader.readBoundedStreamBytes(inputStream)
        val jsonString = BoundedAssetReader.decodeUtf8Strict(bytes)
        val parsed = AndroidJsonReaderParser.parseJson(jsonString)
        return SyntheticNewsValidator.validateDataset(parsed)
    }
}

@Deprecated("Используйте SyntheticNewsAssetLoader или BoundedAssetReader + SyntheticNewsValidator")
object SyntheticNewsParser {
    fun parseAndValidate(jsonString: String): SyntheticNewsDataset {
        val bytes = jsonString.toByteArray(Charsets.UTF_8)
        if (bytes.size > BoundedAssetReader.MAX_ASSET_BYTES) {
            throw IllegalArgumentException("Превышен прототипный лимит размера JSON файла (${bytes.size} B > ${BoundedAssetReader.MAX_ASSET_BYTES} B).")
        }
        val decoded = BoundedAssetReader.decodeUtf8Strict(bytes)
        val parsed = AndroidJsonReaderParser.parseJson(decoded)
        return SyntheticNewsValidator.validateDataset(parsed)
    }
}
