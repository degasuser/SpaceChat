package dev.spacechat

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.spacechat.ui.MarkdownText
import dev.spacechat.ui.SpaceTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun rendersMarkdownAndCodeOnSupportedAndroidVersions() {
        compose.setContent {
            SpaceTheme {
                MarkdownText("# Signal\n\n**Ready** for launch.\n\n```kotlin\nval answer = 42\n```\n\n- One\n- Two")
            }
        }
        compose.onNodeWithText("Signal").assertIsDisplayed()
        compose.onNodeWithText("Ready for launch.").assertIsDisplayed()
        compose.onNodeWithText("val answer = 42").assertIsDisplayed()
        compose.onNodeWithText("Копировать код").performClick()
    }
}
