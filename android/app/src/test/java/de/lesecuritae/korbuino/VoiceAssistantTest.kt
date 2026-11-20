package de.lesecuritae.korbuino

import de.lesecuritae.korbuino.VoiceAssistant.Plan
import de.lesecuritae.korbuino.data.OfferEntity
import de.lesecuritae.korbuino.data.ShoppingListRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAssistantTest {
    private fun offer(id: String, retailer: String, name: String, cents: Int, brand: String = "", until: String? = null) = OfferDisplay(
        OfferEntity(id = id, retailerId = retailer, productId = "p-$id", externalId = id, priceCents = cents,
            sourceUrl = "https://example.invalid/$id", cachedAt = 0L, validUntil = until),
        productName = name, brand = brand,
    )

    private val offers = listOf(
        offer("1", "rewe", "Hakle Toilettenpapier 3-lagig", 399, "Hakle"),
        offer("2", "marktguru-lidl", "Floralys Toilettenpapier", 249),
        offer("3", "edeka", "Müller Joghurt Erdbeere", 59, "Müller"),
        offer("4", "rewe", "Müller Joghurt Erdbeere", 49, "Müller"),
        offer("5", "kaufland", "Gurken-Dip mit Joghurt", 99),
        offer("6", "aldi-sued", "Kaffee Crema 1 kg", 1199),
        offer("7", "rewe", "Alte Butter", 99, until = "2020-01-01"),
    )
    private val today = "2026-11-20"

    private fun plan(text: String, items: List<ShoppingListRow> = emptyList()) =
        VoiceAssistant.plan(ShoppingCommand.parse(text), offers, items, emptySet(), today)

    @Test fun `cheapest fitting offer is picked per product`() {
        val add = plan("Füge Klopapier und zwei Müller Joghurt Erdbeere hinzu") as Plan.Add
        assertEquals("2", add.lines[0].offer?.offer?.id)
        assertEquals("4", add.lines[1].offer?.offer?.id)
        assertEquals(2, add.lines[1].item.quantity)
        assertEquals(249 + 2 * 49, add.totalCents)
    }

    @Test fun `a side mention is never picked for the real product`() {
        val add = plan("Ich brauche Joghurt") as Plan.Add
        assertTrue(add.lines.single().offer?.productName?.startsWith("Müller") == true)
    }

    @Test fun `price limit leaves only a note when every offer is dearer`() {
        val add = plan("Setz Kaffee auf die Liste, maximal fünf Euro") as Plan.Add
        val line = add.lines.single()
        assertNull(line.offer)
        assertTrue(line.overLimitOnly)
        assertEquals(500, line.item.maxPriceCents)
    }

    @Test fun `unknown products and expired offers become notes`() {
        val add = plan("Ich brauche Butter und Gurken") as Plan.Add
        assertNull(add.lines[0].offer) // the only butter offer has expired
        assertFalse(add.lines[0].overLimitOnly)
        assertNull(add.lines[1].offer)
    }

    @Test fun `budget is checked against the picked offers`() {
        val over = plan("Ich brauche Kaffee und Klopapier, insgesamt höchstens 10 Euro") as Plan.Add
        assertTrue(over.overBudget)
        val fine = plan("Ich brauche Klopapier, insgesamt höchstens 10 Euro") as Plan.Add
        assertFalse(fine.overBudget)
        assertTrue(VoiceAssistant.describe(fine, emptyMap()).contains("reicht"))
    }

    @Test fun `remove matches list entries by meaning`() {
        val rows = listOf(
            ShoppingListRow("a", "Toilettenpapier", 1, false),
            ShoppingListRow("b", "Milch", 1, false),
            ShoppingListRow("c", "Brot", 1, true),
        )
        val remove = plan("Lösch Klopapier und Brot von der Liste", rows) as Plan.Remove
        assertEquals(listOf("a"), remove.rows.map { it.productId })
        assertEquals(listOf("brot"), remove.notFound)
    }

    @Test fun `route uses the retailers of the chosen offers`() {
        val rows = listOf(
            ShoppingListRow("a", "Joghurt", 1, false, retailerId = "rewe"),
            ShoppingListRow("b", "Klopapier", 1, false, retailerId = "marktguru-lidl"),
            ShoppingListRow("c", "Milch", 1, false, retailerId = "rewe"),
            ShoppingListRow("d", "Brot", 1, true, retailerId = "edeka"),
        )
        assertEquals(Plan.Route(listOf("rewe", "marktguru-lidl")), plan("Wo muss ich hin?", rows))
        assertTrue(VoiceAssistant.describe(Plan.Route(emptyList()), emptyMap()).contains("zuerst"))
    }

    @Test fun `search and unknown pass through`() {
        assertEquals(Plan.Search("kaffee", 600), plan("Zeig mir Kaffee unter 6 Euro"))
        assertTrue(plan("bitte") is Plan.Unknown)
    }
}
