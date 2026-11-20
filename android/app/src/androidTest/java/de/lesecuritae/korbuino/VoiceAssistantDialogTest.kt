package de.lesecuritae.korbuino

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import de.lesecuritae.korbuino.data.OfferEntity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class VoiceAssistantDialogTest {
    @get:Rule val compose = createComposeRule()

    private val offers = listOf(OfferDisplay(
        OfferEntity(id = "1", retailerId = "rewe", productId = "p1", externalId = "1", priceCents = 249,
            sourceUrl = "https://example.invalid/1", cachedAt = 0L),
        productName = "Hakle Toilettenpapier",
    ))

    @Test fun showsPickedOfferAndRunsConfirmedPlan() {
        var confirmed: VoiceAssistant.Plan? = null
        compose.setContent {
            MaterialTheme {
                VoiceAssistantDialog("Füge zwei Klopapier und Milch hinzu", offers, emptyList(), emptySet(),
                    mapOf("rewe" to "REWE"), "2026-11-20", onListenAgain = {}, onDismiss = {}, onConfirm = { confirmed = it })
            }
        }
        compose.onAllNodesWithTag("voice-line").assertCountEquals(2)
        compose.onNodeWithText("Hakle Toilettenpapier · REWE · 2,49 €").assertIsDisplayed()
        compose.onNodeWithText("Kein Angebot – kommt als Notiz auf die Liste").assertIsDisplayed()
        compose.onNodeWithTag("voice-confirm").performClick()
        compose.runOnIdle {
            val add = confirmed as VoiceAssistant.Plan.Add
            assertEquals(listOf("klopapier", "milch"), add.lines.map { it.item.name })
            assertEquals(2, add.lines.first().item.quantity)
        }
    }

    @Test fun editedTextIsInterpretedAgainAndUnknownCannotRun() {
        compose.setContent {
            MaterialTheme {
                VoiceAssistantDialog("bitte", offers, emptyList(), emptySet(), emptyMap(), "2026-11-20",
                    onListenAgain = {}, onDismiss = {}, onConfirm = {})
            }
        }
        compose.onNodeWithTag("voice-confirm").assertIsNotEnabled()
        compose.onNodeWithTag("voice-text").performTextReplacement("Zeig mir die Route")
        compose.onNodeWithTag("voice-summary").assert(hasText("zuerst", substring = true))
        compose.onNodeWithTag("voice-text").performTextReplacement("Wo gibt es Klopapier")
        compose.onNodeWithTag("voice-confirm").assertIsEnabled()
    }

    @Test fun listeningShowsLiveTextAndFertigStopsIt() {
        var stopped = false
        var continuedWith: String? = null
        compose.setContent {
            MaterialTheme {
                VoiceAssistantDialog("Klopapier", offers, emptyList(), emptySet(), emptyMap(), "2026-11-20",
                    onListenAgain = { continuedWith = it }, onDismiss = {}, onConfirm = {},
                    listening = true, partial = "zwei Flaschen", onStopListening = { stopped = true })
            }
        }
        compose.onNodeWithTag("voice-partial").assert(hasText("zwei Flaschen", substring = true))
        compose.onAllNodesWithTag("voice-confirm").assertCountEquals(0)
        compose.onNodeWithTag("voice-stop").performClick()
        compose.runOnIdle { assertEquals(true, stopped); assertEquals(null, continuedWith) }
    }

    @Test fun generalWordOffersAChoiceAndThePickIsUsed() {
        fun water(id: String, name: String, cents: Int, retailer: String) = OfferDisplay(
            OfferEntity(id = id, retailerId = retailer, productId = "p$id", externalId = id, priceCents = cents,
                sourceUrl = "https://example.invalid/$id", cachedAt = 0L),
            productName = name,
        )
        val waters = listOf(water("a", "Saskia Mineralwasser", 19, "marktguru-lidl"), water("b", "Vöslauer Mineralwasser", 79, "rewe"),
            water("c", "Curafit Schnupfenspray Meerwasser", 179, "dm"))
        var confirmed: VoiceAssistant.Plan? = null
        compose.setContent {
            MaterialTheme {
                VoiceAssistantDialog("Ich brauche Wasser", waters, emptyList(), emptySet(), mapOf("rewe" to "REWE"), "2026-11-20",
                    onListenAgain = {}, onDismiss = {}, onConfirm = { confirmed = it })
            }
        }
        compose.onAllNodesWithTag("voice-option").assertCountEquals(2)
        compose.onAllNodesWithText("Meerwasser", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Vöslauer Mineralwasser", substring = true).performClick()
        compose.onNodeWithTag("voice-confirm").performClick()
        compose.runOnIdle {
            assertEquals("b", (confirmed as VoiceAssistant.Plan.Add).lines.single().offer?.offer?.id)
        }
    }

    @Test fun sortChipsNarrowTheChoiceAndMemoryIsShown() {
        fun water(id: String, name: String, cents: Int) = OfferDisplay(
            OfferEntity(id = id, retailerId = "rewe", productId = "p$id", externalId = id, priceCents = cents,
                sourceUrl = "https://example.invalid/$id", cachedAt = 0L),
            productName = name,
        )
        val waters = listOf(water("a", "Saskia Mineralwasser still", 19), water("b", "Gerolsteiner Mineralwasser Medium", 69))
        var confirmed: VoiceAssistant.Plan? = null
        var forgotten: String? = null
        compose.setContent {
            MaterialTheme {
                VoiceAssistantDialog("Ich brauche Wasser", waters, emptyList(), emptySet(), emptyMap(), "2026-11-20",
                    onListenAgain = {}, onDismiss = {}, onConfirm = { confirmed = it },
                    memory = mapOf("wasser" to VoiceMemory.Entry("still", null)), onForget = { forgotten = it })
            }
        }
        compose.onNodeWithTag("voice-variant-still").assert(hasText("gemerkt", substring = true))
        compose.onNodeWithTag("voice-variant-medium").performClick()
        compose.onNodeWithTag("voice-confirm").performClick()
        compose.runOnIdle {
            assertEquals("b", (confirmed as VoiceAssistant.Plan.Add).lines.single().offer?.offer?.id)
        }
    }
}

