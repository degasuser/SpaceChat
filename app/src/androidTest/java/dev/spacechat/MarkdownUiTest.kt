package dev.spacechat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Signal").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Signal").assertIsDisplayed()
        compose.onNodeWithText("Ready for launch.").assertIsDisplayed()
        compose.onNodeWithText("val answer = 42").assertIsDisplayed()
        compose.onNodeWithText("Копировать код").performClick()
    }
    @Test fun rendersTablesTasksLinksAndInlineAndScrollableBlockMath() {
        compose.setContent {
            SpaceTheme {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                    MarkdownText("""# Решение

**Масса** и ~~ошибка~~. [Документация](https://example.com)

- [x] Проверено
- [ ] Продолжить

| Вещество | Масса |
| --- | ---: |
| Железо | 56 |

Inline ${'$'}x_1^2${'$'}.

${'$'}${'$'}n(Fe_3O_4) = \frac{110000}{232} = 474{,}1 \text{ моль}${'$'}${'$'}
""", Modifier.fillMaxWidth())
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Вещество").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Вещество").assertExists()
        compose.onNodeWithText("Проверено").assertExists()
        compose.onNodeWithText("☑ ").assertExists()
        compose.waitUntil(30_000) { compose.onAllNodes(hasContentDescription("Формула:", substring = true), useUnmergedTree = true).fetchSemanticsNodes().size >= 2 }
        compose.onNodeWithText("Копировать LaTeX").performScrollTo().performClick()
        compose.onNodeWithText("Масса и ошибка. Документация").assertExists()
    }
}
