package de.lesecuritae.korbuino

import de.lesecuritae.korbuino.data.ShoppingListRow

/**
 * Turns a recognised [ShoppingCommand] into a concrete, confirmable plan (Korbunio Plus).
 *
 * Products are matched against the loaded offers with [OfferSearch]; among the best matches the
 * cheapest current offer within the spoken price limit wins. Nothing is changed here: the plan is
 * shown first and only carried out after the user confirmed it.
 */
object VoiceAssistant {
    /** One wanted product and the offer picked for it (null: no fitting offer, it becomes a free list entry). */
    data class Line(val item: ShoppingCommand.Item, val offer: OfferDisplay?, val unitCents: Int?, val overLimitOnly: Boolean = false)

    sealed interface Plan {
        data class Add(val lines: List<Line>, val budgetCents: Int?) : Plan {
            /** Sum of the picked offers; products without an offer are not counted. */
            val totalCents: Int get() = lines.sumOf { (it.unitCents ?: 0) * it.item.quantity }
            val overBudget: Boolean get() = budgetCents != null && totalCents > budgetCents
        }
        data class Search(val query: String, val maxPriceCents: Int?) : Plan
        data class Remove(val rows: List<ShoppingListRow>, val notFound: List<String>) : Plan
        data object ShowList : Plan
        data class Route(val retailerIds: List<String>) : Plan
        data class Unknown(val text: String) : Plan
    }

    /** Matches below this share of the best score are side mentions ("Gurken-Dip mit Joghurt"). */
    private const val TIER = 0.75
    /** An offer is only picked automatically when the product word itself (or the brand) matched, not a side word. */
    private const val MIN_PICK_SCORE = 8

    fun plan(
        command: ShoppingCommand.Command,
        offers: List<OfferDisplay>,
        shoppingItems: List<ShoppingListRow>,
        loyaltyPrograms: Set<String> = emptySet(),
        today: String? = null,
    ): Plan = when (command) {
        is ShoppingCommand.Command.Add -> {
            val current = if (today == null) offers
            else offers.filter { OfferMatcher.isShown(it.offer.validFrom, it.offer.validUntil, today, false) }
            val index = current.map { it to OfferSearch.index(it) }
            Plan.Add(command.items.map { line(it, index, loyaltyPrograms) }, command.budgetCents)
        }
        is ShoppingCommand.Command.Search -> Plan.Search(command.query, command.maxPriceCents)
        is ShoppingCommand.Command.Remove -> {
            val open = shoppingItems.filterNot { it.checked }
            val found = command.names.associateWith { name -> matchingRows(name, open) }
            Plan.Remove(found.values.flatten().distinctBy { it.itemId }, found.filterValues { it.isEmpty() }.keys.toList())
        }
        ShoppingCommand.Command.ShowList -> Plan.ShowList
        ShoppingCommand.Command.Route -> Plan.Route(
            shoppingItems.filterNot { it.checked }.mapNotNull { it.retailerId }.distinct(),
        )
        is ShoppingCommand.Command.Unknown -> Plan.Unknown(command.text)
    }

    fun unitCents(display: OfferDisplay, loyaltyPrograms: Set<String>): Int? =
        OfferPricing.quote(display.offer, 1, loyaltyPrograms).totalCents

    private fun line(item: ShoppingCommand.Item, index: List<Pair<OfferDisplay, OfferSearch.Indexed>>, loyalty: Set<String>): Line {
        val query = OfferSearch.prepare(item.name)
        val scored = index.map { (display, indexed) -> display to OfferSearch.score(query, indexed) }.filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second }?.takeIf { it >= MIN_PICK_SCORE } ?: return Line(item, null, null)
        val candidates = scored.filter { it.second >= best * TIER }
            .mapNotNull { (display, score) -> unitCents(display, loyalty)?.let { Triple(display, score, it) } }
        // Cheapest first; with equal prices the closer match wins.
        val ordered = candidates.sortedWith(compareBy({ it.third }, { -it.second }))
        val within = ordered.firstOrNull { item.maxPriceCents == null || it.third <= item.maxPriceCents }
        return when {
            within != null -> Line(item, within.first, within.third)
            ordered.isNotEmpty() -> Line(item, null, null, overLimitOnly = true)
            else -> Line(item, null, null)
        }
    }

    private fun matchingRows(name: String, rows: List<ShoppingListRow>): List<ShoppingListRow> {
        val query = OfferSearch.prepare(name)
        val scored = rows.map { it to OfferSearch.score(query, OfferSearch.index(it.name, "", "", null)) }.filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second >= best * TIER }.map { it.first }
    }

    /** Short German summary, also suitable to be read aloud. */
    fun describe(plan: Plan, retailerNames: Map<String, String>): String = when (plan) {
        is Plan.Add -> buildString {
            val withOffer = plan.lines.count { it.offer != null }
            append("${plan.lines.size} Artikel auf die Liste")
            if (withOffer > 0) append(", $withOffer mit Angebot für zusammen ${OfferMatcher.formatPrice(plan.totalCents)}")
            if (plan.budgetCents != null) append(
                if (plan.overBudget) ". Budget von ${OfferMatcher.formatPrice(plan.budgetCents)} überschritten"
                else ". Budget von ${OfferMatcher.formatPrice(plan.budgetCents)} reicht",
            )
            append('.')
        }
        is Plan.Search -> "Angebote zu „${plan.query}“ suchen" +
            (plan.maxPriceCents?.let { " bis ${OfferMatcher.formatPrice(it)}" } ?: "") + "."
        is Plan.Remove -> when {
            plan.rows.isEmpty() -> "Nichts davon steht auf der Einkaufsliste."
            else -> "${plan.rows.joinToString { "„${it.name}“" }} von der Liste entfernen." +
                (if (plan.notFound.isNotEmpty()) " Nicht gefunden: ${plan.notFound.joinToString()}." else "")
        }
        Plan.ShowList -> "Einkaufsliste öffnen."
        is Plan.Route -> if (plan.retailerIds.isEmpty()) "Für die Route zuerst Angebote in der Einkaufsliste wählen."
        else "Route über ${plan.retailerIds.joinToString { retailerNames[it] ?: it }} planen."
        is Plan.Unknown -> "Das habe ich nicht verstanden. Beispiel: „Füge Milch und zwei Butter hinzu“."
    }
}
