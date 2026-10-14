package com.example.localnewsassistant.model

data class SummaryBulletPoint(
    val originalLine: String,
    val textWithoutCitation: String,
    val citedIds: List<String>,
    val isValidCitation: Boolean,
    val warningMessage: String? = null
)

data class NewsSummaryResult(
    val rawResponse: String,
    val bulletPoints: List<SummaryBulletPoint>,
    val isFormatValid: Boolean,
    val areCitationsValid: Boolean,
    val isSuccess: Boolean,
    val warningMessages: List<String>
)

object NewsSummaryPostProcessor {

    private val END_CITATION_REGEX = Regex("\\[([^]]+)\\]\\s*$")
    private val STRICT_ID_TOKEN_REGEX = Regex("^N\\d{3}$")

    fun processSummary(
        rawResponseText: String,
        dataset: SyntheticNewsDataset
    ): NewsSummaryResult {
        if (rawResponseText.isBlank()) {
            return NewsSummaryResult(
                rawResponse = rawResponseText,
                bulletPoints = emptyList(),
                isFormatValid = false,
                areCitationsValid = false,
                isSuccess = false,
                warningMessages = listOf("Модель вернула пустой ответ.")
            )
        }

        val globalWarnings = mutableListOf<String>()
        val bulletPoints = mutableListOf<SummaryBulletPoint>()
        val unparsedLines = mutableListOf<String>()

        var formatValid = true
        var citationsValid = true

        val lines = rawResponseText.lines()

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isBlank()) continue

            val isBullet = line.startsWith("-") || line.startsWith("•") || line.startsWith("*") || line.matches(Regex("^\\d+\\..*"))

            if (!isBullet) {
                formatValid = false
                unparsedLines.add(line)
                globalWarnings.add("Неструктурированная строка в ответе: '$line'")
                continue
            }

            val isCanonicalMarker = line.startsWith("- ")
            if (!isCanonicalMarker) {
                formatValid = false
                globalWarnings.add("Неканонический маркер пункта в строке: '$line'")
            }

            val citationMatch = END_CITATION_REGEX.find(line)
            if (citationMatch == null) {
                citationsValid = false
                formatValid = false
                val cleanText = line.removePrefix("-").removePrefix("•").removePrefix("*").replace(Regex("^\\d+\\."), "").trim()
                bulletPoints.add(
                    SummaryBulletPoint(
                        originalLine = line,
                        textWithoutCitation = cleanText,
                        citedIds = emptyList(),
                        isValidCitation = false,
                        warningMessage = "В конце пункта отсутствует ссылка формата [N001, N002]"
                    )
                )
                continue
            }

            val bracketStart = citationMatch.range.first
            val lineTextBeforeCitation = line.substring(0, bracketStart).trim()
            val cleanText = lineTextBeforeCitation.removePrefix("-").removePrefix("•").removePrefix("*").replace(Regex("^\\d+\\."), "").trim()

            if (cleanText.isBlank()) {
                formatValid = false
                citationsValid = false
                bulletPoints.add(
                    SummaryBulletPoint(
                        originalLine = line,
                        textWithoutCitation = "",
                        citedIds = emptyList(),
                        isValidCitation = false,
                        warningMessage = "Пункт не содержит описания события, а только ссылку"
                    )
                )
                continue
            }

            val bracketContent = citationMatch.groupValues[1]
            val rawTokens = bracketContent.split(",").map { it.trim() }

            if (rawTokens.isEmpty() || rawTokens.any { it.isBlank() }) {
                formatValid = false
                citationsValid = false
                bulletPoints.add(
                    SummaryBulletPoint(
                        originalLine = line,
                        textWithoutCitation = cleanText,
                        citedIds = emptyList(),
                        isValidCitation = false,
                        warningMessage = "Некорректный синтаксис списка ID в скобках: '$bracketContent'"
                    )
                )
                continue
            }

            var itemCitationValid = true
            val itemWarnings = mutableListOf<String>()
            val itemIds = mutableListOf<String>()
            val seenInItem = mutableSetOf<String>()

            for (token in rawTokens) {
                if (!STRICT_ID_TOKEN_REGEX.matches(token)) {
                    itemCitationValid = false
                    citationsValid = false
                    formatValid = false
                    itemWarnings.add("Токен ID '$token' не соответствует строгой грамматике N***")
                    continue
                }

                if (seenInItem.contains(token)) {
                    formatValid = false
                    itemWarnings.add("Дубликат ID $token в рамках одного пункта")
                } else {
                    seenInItem.add(token)
                }

                if (!dataset.messageIndex.containsKey(token)) {
                    itemCitationValid = false
                    citationsValid = false
                    itemWarnings.add("Указан неизвестный ID новости: $token")
                } else {
                    itemIds.add(token)
                }
            }

            bulletPoints.add(
                SummaryBulletPoint(
                    originalLine = line,
                    textWithoutCitation = cleanText,
                    citedIds = itemIds,
                    isValidCitation = itemCitationValid && itemIds.isNotEmpty(),
                    warningMessage = if (itemWarnings.isNotEmpty()) itemWarnings.joinToString("; ") else null
                )
            )
        }

        if (bulletPoints.size !in 1..3) {
            formatValid = false
            globalWarnings.add("Количество пунктов (${bulletPoints.size}) вышло за допустимый предел 1..3")
        }

        return NewsSummaryResult(
            rawResponse = rawResponseText,
            bulletPoints = bulletPoints,
            isFormatValid = formatValid,
            areCitationsValid = citationsValid && bulletPoints.isNotEmpty() && bulletPoints.all { it.isValidCitation },
            isSuccess = bulletPoints.isNotEmpty(),
            warningMessages = globalWarnings
        )
    }
}
