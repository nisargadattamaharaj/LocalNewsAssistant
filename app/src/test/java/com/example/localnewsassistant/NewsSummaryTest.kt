package com.example.localnewsassistant

import com.example.localnewsassistant.model.AndroidJsonReaderParser
import com.example.localnewsassistant.model.BoundedAssetReader
import com.example.localnewsassistant.model.NewsSummaryPostProcessor
import com.example.localnewsassistant.model.NewsSummaryPromptBuilder
import com.example.localnewsassistant.model.SyntheticNewsDataset
import com.example.localnewsassistant.model.SyntheticNewsMessage
import com.example.localnewsassistant.model.SyntheticNewsValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class TrackingInputStream(
    private val delegate: InputStream,
    private val throwOnRead: Boolean = false
) : InputStream() {
    var bytesReadCount = 0
    var isClosed = false

    override fun read(): Int {
        if (throwOnRead) throw IOException("Simulated stream read error")
        val b = delegate.read()
        if (b != -1) bytesReadCount++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (throwOnRead) throw IOException("Simulated stream read error")
        val count = delegate.read(b, off, len)
        if (count != -1) bytesReadCount += count
        return count
    }

    override fun close() {
        isClosed = true
        delegate.close()
    }
}

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
        assertTrue(result.bulletPoints[0].isValidCitation)
    }

    @Test
    fun testP_PromptBuilderContainsDataDelimitersAndAllMessages() {
        val prompt = NewsSummaryPromptBuilder.buildPrompt(sampleDataset)

        val dataStartMarker = "\n<DATA_START>\n"
        val dataEndMarker = "\n<DATA_END>\n"

        val dataStartPos = prompt.indexOf(dataStartMarker)
        val dataEndPos = prompt.indexOf(dataEndMarker)
        val finalSummaryPos = prompt.lastIndexOf("Сводка:")

        assertTrue("Не найдена отдельная строка <DATA_START>", dataStartPos != -1)
        assertTrue("Не найдена отдельная строка <DATA_END>", dataEndPos != -1)
        assertTrue("Блок данных должен быть до финального приглашения Сводка:", dataEndPos < finalSummaryPos)

        val dataBlock = prompt.substring(dataStartPos + dataStartMarker.length, dataEndPos)

        val n001Pos = dataBlock.indexOf("[N001]")
        val n002Pos = dataBlock.indexOf("[N002]")
        val n003Pos = dataBlock.indexOf("[N003]")

        assertTrue("N001 должен быть внутри блока данных", n001Pos != -1)
        assertTrue("N002 должен быть внутри блока данных", n002Pos != -1)
        assertTrue("N003 должен быть внутри блока данных", n003Pos != -1)
        assertTrue("Порядок ID внутри блока данных должен сохраняться", n001Pos < n002Pos && n002Pos < n003Pos)

        sampleDataset.messages.forEach { msg ->
            assertTrue("Блок данных должен содержать ID ${msg.id}", dataBlock.contains(msg.id))
            assertTrue("Блок данных должен содержать источник ${msg.source}", dataBlock.contains(msg.source))
            assertTrue("Блок данных должен содержать дату ${msg.publishedAt}", dataBlock.contains(msg.publishedAt))
            assertTrue("Блок данных должен содержать текст ${msg.text}", dataBlock.contains(msg.text))
        }

        val rulesBlock = prompt.substring(0, dataStartPos)
        assertTrue(rulesBlock.contains("Отрази все разные события"))
        assertTrue(rulesBlock.contains("сначала «- »"))
        assertTrue(rulesBlock.contains("в самом конце реальные ID"))

        assertFalse(prompt.contains("Лаборатория «Север» планирует открытие"))
    }

    @Test
    fun testR_PromptBuilderDynamicInputChangesDataBlock() {
        val customDataset = SyntheticNewsDataset(
            datasetId = "custom-v1",
            synthetic = true,
            messages = listOf(
                SyntheticNewsMessage(
                    id = "X100",
                    source = "Кастомный Источник",
                    publishedAt = "2026-12-01T12:00:00Z",
                    text = "Текст кастомной новости X100."
                )
            )
        )

        val customPrompt = NewsSummaryPromptBuilder.buildPrompt(customDataset)

        assertTrue(customPrompt.contains("[X100]"))
        assertTrue(customPrompt.contains("Кастомный Источник"))
        assertTrue(customPrompt.contains("Текст кастомной новости X100."))
        assertFalse(customPrompt.contains("[N001]"))
    }

    @Test
    fun testS_PromptMetricsCalculation() {
        val prompt = NewsSummaryPromptBuilder.buildPrompt(sampleDataset)
        val bytes = prompt.toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        val hex = digest.joinToString("") { "%02x".format(it) }

        assertEquals(1509, prompt.length)
        assertEquals(2595, bytes.size)
        assertEquals("f13ed5220426b4e0583e28524728057e4ec9e798250db283312f8d6358be8ca3", hex)
    }

    @Test
    fun testSemicolonGuard_RejectsSemicolonsOutsideStrings() {
        try {
            AndroidJsonReaderParser.verifyNoSemicolonsOutsideStrings("{\"datasetId\": \"v1\"; \"synthetic\": true}")
            fail("Should reject semicolon between object fields")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("разделитель ';'"))
        }

        try {
            AndroidJsonReaderParser.verifyNoSemicolonsOutsideStrings("{\"messages\": [1; 2]}")
            fail("Should reject semicolon in array")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("разделитель ';'"))
        }
    }

    @Test
    fun testSemicolonGuard_AllowsSemicolonsInsideStringsAndEscapedQuotes() {
        // Valid semicolon inside string
        AndroidJsonReaderParser.verifyNoSemicolonsOutsideStrings("{\"text\": \"Текст; с точкой с запятой\"}")

        // Valid semicolon inside string with escaped quotes
        AndroidJsonReaderParser.verifyNoSemicolonsOutsideStrings("{\"text\": \"Текст \\\"Цитата;\\\" продолжение\"}")

        // Escaped backslash before quote closes string, so semicolon after is outside string!
        try {
            AndroidJsonReaderParser.verifyNoSemicolonsOutsideStrings("{\"text\": \"значение\\\\\" ; \"key\": 1}")
            fail("Should reject semicolon outside string after escaped backslash")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("разделитель ';'"))
        }
    }

    @Test
    fun testValidator_ValidDatasetPasses() {
        val validated = SyntheticNewsValidator.validateDataset(sampleDataset)
        assertEquals(sampleDataset, validated)
    }

    @Test
    fun testValidator_RejectsBlankDatasetIdAndEmptyMessages() {
        try {
            SyntheticNewsValidator.validateDataset(sampleDataset.copy(datasetId = "   "))
            fail("Should reject blank datasetId")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("datasetId"))
        }

        try {
            SyntheticNewsValidator.validateDataset(sampleDataset.copy(messages = emptyList()))
            fail("Should reject empty messages")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("messages"))
        }
    }

    @Test
    fun testValidator_RejectsSyntheticFalse() {
        val invalid = sampleDataset.copy(synthetic = false)
        try {
            SyntheticNewsValidator.validateDataset(invalid)
            fail("Should have rejected synthetic: false")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("synthetic: true"))
        }
    }

    @Test
    fun testValidator_RejectsInvalidAndDuplicateIds() {
        val invalidDup = sampleDataset.copy(
            messages = sampleDataset.messages + sampleDataset.messages[0]
        )
        try {
            SyntheticNewsValidator.validateDataset(invalidDup)
            fail("Should have rejected duplicate ID")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("дубликат"))
        }

        val invalidLowercase = sampleDataset.copy(
            messages = listOf(sampleDataset.messages[0].copy(id = "n001"))
        )
        try {
            SyntheticNewsValidator.validateDataset(invalidLowercase)
            fail("Should have rejected lowercase id")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("некорректный формат id"))
        }
    }

    @Test
    fun testValidator_RejectsBlankFields() {
        try {
            SyntheticNewsValidator.validateDataset(sampleDataset.copy(messages = listOf(sampleDataset.messages[0].copy(source = "  "))))
            fail("Should reject blank source")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("source"))
        }

        try {
            SyntheticNewsValidator.validateDataset(sampleDataset.copy(messages = listOf(sampleDataset.messages[0].copy(text = "  "))))
            fail("Should reject blank text")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("text"))
        }
    }

    @Test
    fun testValidator_ValidIsoOffsetDatesPass() {
        val validUtc = sampleDataset.copy(messages = listOf(sampleDataset.messages[0].copy(publishedAt = "2026-10-12T06:00:00Z")))
        assertEquals(validUtc, SyntheticNewsValidator.validateDataset(validUtc))
    }

    @Test
    fun testValidator_RejectsInvalidIsoDatesAndMissingOffset() {
        val invalidLeap = sampleDataset.copy(messages = listOf(sampleDataset.messages[0].copy(publishedAt = "2026-02-30T09:00:00+03:00")))
        try {
            SyntheticNewsValidator.validateDataset(invalidLeap)
            fail("Should reject invalid leap day")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("некорректную дату"))
        }

        val missingOffset = sampleDataset.copy(messages = listOf(sampleDataset.messages[0].copy(publishedAt = "2026-10-12T09:00:00")))
        try {
            SyntheticNewsValidator.validateDataset(missingOffset)
            fail("Should reject date without timezone offset")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("некорректную дату"))
        }

        val notADate = sampleDataset.copy(messages = listOf(sampleDataset.messages[0].copy(publishedAt = "NOT_A_DATE")))
        try {
            SyntheticNewsValidator.validateDataset(notADate)
            fail("Should reject NOT_A_DATE")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("некорректную дату"))
        }
    }

    @Test
    fun testValidator_MessageCountLimit10Passed11Rejected() {
        val tenMsgs = (1..10).map { i ->
            val id = String.format("N%03d", i)
            SyntheticNewsMessage(id, "Source", "2026-10-12T09:00:00Z", "Text $i")
        }
        val valid10 = SyntheticNewsDataset("v1", true, tenMsgs)
        assertEquals(valid10, SyntheticNewsValidator.validateDataset(valid10))

        val elevenMsgs = (1..11).map { i ->
            val id = String.format("N%03d", i)
            SyntheticNewsMessage(id, "Source", "2026-10-12T09:00:00Z", "Text $i")
        }
        val invalid11 = SyntheticNewsDataset("v1", true, elevenMsgs)
        try {
            SyntheticNewsValidator.validateDataset(invalid11)
            fail("Should reject 11 messages")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Превышено максимальное количество"))
        }
    }

    @Test
    fun testValidator_SingleTextAndTotalTextLengthLimits() {
        val text1000 = "a".repeat(1000)
        val validSingle = sampleDataset.copy(messages = listOf(sampleDataset.messages[0].copy(text = text1000)))
        assertEquals(validSingle, SyntheticNewsValidator.validateDataset(validSingle))

        val text1001 = "a".repeat(1001)
        val invalidSingle = sampleDataset.copy(messages = listOf(sampleDataset.messages[0].copy(text = text1001)))
        try {
            SyntheticNewsValidator.validateDataset(invalidSingle)
            fail("Should reject text over 1000 chars")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("превышает лимит"))
        }

        // Total text 3001 chars across 4 messages (<1000 each)
        val msg1 = SyntheticNewsMessage("N001", "Source", "2026-10-12T09:00:00Z", "a".repeat(1000))
        val msg2 = SyntheticNewsMessage("N002", "Source", "2026-10-12T09:00:00Z", "a".repeat(1000))
        val msg3 = SyntheticNewsMessage("N003", "Source", "2026-10-12T09:00:00Z", "a".repeat(1000))
        val msg4 = SyntheticNewsMessage("N004", "Source", "2026-10-12T09:00:00Z", "b") // Total 3001
        val invalidTotal = SyntheticNewsDataset("v1", true, listOf(msg1, msg2, msg3, msg4))
        try {
            SyntheticNewsValidator.validateDataset(invalidTotal)
            fail("Should reject total text over 3000 chars")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Суммарная длина"))
        }
    }

    @Test
    fun testBoundedReader_Exactly51200BytesAcceptedAndClosed() {
        val exactData = ByteArray(51200) { 0x61 } // Exact 50 KB
        val rawStream = ByteArrayInputStream(exactData)
        val trackingStream = TrackingInputStream(rawStream)

        val readBytes = BoundedAssetReader.readBoundedStreamBytes(trackingStream, 51200)
        assertEquals(51200, readBytes.size)
        assertTrue(trackingStream.isClosed)
        assertEquals(51200, trackingStream.bytesReadCount)
    }

    @Test
    fun testBoundedReader_51201BytesRejectedAndClosed() {
        val overflowData = ByteArray(51201) { 0x61 } // 50 KB + 1 byte
        val rawStream = ByteArrayInputStream(overflowData)
        val trackingStream = TrackingInputStream(rawStream)

        try {
            BoundedAssetReader.readBoundedStreamBytes(trackingStream, 51200)
            fail("Should reject 51201 bytes")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Превышен прототипный лимит"))
        }
        assertTrue(trackingStream.isClosed)
        assertTrue(trackingStream.bytesReadCount <= 51201)
    }

    @Test
    fun testBoundedReader_LargeStreamReadsAtMost51201BytesAndClosed() {
        val largeData = ByteArray(100000) { 0x61 } // 100 KB stream
        val rawStream = ByteArrayInputStream(largeData)
        val trackingStream = TrackingInputStream(rawStream)

        try {
            BoundedAssetReader.readBoundedStreamBytes(trackingStream, 51200)
            fail("Should reject stream over 50 KB")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Превышен прототипный лимит"))
        }

        assertTrue(trackingStream.isClosed)
        assertTrue("Stream must NOT be fully read (read ${trackingStream.bytesReadCount} <= 51201)", trackingStream.bytesReadCount <= 51201)
    }

    @Test
    fun testBoundedReader_IOExceptionPropagatesAndClosed() {
        val rawStream = ByteArrayInputStream("test".toByteArray())
        val trackingStream = TrackingInputStream(rawStream, throwOnRead = true)

        try {
            BoundedAssetReader.readBoundedStreamBytes(trackingStream, 51200)
            fail("Should propagate IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Simulated stream read error"))
        }
        assertTrue(trackingStream.isClosed)
    }

    @Test
    fun testBoundedReader_StrictUtf8DecoderRejectsMalformedBytesAndPreservesCyrillic() {
        val cyrillicText = "Тестовая сводка новостей"
        val validBytes = cyrillicText.toByteArray(Charsets.UTF_8)
        val decoded = BoundedAssetReader.decodeUtf8Strict(validBytes)
        assertEquals(cyrillicText, decoded)

        val malformedUtf8 = byteArrayOf(0xC3.toByte(), 0x28.toByte()) // Invalid UTF-8 sequence
        try {
            BoundedAssetReader.decodeUtf8Strict(malformedUtf8)
            fail("Should have rejected invalid UTF-8")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Некорректная кодировка UTF-8"))
        }
    }
}
