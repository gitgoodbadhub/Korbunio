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
    data class Line(
        val item: ShoppingCommand.Item,
        val offer: OfferDisplay?,
        val unitCents: Int?,
        val overLimitOnly: Boolean = false,
        /** A small choice (cheapest first) for general words like "Wasser"; the user picks in the dialog. */
        val options: List<Option> = emptyList(),
        /** True when only loosely related offers exist ("Meerwasser" for "Wasser"): nothing is picked, the user decides. */
        val uncertain: Boolean = false,
        /** Known sorts of this product ("still", "medium", "classic") to ask for, and the one in use. */
        val variants: List<ProductVariants.Variant> = emptyList(),
        val variant: ProductVariants.Variant? = null,
        val variantSource: VariantSource = VariantSource.NONE,
        /** True when no offer of the wanted sort exists; the other sorts are shown instead. */
        val variantMissing: Boolean = false,
        /** Key under which the choice is remembered ("wasser"); null for exact wishes. */
        val memoryKey: String? = null,
        val rememberedProduct: Boolean = false,
    ) {
        val needsChoice: Boolean get() = options.size > 1
    }

    data class Option(val offer: OfferDisplay, val unitCents: Int)

    enum class VariantSource { NONE, SPOKEN, REMEMBERED, PICKED }

    /** At most this many offers are offered per product. */
    const val MAX_OPTIONS = 4

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
        memory: Map<String, VoiceMemory.Entry> = emptyMap(),
        /** Sorts picked in the dialog per line; a null value means "any sort". */
        variantPicks: Map<Int, String?> = emptyMap(),
    ): Plan = when (command) {
        is ShoppingCommand.Command.Add -> {
            val current = if (today == null) offers else offers.filter { isCurrentOrSoon(it, today) }
            val index = current.map { it to OfferSearch.index(it) }
            val items = withVariantAnswers(command.items)
            Plan.Add(items.mapIndexed { i, item ->
                line(item, index, loyaltyPrograms, memory, if (i in variantPicks) Pick(variantPicks[i]) else null)
            }, command.budgetCents)
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

    private data class Pick(val variantId: String?)

    /** Days ahead from which offers count: paper leaflets often show next week's offers already. */
    const val DAYS_AHEAD = 7L

    fun isCurrentOrSoon(display: OfferDisplay, today: String): Boolean {
        val offer = display.offer
        if (OfferMatcher.isShown(offer.validFrom, offer.validUntil, today, false)) return true
        if (!OfferMatcher.isShown(offer.validFrom, offer.validUntil, today, true)) return false
        val from = BerlinDate.toLocalDate(offer.validFrom) ?: return false
        val limit = runCatching { java.time.LocalDate.parse(today).plusDays(DAYS_AHEAD).toString() }.getOrNull() ?: return false
        return from <= limit
    }

    /** "ab 23.11." for an offer that starts after [today], else null. */
    fun startsLater(display: OfferDisplay, today: String): String? {
        if (today.isBlank()) return null
        val from = BerlinDate.toLocalDate(display.offer.validFrom) ?: return null
        if (from <= today) return null
        return "ab ${from.substring(8, 10)}.${from.substring(5, 7)}."
    }

    /** "Wasser, feinperlig": a piece that only names a sort belongs to the product before it. */
    fun withVariantAnswers(items: List<ShoppingCommand.Item>): List<ShoppingCommand.Item> {
        val result = mutableListOf<ShoppingCommand.Item>()
        for (item in items) {
            val previous = result.lastOrNull()
            if (previous != null && ProductVariants.isOnlyVariant(ProductVariants.key(previous.name), item.name)) {
                result[result.lastIndex] = previous.copy(
                    name = previous.name + " " + item.name,
                    maxPriceCents = previous.maxPriceCents ?: item.maxPriceCents,
                )
            } else result += item
        }
        return result
    }

    private fun line(
        item: ShoppingCommand.Item,
        index: List<Pair<OfferDisplay, OfferSearch.Indexed>>,
        loyalty: Set<String>,
        memory: Map<String, VoiceMemory.Entry> = emptyMap(),
        pick: Pick? = null,
    ): Line {
        val key = ProductVariants.key(item.name)
        val spoken = ProductVariants.detect(key, item.name)
        val baseName = ProductVariants.strip(key, item.name).ifBlank { key ?: item.name }
        val queries = OfferSearch.prepareAll(baseName)
        val general = queries.any { it.terms.size == 1 && it.quantities.isEmpty() }
        val memoryKey = if (general) key ?: OfferSearch.normalize(baseName) else null
        val remembered = memoryKey?.let { memory[it] }
        val (variant, source) = when {
            pick != null -> ProductVariants.byId(key, pick.variantId) to VariantSource.PICKED
            spoken != null -> spoken to VariantSource.SPOKEN
            remembered?.variant != null && ProductVariants.byId(key, remembered.variant) != null ->
                ProductVariants.byId(key, remembered.variant) to VariantSource.REMEMBERED
            else -> null to VariantSource.NONE
        }
        val base = match(item, queries, index, loyalty)
            .copy(variants = ProductVariants.variants(key), variant = variant, variantSource = source, memoryKey = memoryKey)
        val all = base.options
        if (all.isEmpty()) return base
        if (base.uncertain) return base.copy(options = all.take(MAX_OPTIONS))
        // Only the wanted sort; if there is none on offer, all sorts stay visible.
        val ofSort = if (variant == null) all else all.filter { ProductVariants.matches(variant, offerText(it.offer)) }
        val shown = ofSort.ifEmpty { all }
        val favourite = if (source == VariantSource.PICKED) null
        else remembered?.product?.let { name -> shown.firstOrNull { it.offer.productName.equals(name, ignoreCase = true) } }
        // General words: the remembered product or the cheapest of the sort, with a small choice.
        // Exact wishes keep their best match unless a spoken sort says otherwise.
        val chosen = when {
            general -> favourite ?: shown.first()
            variant != null && ofSort.isNotEmpty() && ofSort.none { it.offer.offer.id == base.offer?.offer?.id } -> ofSort.first()
            else -> null
        }
        return base.copy(
            offer = chosen?.offer ?: base.offer,
            unitCents = chosen?.unitCents ?: base.unitCents,
            options = if (general) shown.take(MAX_OPTIONS) else emptyList(),
            variantMissing = variant != null && ofSort.isEmpty(),
            rememberedProduct = favourite != null,
        )
    }

    private fun offerText(display: OfferDisplay) =
        "${display.productName} ${display.brand} ${display.offer.description} ${display.offer.packSize.orEmpty()}"

    /** What [VoiceMemory] keeps from a confirmed plan: per general product word the sort and the product taken. */
    fun learn(plan: Plan.Add): Map<String, VoiceMemory.Entry> = plan.lines.mapNotNull { line ->
        val key = line.memoryKey ?: return@mapNotNull null
        val offer = line.offer ?: return@mapNotNull null
        val sort = line.variant ?: ProductVariants.detect(ProductVariants.key(key), offerText(offer))
        key to VoiceMemory.Entry(sort?.id, offer.productName)
    }.toMap()

    private fun match(item: ShoppingCommand.Item, queries: List<OfferSearch.Query>, index: List<Pair<OfferDisplay, OfferSearch.Indexed>>, loyalty: Set<String>): Line {
        val scored = index.mapNotNull { (display, indexed) ->
            OfferSearch.score(queries, indexed).takeIf { it > 0 }?.let { Triple(display, indexed, it) }
        }
        if (scored.isEmpty()) return Line(item, null, null)
        // The product itself ("Vollmilch", "Mineralwasser") before things that only contain the word
        // ("Müllermilch", "Schnupfenspray Meerwasser").
        val same = scored.filter { candidate -> queries.any { OfferSearch.isSameProduct(it, candidate.second) } }
        val uncertain = same.isEmpty()
        val pool = (if (uncertain) scored.filter { it.third >= MIN_PICK_SCORE } else same)
        if (pool.isEmpty()) return Line(item, null, null)
        // Stay in one product group: water from the drinks, not from the chemist's shelf.
        val groups = pool.groupingBy { ProductGroups.of(it.first.offer.categoryId, it.first.productName) }.eachCount()
        val top = groups.values.maxOrNull() ?: 0
        val mainGroups = groups.filterValues { it == top }.keys
        val priced = pool.filter { ProductGroups.of(it.first.offer.categoryId, it.first.productName) in mainGroups }
            .mapNotNull { (display, _, score) -> unitCents(display, loyalty)?.let { Triple(display, score, it) } }
        val within = priced.filter { item.maxPriceCents == null || it.third <= item.maxPriceCents }
        if (within.isEmpty()) return Line(item, null, null, overLimitOnly = priced.isNotEmpty())
        val bestScore = within.maxOf { it.second }
        val ordered = within.sortedWith(compareBy({ it.third }, { -it.second }))
        val options = ordered.distinctBy { it.first.productName.lowercase() to it.first.offer.retailerId }
            .map { Option(it.first, it.third) }
        // Exact wishes (brand, size, several words) get the best match; general words the cheapest real one.
        val general = queries.any { it.terms.size == 1 && it.quantities.isEmpty() }
        val pick = when {
            uncertain -> null
            general -> ordered.first()
            else -> ordered.filter { it.second == bestScore }.minByOrNull { it.third }
        }
        // All candidates go back to line(), which narrows them by sort and keeps a small choice.
        return Line(item, pick?.first, pick?.third, options = options, uncertain = uncertain)
    }

    /** The plan with the user's picks from the dialog; null keeps the product as a free note. */
    fun choose(plan: Plan.Add, picks: Map<Int, OfferDisplay?>, loyaltyPrograms: Set<String>): Plan.Add =
        plan.copy(lines = plan.lines.mapIndexed { i, line ->
            if (i !in picks) line
            else picks[i].let { offer -> line.copy(offer = offer, unitCents = offer?.let { unitCents(it, loyaltyPrograms) }) }
        })

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
            val open = plan.lines.count { it.needsChoice && it.offer == null }
            if (open > 0) append(". Bei $open bitte die Sorte wählen")
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
