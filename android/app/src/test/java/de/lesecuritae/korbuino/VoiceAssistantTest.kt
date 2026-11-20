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

    private val drinks = listOf(
        offer("w1", "dm", "Curafit Schnupfenspray Meerwasser", 179),
        offer("w2", "rewe", "Vöslauer Mineralwasser", 79),
        offer("w3", "marktguru-lidl", "Saskia Mineralwasser still", 19),
        offer("w4", "kaufland", "Tafelwasser", 25),
        offer("m1", "rewe", "Müllermilch Schoko", 79),
        offer("m2", "edeka", "Frische Vollmilch 3,8%", 99),
        offer("m3", "aldi-sued", "H-Milch fettarm", 85),
    )

    private fun drinkPlan(text: String) = VoiceAssistant.plan(ShoppingCommand.parse(text), drinks, emptyList(), emptySet(), today) as Plan.Add

    @Test fun `general water offers real water to choose and never nasal spray`() {
        val line = drinkPlan("Ich brauche Wasser").lines.single()
        assertEquals("w3", line.offer?.offer?.id)
        assertEquals(listOf("w3", "w4", "w2"), line.options.map { it.offer.offer.id })
        assertTrue(line.needsChoice)
    }

    @Test fun `milk is milk, not a milk drink`() {
        val line = drinkPlan("Füge Milch hinzu").lines.single()
        assertEquals("m3", line.offer?.offer?.id)
        assertEquals(setOf("m2", "m3"), line.options.map { it.offer.offer.id }.toSet())
        // Asked by name, the milk drink is found.
        assertEquals("m1", drinkPlan("Füge Müllermilch hinzu").lines.single().offer?.offer?.id)
    }

    @Test fun `only loose matches are shown but not picked`() {
        val sprayOnly = VoiceAssistant.plan(ShoppingCommand.parse("Ich brauche Wasser"), drinks.take(1), emptyList(), emptySet(), today) as Plan.Add
        val line = sprayOnly.lines.single()
        assertNull(line.offer)
        assertTrue(line.uncertain)
        assertEquals(listOf("w1"), line.options.map { it.offer.offer.id })
    }

    @Test fun `exact wishes get the best match without a choice`() {
        val line = drinkPlan("Füge Vöslauer Mineralwasser hinzu").lines.single()
        assertEquals("w2", line.offer?.offer?.id)
        assertFalse(line.needsChoice)
    }

    @Test fun `picks from the dialog replace the proposal`() {
        val plan = drinkPlan("Ich brauche Wasser und Milch")
        val chosen = VoiceAssistant.choose(plan, mapOf(0 to drinks[1], 1 to null), emptySet())
        assertEquals("w2", chosen.lines[0].offer?.offer?.id)
        assertEquals(79, chosen.lines[0].unitCents)
        assertNull(chosen.lines[1].offer)
        assertEquals(79, chosen.totalCents)
    }

    @Test fun `kinds of a product are told apart from other things`() {
        assertTrue(OfferSearch.isKind("milch", "vollmilch"))
        assertTrue(OfferSearch.isKind("wasser", "mineralwasser"))
        assertTrue(OfferSearch.isKind("ei", "eier"))
        assertFalse(OfferSearch.isKind("milch", "mullermilch"))
        assertFalse(OfferSearch.isKind("wasser", "meerwasser"))
        assertFalse(OfferSearch.isKind("milch", "buttermilch"))
    }
}

