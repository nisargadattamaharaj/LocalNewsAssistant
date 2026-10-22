package com.example.localnewsassistant.data.local

import java.text.Normalizer
import java.util.Locale

object SearchTextNormalizer {

    /**
     * Normalizes a text string for news search comparison.
     *
     * Rules:
     * 1. Russian and English text are case-insensitive (using Locale.ROOT).
     * 2. Russian 'ё' (U+0451 / U+0401) and combining diaeresis on 'е' (e + U+0308) are normalized to 'е' (U+0435).
     * 3. Original text formatting, whitespace, NUL characters, wildcards, quotes, and non-Cyrillic diacritics are preserved.
     * 4. No translation, transliteration, or morphology is applied.
     */
    fun normalize(text: String): String {
        if (text.isEmpty()) return ""

        // 1. Convert combining character sequences (e.g. е + U+0308) into canonical NFC form (ё)
        val nfcText = Normalizer.normalize(text, Normalizer.Form.NFC)

        // 2. Locale-independent lowercasing for Russian and English
        val lowerText = nfcText.lowercase(Locale.ROOT)

        // 3. Replace Russian 'ё' (U+0451) with 'е' (U+0435)
        val yoReplaced = lowerText.replace('ё', 'е')

        // 4. Return canonical NFC form of normalized string
        return Normalizer.normalize(yoReplaced, Normalizer.Form.NFC)
    }
}
