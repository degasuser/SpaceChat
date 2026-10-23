package dev.spacechat

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun opensSettingsAndCreatesChatWithoutCredentials() {
        compose.waitUntil(15000) { compose.onAllNodesWithText("SpaceChat").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNodeWithText("Base URL").assertIsDisplayed()
        compose.onNodeWithText("API Key").assertIsDisplayed()
        compose.onNodeWithContentDescription("К диалогам").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Новый чат", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Новый чат", useUnmergedTree = true).performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Сообщение…").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Сообщение…").performTextInput("Test draft")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Test draft").assertIsDisplayed()
        compose.onNodeWithContentDescription("Отправить").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Укажите Base URL и API-ключ в настройках.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Test draft").assertIsDisplayed()
    }
}
