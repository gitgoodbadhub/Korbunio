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
    private fun offer(id: String, retailer: String, name: String, cents: Int, brand: String = "", until: String? = null, from: String? = null) = OfferDisplay(
        OfferEntity(id = id, retailerId = retailer, productId = "p-$id", externalId = id, priceCents = cents,
            sourceUrl = "https://example.invalid/$id", cachedAt = 0L, validUntil = until, validFrom = from),
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

    private val waters = listOf(
        offer("s1", "marktguru-lidl", "Saskia Mineralwasser still", 19),
        offer("s2", "rewe", "Gerolsteiner Mineralwasser Medium", 69),
        offer("s3", "edeka", "Vöslauer Mineralwasser", 79),
        offer("s4", "kaufland", "Tafelwasser spritzig", 25),
    )

    private fun waterPlan(text: String, memory: Map<String, VoiceMemory.Entry> = emptyMap(), picks: Map<Int, String?> = emptyMap()) =
        VoiceAssistant.plan(ShoppingCommand.parse(text), waters, emptyList(), emptySet(), today, memory, picks) as Plan.Add

    @Test fun `a spoken sort narrows the choice`() {
        val line = waterPlan("Ich brauche stilles Wasser").lines.single()
        assertEquals("still", line.variant?.id)
        assertEquals(VoiceAssistant.VariantSource.SPOKEN, line.variant?.let { line.variantSource })
        assertEquals(listOf("s1"), line.options.map { it.offer.offer.id })
        assertEquals("s1", line.offer?.offer?.id)
    }

    @Test fun `an answer after a pause belongs to the product before`() {
        val plan = waterPlan("Ich brauche Wasser, feinperlig, und Milch")
        assertEquals(2, plan.lines.size)
        assertEquals("medium", plan.lines[0].variant?.id)
        assertEquals("s2", plan.lines[0].offer?.offer?.id)
        assertEquals("classic", waterPlan("Füge Sprudel hinzu").lines.single().variant?.id)
    }

    @Test fun `without a sort the sorts are offered as a question`() {
        val line = waterPlan("Ich brauche Wasser").lines.single()
        assertNull(line.variant)
        assertEquals(listOf("still", "medium", "classic"), line.variants.map { it.id })
        assertEquals("s1", line.offer?.offer?.id)
    }

    @Test fun `remembered sort and product are used next time and a pick overrides them`() {
        val memory = mapOf("wasser" to VoiceMemory.Entry("medium", "Gerolsteiner Mineralwasser Medium"))
        val line = waterPlan("Ich brauche Wasser", memory).lines.single()
        assertEquals(VoiceAssistant.VariantSource.REMEMBERED, line.variantSource)
        assertEquals("s2", line.offer?.offer?.id)
        assertTrue(line.rememberedProduct)
        val picked = waterPlan("Ich brauche Wasser", memory, mapOf(0 to "still")).lines.single()
        assertEquals("s1", picked.offer?.offer?.id)
        assertEquals(VoiceAssistant.VariantSource.PICKED, picked.variantSource)
    }

    @Test fun `a missing sort shows the others`() {
        val line = VoiceAssistant.plan(ShoppingCommand.parse("Ich brauche Wasser classic"), waters.take(3), emptyList(), emptySet(), today) as Plan.Add
        assertTrue(line.lines.single().variantMissing)
        assertEquals(3, line.lines.single().options.size)
    }

    @Test fun `confirmed choices are learned, also the sort of a picked product`() {
        val plan = waterPlan("Ich brauche Wasser und Müller Joghurt Erdbeere")
        val chosen = VoiceAssistant.choose(plan, mapOf(0 to waters[1]), emptySet())
        val learned = VoiceAssistant.learn(chosen)
        assertEquals(mapOf("wasser" to VoiceMemory.Entry("medium", "Gerolsteiner Mineralwasser Medium")), learned)
        val stored = VoiceMemory.decode(VoiceMemory.encode(VoiceMemory.merge(mapOf("milch" to VoiceMemory.Entry("voll", null)), learned)))
        assertEquals(setOf("milch", "wasser"), stored.keys)
        assertEquals(emptyMap<String, VoiceMemory.Entry>(), VoiceMemory.decode("kaputt"))
    }

    @Test fun `sort words are recognised in many ways`() {
        assertEquals("wasser", ProductVariants.key("Mineralwasser"))
        assertEquals("still", ProductVariants.detect("wasser", "Wasser ohne Kohlensäure")?.id)
        assertEquals("classic", ProductVariants.detect("wasser", "Wasser mit Kohlensäure")?.id)
        assertEquals("fettarm", ProductVariants.detect("milch", "Frische Milch 1,5%")?.id)
        assertEquals("haltbar", ProductVariants.detect("milch", "H Milch")?.id)
        assertTrue(ProductVariants.isOnlyVariant("wasser", "feinperlig"))
        assertFalse(ProductVariants.isOnlyVariant("wasser", "Butter"))
        assertEquals("wasser", ProductVariants.strip("wasser", "stilles Wasser"))
    }

    @Test fun `words written apart find the compound and the cheaper leaflet offer wins`() {
        val meat = listOf(
            offer("t1", "edeka", "Gut & Günstig Puten-Schnitzel", 509),
            offer("t2", "aldi-sued", "Putenschnitzel", 499, from = "2026-11-23", until = "2026-11-28"),
            offer("t3", "rewe", "Putenschnitzel natur", 699, from = "2026-12-10"),
            offer("t4", "rewe", "Schweineschnitzel", 399),
        )
        for (said in listOf("Puten Schnitzel", "Putenschnitzel")) {
            val line = (VoiceAssistant.plan(ShoppingCommand.parse("Ich brauche $said"), meat, emptyList(), emptySet(), today) as Plan.Add).lines.single()
            assertEquals(said, "t2", line.offer?.offer?.id)
            assertEquals(said, listOf("t2", "t1"), line.options.map { it.offer.offer.id })
        }
        assertEquals("ab 23.11.", VoiceAssistant.startsLater(meat[1], today))
        assertNull(VoiceAssistant.startsLater(meat[0], today))
    }
}

