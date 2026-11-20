package de.lesecuritae.korbuino

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ShoppingPlanCardTest {
    @get:Rule val compose = createComposeRule()
    private val names = mapOf("edeka" to "EDEKA", "rewe" to "REWE", "kik" to "KiK")

    private fun plan(actual: Int, cheapest: Int) = ShoppingPlan.Plan(
        matchedCount = 3, unmatchedItems = emptyList(), cheapestTotalCents = cheapest, cheapestStores = listOf("edeka", "rewe", "kik"),
        primaryStoreId = "rewe", consolidations = emptyList(), consolidatedStores = listOf("edeka", "rewe", "kik"), consolidationExtraCents = 0,
        positions = listOf(ShoppingPlan.Position("1", "p", "Milch", 1, "rewe", null, null, null, null, 99, 99, null, null, null, null, null)),
        actualTotalCents = actual, actualStores = listOf("edeka", "rewe", "kik"),
    )

    @Test fun badgesTotalAndCollapsedDetails() {
        var routed = false
        compose.setContent { MaterialTheme { ShoppingPlanCard(plan(4334, 4334), names, routeLoading = false, onRoute = { routed = true }) } }
        compose.onNodeWithTag("shopping-total").assert(hasText("43,34", substring = true))
        listOf("edeka", "rewe", "kik").forEach { compose.onNodeWithTag("retailer-badge-$it").assertIsDisplayed() }
        compose.onNodeWithTag("shopping-verdict").assert(hasText("günstigste Auswahl", substring = true))
        compose.onAllNodesWithTag("shopping-plan-details").assertCountEquals(0)
        compose.onNodeWithTag("shopping-plan-toggle").performClick()
        compose.onNodeWithTag("shopping-plan-details").assertIsDisplayed()
        compose.onNodeWithTag("shopping-route").performClick()
        compose.runOnIdle { assertTrue(routed) }
    }

    @Test fun cheaperSelectionIsPointedOut() {
        compose.setContent { MaterialTheme { ShoppingPlanCard(plan(4334, 4120), names, routeLoading = false, onRoute = {}) } }
        compose.onNodeWithTag("shopping-verdict").assert(hasText("41,20", substring = true))
        compose.onNodeWithTag("shopping-verdict").assert(hasText("2,14", substring = true))
    }
}
