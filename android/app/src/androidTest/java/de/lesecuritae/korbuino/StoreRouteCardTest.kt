package de.lesecuritae.korbuino

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class StoreRouteCardTest {
    @get:Rule val compose = createComposeRule()
    private val names = mapOf("marktguru-lidl" to "Lidl", "kik" to "KiK", "woolworth" to "Woolworth")

    @Test fun stopsAreNumberedAndMissingRetailersAreNamed() {
        val route = StoreRouteUi(requested = true, stops = listOf(
            StoreStop("marktguru-lidl", "Lidl", "Hauptstraße 1, 56068 Koblenz", 50.36, 7.59, 0.4),
            StoreStop("woolworth", "Woolworth", "", 50.365, 7.595, 1.2),
        ), missingRetailerIds = listOf("kik"))
        var closed = false
        compose.setContent { MaterialTheme { StoreRouteCard(route, names, onClose = { closed = true }) } }
        compose.onNodeWithText("1. Lidl").assertIsDisplayed()
        compose.onNodeWithText("Hauptstraße 1, 56068 Koblenz · 0,4 km").assertIsDisplayed()
        compose.onNodeWithText("2. Woolworth").assertIsDisplayed()
        compose.onNodeWithTag("route-google-maps").assertIsDisplayed()
        compose.onNodeWithText("Ganze Route in Google Maps").assertIsDisplayed()
        // The map itself needs GPU rendering; only its entry point is checked here.
        compose.onNodeWithTag("route-show-map").assertIsDisplayed()
        compose.onNodeWithTag("route-missing").assert(hasText("KiK", substring = true))
        compose.onNodeWithTag("route-search-kik").assert(hasAnyDescendant(hasText("Google Maps")))
        compose.onAllNodesWithTag("route-search-marktguru-lidl").assertCountEquals(0)
        compose.onNodeWithTag("route-close").performClick()
        compose.runOnIdle { assertTrue(closed) }
    }

    @Test fun errorIsShownInsteadOfStops() {
        compose.setContent { MaterialTheme { StoreRouteCard(StoreRouteUi(requested = true, retailerIds = listOf("marktguru-lidl", "kik"),
            error = "PLZ-Suche (OpenStreetMap) HTTP 503"), names, onClose = {}, postalCode = "56068") } }
        compose.onNodeWithTag("route-error").assertIsDisplayed()
        // Without the OSM shop search every retailer of the plan can still be searched in a map app.
        compose.onNodeWithTag("route-search-marktguru-lidl").assert(hasAnyDescendant(hasText("Lidl")))
        compose.onNodeWithTag("route-search-kik").assert(hasAnyDescendant(hasText("Karten-App")))
        compose.onAllNodesWithTag("route-google-maps").assertCountEquals(0)
    }
}
