package com.example.localnewsassistant

import com.example.localnewsassistant.data.local.SearchTextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Locale

class SearchTextNormalizerTest {

    @Test
    fun testN1_russianCaseInsensitivity() {
        assertEquals("новости", SearchTextNormalizer.normalize("Новости"))
        assertEquals("новости", SearchTextNormalizer.normalize("НОВОСТИ"))
        assertEquals("новости", SearchTextNormalizer.normalize("новости"))
    }

    @Test
    fun testN2_englishCaseInsensitivity() {
        assertEquals("news", SearchTextNormalizer.normalize("News"))
        assertEquals("news", SearchTextNormalizer.normalize("NEWS"))
        assertEquals("news", SearchTextNormalizer.normalize("nEwS"))
    }

    @Test
    fun testN3_russianYoAndEEquivalence() {
        assertEquals("елка", SearchTextNormalizer.normalize("ЁЛКА"))
        assertEquals("елка", SearchTextNormalizer.normalize("ёлка"))
        assertEquals("елка", SearchTextNormalizer.normalize("ЕЛКА"))
        assertEquals("елка", SearchTextNormalizer.normalize("елка"))
    }

    @Test
    fun testN4_mixedRussianAndEnglishString() {
        val input = "Ёж News AND НОВОСТИ"
        val expected = "еж news and новости"
        assertEquals(expected, SearchTextNormalizer.normalize(input))
    }

    @Test
    fun testN5_combiningDiaeresisSequenceNormalizedToE() {
        // 'е' (U+0435) + Combining Diaeresis (U+0308) -> 'ё' -> 'е'
        val combiningUpper = "Е\u0308Ж"
        val combiningLower = "е\u0308ж"

        assertEquals("еж", SearchTextNormalizer.normalize(combiningUpper))
        assertEquals("еж", SearchTextNormalizer.normalize(combiningLower))
    }

    @Test
    fun testN6_preservesFormattingWhitespaceWildcardsQuotesAndNul() {
        val input = "  \t 100%_новости\\срочно'\" \u0000 "
        val expected = "  \t 100%_новости\\срочно'\" \u0000 "
        assertEquals(expected, SearchTextNormalizer.normalize(input))

        assertEquals("", SearchTextNormalizer.normalize(""))
    }

    @Test
    fun testN7_negativeDistinctionsPreserved() {
        // Latin 'e' (U+0065) vs Russian 'е' (U+0435)
        assertNotEquals(
            SearchTextNormalizer.normalize("e"),
            SearchTextNormalizer.normalize("е")
        )

        // Russian 'й' vs 'и'
        assertNotEquals(
            SearchTextNormalizer.normalize("край"),
            SearchTextNormalizer.normalize("краи")
        )

        // French diacritics 'café' vs 'cafe'
        assertNotEquals(
            SearchTextNormalizer.normalize("café"),
            SearchTextNormalizer.normalize("cafe")
        )

        // English 'news' vs Russian 'новости'
        assertNotEquals(
            SearchTextNormalizer.normalize("news"),
            SearchTextNormalizer.normalize("новости")
        )
    }

    @Test
    fun testN8_idempotencyAndImmutabilityOfOriginalString() {
        val originalInput = "ЁЛКА Свежие Новости 100%"
        val firstPass = SearchTextNormalizer.normalize(originalInput)
        val secondPass = SearchTextNormalizer.normalize(firstPass)

        assertEquals(firstPass, secondPass)
        assertEquals("Original input string must remain unchanged", "ЁЛКА Свежие Новости 100%", originalInput)
    }

    @Test
    fun testN9_localeRootIsolationDottedI() {
        val previousDefault = Locale.getDefault()
        val previousFormat = Locale.getDefault(Locale.Category.FORMAT)
        val previousDisplay = Locale.getDefault(Locale.Category.DISPLAY)
        try {
            val turkish = Locale.forLanguageTag("tr-TR")
            Locale.setDefault(turkish)
            Locale.setDefault(Locale.Category.FORMAT, turkish)
            Locale.setDefault(Locale.Category.DISPLAY, turkish)

            // With Locale.ROOT, 'I' MUST lower to standard English 'i'
            assertEquals("i", SearchTextNormalizer.normalize("I"))
            assertEquals("info", SearchTextNormalizer.normalize("INFO"))
        } finally {
            Locale.setDefault(previousDefault)
            Locale.setDefault(Locale.Category.FORMAT, previousFormat)
            Locale.setDefault(Locale.Category.DISPLAY, previousDisplay)
        }
    }
}
