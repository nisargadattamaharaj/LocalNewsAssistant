package com.example.localnewsassistant

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.localnewsassistant.data.local.StoredNewsEntity
import com.example.localnewsassistant.ui.LocalSearchCard
import com.example.localnewsassistant.ui.NewsSearchUiState
import com.example.localnewsassistant.ui.theme.LocalNewsAssistantTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewsSearchUiInstrumentedTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testLocalSearchCard_SuccessStateDisplaysOriginalEntitiesAndMetadata() {
        val entity1 = StoredNewsEntity(
            datasetId = "dataset_alpha",
            messageId = "msg_001",
            source = "Source_Telegram_1",
            publishedAt = "2026-10-22T10:00:00Z",
            text = "ЁЛКА свежая лесная",
            normalizedText = "NORMALIZED_MARKER_1",
            synthetic = true
        )

        val entity2 = StoredNewsEntity(
            datasetId = "dataset_beta",
            messageId = "msg_002",
            source = "Source_Telegram_2",
            publishedAt = "2026-10-22T11:00:00Z",
            text = "елка искусственная 100%",
            normalizedText = "NORMALIZED_MARKER_2",
            synthetic = true
        )

        val successState = NewsSearchUiState.Success(
            query = "елка",
            results = listOf(entity1, entity2)
        )

        composeTestRule.setContent {
            LocalNewsAssistantTheme {
                LocalSearchCard(
                    state = successState,
                    onSearchClick = {}
                )
            }
        }

        // 1. Assert Title "Показано записей: 2" is displayed
        composeTestRule.onNodeWithText("Показано записей: 2").assertIsDisplayed()

        // 2. Assert Original Text 1 and Original Text 2 are displayed intact
        composeTestRule.onNodeWithText("ЁЛКА свежая лесная").assertIsDisplayed()
        composeTestRule.onNodeWithText("елка искусственная 100%").assertIsDisplayed()

        // 3. Assert Sources, Dates, and Composite Keys are displayed
        composeTestRule.onNodeWithText("[dataset_alpha / msg_001] Source_Telegram_1").assertIsDisplayed()
        composeTestRule.onNodeWithText("2026-10-22T10:00:00Z").assertIsDisplayed()

        composeTestRule.onNodeWithText("[dataset_beta / msg_002] Source_Telegram_2").assertIsDisplayed()
        composeTestRule.onNodeWithText("2026-10-22T11:00:00Z").assertIsDisplayed()

        // 4. Assert normalizedText markers are NOT displayed on UI
        composeTestRule.onNodeWithText("NORMALIZED_MARKER_1").assertDoesNotExist()
        composeTestRule.onNodeWithText("NORMALIZED_MARKER_2").assertDoesNotExist()
    }
}
