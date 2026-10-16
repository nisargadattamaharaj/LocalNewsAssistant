package com.example.localnewsassistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.localnewsassistant.model.AndroidJsonReaderParser
import com.example.localnewsassistant.model.NewsSummaryPromptBuilder
import com.example.localnewsassistant.model.SyntheticNewsAssetLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class SyntheticNewsInputInstrumentedTest {

    @Test
    fun testRealAssetParsingAndValidation() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val dataset = SyntheticNewsAssetLoader.loadAndValidateAsset(targetContext, "test_news.json")

        assertEquals("synthetic-news-v1", dataset.datasetId)
        assertTrue(dataset.synthetic)
        assertEquals(3, dataset.messages.size)

        val n001 = dataset.messageIndex["N001"]
        assertNotNull(n001)
        assertEquals("Тестовый источник Альфа", n001!!.source)
        assertEquals("2026-10-12T09:00:00+03:00", n001.publishedAt)
        assertTrue(n001.text.contains("Ладоград"))

        val n002 = dataset.messageIndex["N002"]
        assertNotNull(n002)
        assertEquals("Тестовый источник Бета", n002!!.source)
        assertTrue(n002.text.contains("Маяк"))

        val n003 = dataset.messageIndex["N003"]
        assertNotNull(n003)
        assertEquals("Тестовый источник Гамма", n003!!.source)
    }

    @Test
    fun testEscapedCharactersInJson() {
        val jsonWithEscapes = """
        {
          "datasetId": "escaped-v1",
          "synthetic": true,
          "messages": [
            {
              "id": "N001",
              "source": "Источник \"Цитата\"",
              "publishedAt": "2026-10-12T09:00:00+03:00",
              "text": "строка\nпосле\\путь\u0422"
            }
          ]
        }
        """.trimIndent()

        val dataset = AndroidJsonReaderParser.parseJson(jsonWithEscapes)
        val msg = dataset.messages[0]

        assertEquals("Источник \"Цитата\"", msg.source)
        assertEquals("строка\nпосле\\путьТ", msg.text)
    }

    @Test
    fun testUnicodeEscapeDecoding() {
        val jsonWithUnicode = """
        {
          "datasetId": "unicode-v1",
          "synthetic": true,
          "messages": [
            {
              "id": "N001",
              "source": "Source",
              "publishedAt": "2026-10-12T09:00:00+03:00",
              "text": "\u0422\u0435\u0441\u0442"
            }
          ]
        }
        """.trimIndent()

        val dataset = AndroidJsonReaderParser.parseJson(jsonWithUnicode)
        assertEquals("Тест", dataset.messages[0].text)
    }

    @Test
    fun testRejectsNumericTextToken() {
        val json = """
        {
          "datasetId": "v1",
          "synthetic": true,
          "messages": [
            {
              "id": "N001",
              "source": "Source",
              "publishedAt": "2026-10-12T09:00:00+03:00",
              "text": 123
            }
          ]
        }
        """.trimIndent()

        try {
            AndroidJsonReaderParser.parseJson(json)
            fail("Should reject numeric token for text field")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("строкой"))
        }
    }

    @Test
    fun testRejectsNullMessagesArray() {
        val json = """
        {
          "datasetId": "v1",
          "synthetic": true,
          "messages": null
        }
        """.trimIndent()

        try {
            AndroidJsonReaderParser.parseJson(json)
            fail("Should reject null for messages array")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("ARRAY"))
        }
    }

    @Test
    fun testRejectsJsonCommentsInStrictMode() {
        val blockCommentJson = """
        {
          /* Block comment */
          "datasetId": "v1",
          "synthetic": true,
          "messages": []
        }
        """.trimIndent()

        try {
            AndroidJsonReaderParser.parseJson(blockCommentJson)
            fail("Should reject block comments in strict mode")
        } catch (e: Exception) {
            assertTrue(e is IOException || e is IllegalArgumentException)
        }

        val lineCommentJson = """
        {
          // Line comment
          "datasetId": "v1",
          "synthetic": true,
          "messages": []
        }
        """.trimIndent()

        try {
            AndroidJsonReaderParser.parseJson(lineCommentJson)
            fail("Should reject line comments in strict mode")
        } catch (e: Exception) {
            assertTrue(e is IOException || e is IllegalArgumentException)
        }
    }

    @Test
    fun testRejectsSemicolonsInObject() {
        val semicolonObjectJson = """
        {
          "datasetId": "v1";
          "synthetic": true,
          "messages": []
        }
        """.trimIndent()

        try {
            AndroidJsonReaderParser.parseJson(semicolonObjectJson)
            fail("Should reject semicolon in object")
        } catch (e: Exception) {
            assertTrue(e is IOException || e is IllegalArgumentException)
        }
    }

    @Test
    fun testRejectsSemicolonsInArray() {
        val semicolonArrayJson = """
        {
          "datasetId": "v1",
          "synthetic": true,
          "messages": [
            {"id": "N001", "source": "S1", "publishedAt": "2026-10-12T09:00:00+03:00", "text": "T1"};
            {"id": "N002", "source": "S2", "publishedAt": "2026-10-12T09:00:00+03:00", "text": "T2"}
          ]
        }
        """.trimIndent()

        try {
            AndroidJsonReaderParser.parseJson(semicolonArrayJson)
            fail("Should reject semicolon in array")
        } catch (e: Exception) {
            assertTrue(e is IOException || e is IllegalArgumentException)
        }
    }

    @Test
    fun testAllowsSemicolonsInsideStrings() {
        val validWithSemicolonInString = """
        {
          "datasetId": "v1",
          "synthetic": true,
          "messages": [
            {
              "id": "N001",
              "source": "Источник \"Цитата;\"",
              "publishedAt": "2026-10-12T09:00:00+03:00",
              "text": "Текст; с точкой с запятой и Unicode \u0422;est."
            }
          ]
        }
        """.trimIndent()

        val dataset = AndroidJsonReaderParser.parseJson(validWithSemicolonInString)
        val msg = dataset.messages[0]

        assertEquals("Источник \"Цитата;\"", msg.source)
        assertEquals("Текст; с точкой с запятой и Unicode Т;est.", msg.text)

        // Escaped backslash before quote closes string, so semicolon after is outside string
        val semicolonOutsideAfterEscapedSlash = """
        {
          "datasetId": "v1",
          "synthetic": true,
          "messages": [
            {
              "id": "N001",
              "source": "Source\\";
              "publishedAt": "2026-10-12T09:00:00+03:00",
              "text": "Text"
            }
          ]
        }
        """.trimIndent()

        try {
            AndroidJsonReaderParser.parseJson(semicolonOutsideAfterEscapedSlash)
            fail("Should reject semicolon outside string after escaped backslash")
        } catch (e: Exception) {
            assertTrue(e is IOException || e is IllegalArgumentException)
        }
    }

    @Test
    fun testRejectsStringSynthetic() {
        val json = """
        {
          "datasetId": "v1",
          "synthetic": "true",
          "messages": []
        }
        """.trimIndent()
        try {
            AndroidJsonReaderParser.parseJson(json)
            fail("Should reject string synthetic value 'true'")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("BOOLEAN"))
        }
    }

    @Test
    fun testRejectsDuplicateRootKey() {
        val json = """
        {
          "datasetId": "v1",
          "datasetId": "v2",
          "synthetic": true,
          "messages": []
        }
        """.trimIndent()
        try {
            AndroidJsonReaderParser.parseJson(json)
            fail("Should reject duplicate root key")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("дублирующееся"))
        }
    }

    @Test
    fun testRejectsUnknownRootKey() {
        val json = """
        {
          "datasetId": "v1",
          "synthetic": true,
          "unknown_field": 123,
          "messages": []
        }
        """.trimIndent()
        try {
            AndroidJsonReaderParser.parseJson(json)
            fail("Should reject unknown root key")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("неизвестное"))
        }
    }

    @Test
    fun testRejectsMissingRequiredMessageFields() {
        val json = """
        {
          "datasetId": "v1",
          "synthetic": true,
          "messages": [
            {
              "id": "N001",
              "source": "Source",
              "publishedAt": "2026-10-12T09:00:00+03:00"
            }
          ]
        }
        """.trimIndent()
        try {
            AndroidJsonReaderParser.parseJson(json)
            fail("Should reject message missing required 'text' field")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("отсутствует"))
        }
    }

    @Test
    fun testRejectsDuplicateMessageKey() {
        val json = """
        {
          "datasetId": "v1",
          "synthetic": true,
          "messages": [
            {
              "id": "N001",
              "id": "N002",
              "source": "Source",
              "publishedAt": "2026-10-12T09:00:00+03:00",
              "text": "Text"
            }
          ]
        }
        """.trimIndent()
        try {
            AndroidJsonReaderParser.parseJson(json)
            fail("Should reject duplicate message key")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("дублирующееся"))
        }
    }

    @Test
    fun testRejectsUnknownMessageKey() {
        val json = """
        {
          "datasetId": "v1",
          "synthetic": true,
          "messages": [
            {
              "id": "N001",
              "source": "Source",
              "publishedAt": "2026-10-12T09:00:00+03:00",
              "text": "Text",
              "extra_field": "val"
            }
          ]
        }
        """.trimIndent()
        try {
            AndroidJsonReaderParser.parseJson(json)
            fail("Should reject unknown message key")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("неизвестное"))
        }
    }

    @Test
    fun testRejectsRootArray() {
        val json = """
        [
          {"datasetId": "v1"}
        ]
        """.trimIndent()
        try {
            AndroidJsonReaderParser.parseJson(json)
            fail("Should reject root array")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("объектом"))
        }
    }

    @Test
    fun testRejectsMalformedJsonAndTrailingContent() {
        val json = """
        {
          "datasetId": "v1",
          "synthetic": true,
          "messages": []
        }
        extra_content
        """.trimIndent()
        try {
            AndroidJsonReaderParser.parseJson(json)
            fail("Should reject trailing content")
        } catch (e: Exception) {
            assertTrue(e is IOException || e is IllegalArgumentException)
        }
    }

    @Test
    fun testRejectsLeniencySingleQuotesAndUnquotedNames() {
        val singleQuoteJson = """
        {
          'datasetId': 'v1',
          'synthetic': true,
          'messages': []
        }
        """.trimIndent()
        try {
            AndroidJsonReaderParser.parseJson(singleQuoteJson)
            fail("Should reject single quotes in strict mode")
        } catch (e: Exception) {
            assertTrue(e is IOException || e is IllegalArgumentException)
        }

        val unquotedKeyJson = """
        {
          datasetId: "v1",
          synthetic: true,
          messages: []
        }
        """.trimIndent()
        try {
            AndroidJsonReaderParser.parseJson(unquotedKeyJson)
            fail("Should reject unquoted keys in strict mode")
        } catch (e: Exception) {
            assertTrue(e is IOException || e is IllegalArgumentException)
        }
    }

    @Test
    fun testPromptMetricsOnParsedAsset() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val dataset = SyntheticNewsAssetLoader.loadAndValidateAsset(targetContext, "test_news.json")
        val prompt = NewsSummaryPromptBuilder.buildPrompt(dataset)

        val bytes = prompt.toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        val hex = md.digest(bytes).joinToString("") { "%02x".format(it) }

        assertEquals(1509, prompt.length)
        assertEquals(2595, bytes.size)
        assertEquals("f13ed5220426b4e0583e28524728057e4ec9e798250db283312f8d6358be8ca3", hex)
    }
}
