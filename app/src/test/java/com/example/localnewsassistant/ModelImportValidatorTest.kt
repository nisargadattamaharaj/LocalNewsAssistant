package com.example.localnewsassistant

import com.example.localnewsassistant.model.ModelImportValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelImportValidatorTest {

    @Test
    fun testValidExtension() {
        assertTrue(ModelImportValidator.isValidExtension("gemma.litertlm"))
        assertTrue(ModelImportValidator.isValidExtension("MODEL.LITERTLM"))
        assertTrue(ModelImportValidator.isValidExtension("my_model.LiteRtLm"))
    }

    @Test
    fun testInvalidExtension() {
        assertFalse(ModelImportValidator.isValidExtension("model.bin"))
        assertFalse(ModelImportValidator.isValidExtension("model.litertlm.zip"))
        assertFalse(ModelImportValidator.isValidExtension("model"))
        assertFalse(ModelImportValidator.isValidExtension(null))
        assertFalse(ModelImportValidator.isValidExtension(""))
    }

    @Test
    fun testFormatFileSize() {
        assertEquals("0 Б", ModelImportValidator.formatFileSize(0))
        assertEquals("500 Б", ModelImportValidator.formatFileSize(500))
        
        val kbStr = ModelImportValidator.formatFileSize(1024)
        assertTrue(kbStr.endsWith("КБ") && (kbStr.startsWith("1.0") || kbStr.startsWith("1,0")))

        val mbStr = ModelImportValidator.formatFileSize(1024 * 1024)
        assertTrue(mbStr.endsWith("МБ") && (mbStr.startsWith("1.0") || mbStr.startsWith("1,0")))
    }

    @Test
    fun testInternalFileNameExtension() {
        val fileName = com.example.localnewsassistant.model.ModelStorageManager.generateInternalFileName("12345")
        assertEquals("model_12345.litertlm", fileName)
        assertTrue(fileName.endsWith(".litertlm"))
        assertTrue(fileName.startsWith("model_"))
    }
}
