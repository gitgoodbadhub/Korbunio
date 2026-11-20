package de.lesecuritae.korbuino

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NanoDialogTest {
    @get:Rule val compose = createComposeRule()

    private class FakeNano(private val answer: String?) : OnDeviceAi {
        var prompts = 0
        override suspend fun status() = OnDeviceAi.Status.AVAILABLE
        override suspend fun download() = true
        override suspend fun generate(prompt: String): String? { prompts++; return answer }
    }

    @Test fun unreadableSentenceGoesToNanoAndItsReadingIsUsed() {
        val nano = FakeNano("""{"aktion":"hinzufuegen","produkte":[{"name":"Mehl"},{"name":"Eier","menge":6}]}""")
        var confirmed: VoiceAssistant.Plan? = null
        compose.setContent {
            MaterialTheme {
                VoiceAssistantDialog("Pfannkuchen für vier", emptyList(), emptyList(), emptySet(), emptyMap(), "2026-11-20",
                    onListenAgain = {}, onDismiss = {}, onConfirm = { confirmed = it }, ai = nano)
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("voice-line").fetchSemanticsNodes().size == 2 }
        compose.onNodeWithText("Von Gemini Nano", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("voice-confirm").performClick()
        compose.runOnIdle {
            assertEquals(listOf("mehl", "eier"), (confirmed as VoiceAssistant.Plan.Add).lines.map { it.item.name })
            assertEquals(1, nano.prompts)
        }
    }

    @Test fun clearSentenceStaysRuleBasedUntilNanoIsAsked() {
        val nano = FakeNano(null)
        compose.setContent {
            MaterialTheme {
                VoiceAssistantDialog("Milch und Brot", emptyList(), emptyList(), emptySet(), emptyMap(), "2026-11-20",
                    onListenAgain = {}, onDismiss = {}, onConfirm = {}, ai = nano)
            }
        }
        compose.onAllNodesWithTag("voice-line").assertCountEquals(2)
        compose.onNodeWithTag("voice-nano").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("keine verwertbare Antwort", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag("voice-line").assertCountEquals(2)
        compose.runOnIdle { assertEquals(1, nano.prompts) }
    }
}
