package com.example.localnewsassistant

import com.example.localnewsassistant.model.NewsSummaryPostProcessor
import com.example.localnewsassistant.model.NewsSummaryPromptBuilder
import com.example.localnewsassistant.model.SyntheticNewsDataset
import com.example.localnewsassistant.model.SyntheticNewsMessage
import com.example.localnewsassistant.model.SyntheticNewsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsSummaryTest {

    private val sampleDataset = SyntheticNewsDataset(
        datasetId = "synthetic-news-v1",
        synthetic = true,
        messages = listOf(
            SyntheticNewsMessage(
                id = "N001",
                source = "Тестовый источник Альфа",
                publishedAt = "2026-10-12T09:00:00+03:00",
                text = "Вымышленный исследовательский центр «Север» сообщил 12 октября 2026 года, что 15 октября 2026 года откроет лабораторию в городе Ладоград. В лаборатории будет 10 рабочих мест."
            ),
            SyntheticNewsMessage(
                id = "N002",
                source = "Тестовый источник Бета",
                publishedAt = "2026-10-12T10:00:00+03:00",
                text = "Вымышленная компания «Маяк» сообщила 12 октября 2026 года о запуске сервиса «Обзор». Тестирование начнётся 20 октября 2026 года и продлится 7 дней. Стоимость сервиса не указана."
            ),
            SyntheticNewsMessage(
                id = "N003",
                source = "Тестовый источник Гамма",
                publishedAt = "2026-10-12T11:00:00+03:00",
                text = "По сообщению вымышленного центра «Север», 15 октября 2026 года в городе Ладоград откроется его лаборатория с 10 рабочими местами."
            )
        )
    )

    @Test
    fun testA_CanonicalItemWithValidCitationsRecognized() {
        val input = "- Центр «Север» откроет лабораторию в Ладограде. [N001, N003]"
        val result = NewsSummaryPostProcessor.processSummary(input, sampleDataset)

        assertTrue(result.isSuccess)
        assertTrue(result.areCitationsValid)
        assertTrue(result.isFormatValid)
        assertEquals(1, result.bulletPoints.size)

        val item = result.bulletPoints[0]
        assertTrue(item.isValidCitation)
        assertEquals(listOf("N001", "N003"), item.citedIds)
        assertEquals("Центр «Север» откроет лабораторию в Ладограде.", item.textWithoutCitation)
    }

    @Test
    fun testB_UnknownIdRejected() {
        val input = "- Центр «Север» откроет лабораторию в Ладограде. [N999]"
        val result = NewsSummaryPostProcessor.processSummary(input, sampleDataset)

        assertFalse(result.areCitationsValid)
        assertTrue(result.isFormatValid)

        val item = result.bulletPoints[0]
        assertFalse(item.isValidCitation)
        assertNotNull(item.warningMessage)
        assertTrue(item.warningMessage!!.contains("N999"))
    }

    @Test
    fun testC_MixedValidAndInvalidTokenRejected() {
        val input = "- Центр «Север» откроет лабораторию в Ладограде. [N001, UNKNOWN]"
        val result = NewsSummaryPostProcessor.processSummary(input, sampleDataset)

        assertFalse(result.areCitationsValid)
        assertFalse(result.isFormatValid)

        val item = result.bulletPoints[0]
        assertFalse(item.isValidCitation)
        assertNotNull(item.warningMessage)
        assertTrue(item.warningMessage!!.contains("UNKNOWN"))
    }

    @Test
    fun testD_FourDigitOrAffixedTokensNotExtractedAsValidId() {
        val input1 = "- Центр «Север» откроет лабораторию. [N0010]"
        val result1 = NewsSummaryPostProcessor.processSummary(input1, sampleDataset)

        assertFalse(result1.areCitationsValid)

        val input2 = "- Центр «Север» откроет лабораторию. [prefixN001suffix]"
        val result2 = NewsSummaryPostProcessor.processSummary(input2, sampleDataset)

        assertFalse(result2.areCitationsValid)
    }

    @Test
    fun testE_InvalidDelimitersTrailingCommasLowercaseDetected() {
        val inputSemicolon = "- Пункт с точкой с запятой. [N001; N003]"
        assertFalse(NewsSummaryPostProcessor.processSummary(inputSemicolon, sampleDataset).areCitationsValid)

        val inputTrailingComma = "- Пункт с запятой на конце. [N001,]"
        assertFalse(NewsSummaryPostProcessor.processSummary(inputTrailingComma, sampleDataset).areCitationsValid)

        val inputEmptyBracket = "- Пункт со пустыми скобками. []"
        assertFalse(NewsSummaryPostProcessor.processSummary(inputEmptyBracket, sampleDataset).areCitationsValid)

        val inputLowercase = "- Пункт с маленькой буквой. [n001]"
        assertFalse(NewsSummaryPostProcessor.processSummary(inputLowercase, sampleDataset).areCitationsValid)
    }

    @Test
    fun testF_CitationInMiddleOfLineWithExtraTextFailsContract() {
        val input = "- Описание события [N001] и дополнительный текст после ссылки."
        val result = NewsSummaryPostProcessor.processSummary(input, sampleDataset)

        assertFalse(result.areCitationsValid)
        assertFalse(result.isFormatValid)
    }

    @Test
    fun testG_BulletPointWithoutCitationTriggersWarning() {
        val input = "- Описание события без каких-либо скобок."
        val result = NewsSummaryPostProcessor.processSummary(input, sampleDataset)

        assertFalse(result.areCitationsValid)
        assertFalse(result.isFormatValid)
        val item = result.bulletPoints[0]
        assertFalse(item.isValidCitation)
        assertNotNull(item.warningMessage)
    }

    @Test
    fun testH_EmptyDescriptionWithOnlyCitationFails() {
        val input = "- [N001]"
        val result = NewsSummaryPostProcessor.processSummary(input, sampleDataset)

        assertFalse(result.areCitationsValid)
        assertFalse(result.isFormatValid)
        val item = result.bulletPoints[0]
        assertFalse(item.isValidCitation)
    }

    @Test
    fun testI_DuplicateIdInSingleCitationTriggersWarning() {
        val input = "- Описание события. [N001, N001]"
        val result = NewsSummaryPostProcessor.processSummary(input, sampleDataset)

        assertFalse(result.isFormatValid)
        val item = result.bulletPoints[0]
        assertNotNull(item.warningMessage)
        assertTrue(item.warningMessage!!.contains("Дубликат ID N001"))
    }

    @Test
    fun testJ_FourBulletItemsNotTrimmedAndTriggerCountWarning() {
        val input = """
            - Пункт 1. [N001]
            - Пункт 2. [N002]
            - Пункт 3. [N003]
            - Пункт 4. [N001]
        """.trimIndent()

        val result = NewsSummaryPostProcessor.processSummary(input, sampleDataset)

        assertEquals(4, result.bulletPoints.size)
        assertFalse(result.isFormatValid)
        assertTrue(result.warningMessages.any { it.contains("вышло за допустимый предел") })
    }

    @Test
    fun testK_NonBulletLinesPreservedInWarningsAndRawResponse() {
        val input = """
            Заголовок сводки:
            - Пункт 1. [N001]
            Пояснительный текст в конце.
        """.trimIndent()

        val result = NewsSummaryPostProcessor.processSummary(input, sampleDataset)

        assertEquals(input, result.rawResponse)
        assertFalse(result.isFormatValid)
        assertTrue(result.warningMessages.any { it.contains("Неструктурированная строка") })
    }

    @Test
    fun testL_WhitespaceOnlyResponseIsNotSuccess() {
        val result = NewsSummaryPostProcessor.processSummary("   \n\t  ", sampleDataset)

        assertFalse(result.isSuccess)
        assertFalse(result.areCitationsValid)
        assertFalse(result.isFormatValid)
    }

    @Test
    fun testM_RealPhoneBaselineResponseHasNoCitationsAndNonCanonicalMarkers() {
        val realPhoneResponse = """
            1. Лаборатория «Север» планирует открытие в Ладограде 15 октября 2026 года.
            2. «Маяк» запускает сервис «Обзор» с тестированием на 20 октября 2026 года.
            3. Лаборатория «Север» намерена создать 10 рабочих мест в Ладограде.
        """.trimIndent()

        val result = NewsSummaryPostProcessor.processSummary(realPhoneResponse, sampleDataset)

        assertEquals(realPhoneResponse, result.rawResponse)
        assertEquals(3, result.bulletPoints.size)
        assertFalse(result.areCitationsValid)
        assertFalse(result.isFormatValid)
        assertTrue(result.bulletPoints.all { !it.isValidCitation })
        assertTrue(result.warningMessages.any { it.contains("Неканонический маркер") })
    }

    @Test
    fun testN_SourceDetailsRetrievedFromDatasetIndexNotModelText() {
        val sourceN001 = sampleDataset.messageIndex["N001"]
        assertNotNull(sourceN001)
        assertEquals("Тестовый источник Альфа", sourceN001!!.source)
        assertTrue(sourceN001.text.contains("Ладоград"))
    }

    @Test
    fun testO_SyntacticallyValidCitationDoesNotImplyFactualTruth() {
        val input = "- Центр «Север» уже открыл 100 лабораторий вчера. [N001]"
        val result = NewsSummaryPostProcessor.processSummary(input, sampleDataset)

        assertTrue(result.areCitationsValid)
        // Citation syntax/IDs are valid, but factual truth is NOT verified by citation syntax check
        assertTrue(result.bulletPoints[0].isValidCitation)
    }

    @Test
    fun testP_PromptBuilderContainsDataDelimitersAndAllMessages() {
        val prompt = NewsSummaryPromptBuilder.buildPrompt(sampleDataset)

        assertTrue(prompt.contains("<DATA_START>"))
        assertTrue(prompt.contains("<DATA_END>"))
        assertTrue(prompt.contains("[N001]"))
        assertTrue(prompt.contains("[N002]"))
        assertTrue(prompt.contains("[N003]"))
        assertTrue(prompt.contains("Тестовый источник Альфа"))
        assertTrue(prompt.contains("Ладоград"))
    }

    @Test
    fun testQ_SyntheticNewsParserSmokeTest() {
        val jsonInput = """
        {
          "datasetId": "synthetic-news-v1",
          "synthetic": true,
          "messages": [
            {
              "id": "N001",
              "source": "Тестовый источник Альфа",
              "publishedAt": "2026-10-12T09:00:00+03:00",
              "text": "Тестовый текст сообщения N001."
            }
          ]
        }
        """.trimIndent()

        val parsedDataset = SyntheticNewsParser.parseAndValidate(jsonInput)

        assertEquals("synthetic-news-v1", parsedDataset.datasetId)
        assertTrue(parsedDataset.synthetic)
        assertEquals(1, parsedDataset.messages.size)
        assertNotNull(parsedDataset.messageIndex["N001"])
        assertEquals("Тестовый источник Альфа", parsedDataset.messageIndex["N001"]?.source)
    }
}
