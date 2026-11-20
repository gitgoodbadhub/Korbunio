package de.lesecuritae.korbuino

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.lesecuritae.korbuino.data.ShoppingListRow
import java.time.LocalDate
import java.time.ZoneId

internal data class ShoppingActions(
    val add: (String) -> Unit = {},
    val remove: (ShoppingListRow) -> Unit = {},
    val undo: () -> Unit = {},
    val quantity: (ShoppingListRow, Int) -> Unit = { _, _ -> },
    val checked: (ShoppingListRow, Boolean) -> Unit = { _, _ -> },
    val choose: (ShoppingListRow, OfferDisplay?) -> Unit = { _, _ -> },
    val favorite: (String) -> Unit = {},
    val route: (List<String>) -> Unit = {},
    val clearRoute: () -> Unit = {},
)

/** One bounded vertical list: headers, plan and expanded rows all share its scroll state. */
@Composable
internal fun ShoppingListContent(
    state: MainUiState,
    retailerNames: Map<String, String>,
    actions: ShoppingActions = ShoppingActions(),
    modifier: Modifier = Modifier,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(setOf<String>()) }
    val today = LocalDate.now(ZoneId.of("Europe/Berlin"))
    val plan = remember(state.shoppingItems, state.offers, state.selectedLoyaltyPrograms, today) {
        ShoppingPlan.buildRows(state.shoppingItems, state.offers, state.selectedLoyaltyPrograms, today)
    }
    val positions = plan.positions.associateBy { it.itemId }
    val groups = state.shoppingItems.groupBy { row ->
        when {
            row.checked -> "__done"
            row.retailerId == null -> "__manual"
            positions[row.itemId]?.chosenOffer == null -> "__unavailable:${row.retailerId}"
            else -> row.retailerId!!
        }
    }.toSortedMap(compareBy<String> { it.startsWith("__") }.thenBy { it })
    Column(modifier = modifier.fillMaxSize().imePadding()) {
        // Undo remains reachable even after the removed last row disappears.
        state.removedShoppingItem?.let { removed ->
            Text("„${removed.name}“ entfernt.")
            TextButton(onClick = actions.undo, modifier = Modifier.testTag("shopping-undo")) { Text("Rückgängig") }
        }
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("shopping-list"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item(key = "input") {
                Column {
                    Text("Einkaufsliste", style = MaterialTheme.typography.titleLarge)
                    Row {
                        OutlinedTextField(input, { input = it.take(120) }, label = { Text("Artikel hinzufügen") },
                            singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(onClick = { actions.add(input); input = "" }, enabled = input.isNotBlank()) { Text("+") }
                    }
                }
            }
            if (state.shoppingItems.isNotEmpty()) item(key = "plan") {
                ShoppingPlanCard(plan, retailerNames, routeLoading = state.storeRoute.loading, onRoute = { actions.route(plan.actualStores) })
            }
            if (state.storeRoute.requested) item(key = "route") {
                StoreRouteCard(state.storeRoute, retailerNames, onClose = actions.clearRoute, postalCode = state.postalCode)
            }
            groups.forEach { (group, rows) ->
                val label = when {
                    group == "__manual" -> "Ohne Händlerzuordnung"
                    group == "__done" -> "Erledigt"
                    group.startsWith("__unavailable:") -> "Kein aktueller Treffer · ${retailerNames[group.substringAfter(':')] ?: group.substringAfter(':')}"
                    else -> retailerNames[group] ?: group
                }
                item(key = "retailer:$group") { Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("retailer:$group")) }
                items(rows, key = { "item:${it.itemId}" }) { row ->
                    val position = positions[row.itemId]
                    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().testTag("shopping-item:${row.itemId}")) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Only show the assigned pack, never an arbitrary similar search result.
                            val picturedOffer = position?.chosenOffer ?: row.offerId?.let { id ->
                                state.offers.firstOrNull { it.offer.id == id &&
                                    (row.retailerId == null || it.offer.retailerId == row.retailerId) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OfferThumbnail(
                                    picturedOffer?.imagePath, picturedOffer?.imageUrl,
                                    picturedOffer?.imageReferer ?: picturedOffer?.offer?.sourceUrl.orEmpty(),
                                    modifier = Modifier.testTag("shopping-image:${row.itemId}"),
                                    note = picturedOffer?.imageNote, fallbacks = picturedOffer?.imageFallbacks.orEmpty(),
                                    contentDescription = picturedOffer?.let { "Produktbild: ${it.productName}" },
                                )
                                Text(row.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            }
                            Text("Menge: ${row.quantity} Packung(en)")
                            Row {
                                TextButton(onClick = { actions.quantity(row, row.quantity - 1) }, enabled = row.quantity > 1) { Text("−") }
                                TextButton(onClick = { actions.quantity(row, row.quantity + 1) }, enabled = row.quantity < 999) { Text("+") }
                                TextButton(onClick = { actions.checked(row, !row.checked) }) { Text(if (row.checked) "Wieder öffnen" else "Erledigen") }
                            }
                            if (!row.checked) {
                                position?.chosenOffer?.let { display ->
                                    Text(display.productName)
                                    Text(offerPriceLabel(display, state.selectedLoyaltyPrograms))
                                    display.offer.basePriceCents?.let { base ->
                                        if (display.offer.multiBuyQuantity != null || position.basePriceCents != null) {
                                            Text("Grundpreis: ${OfferMatcher.formatPrice(base)}/${display.offer.baseUnit ?: "Einheit"}${if (display.offer.multiBuyQuantity != null) " bei Mehrkauf" else ""}")
                                        }
                                    }
                                    Text("Gültig: ${display.offer.validFrom ?: "Beginn unbekannt"} bis ${display.offer.validUntil ?: "Ende unbekannt"}")
                                }
                                Text("Position ohne Pfand: ${position?.totalCents?.let(OfferMatcher::formatPrice) ?: "Preis nicht vollständig bekannt"}")
                                position?.quote?.takeIf { it.unpricedQuantity > 0 }?.let {
                                    Text("Belegt: ${OfferMatcher.formatPrice(it.knownTotalCents)}; Preis für ${it.unpricedQuantity} Packung(en) offen.")
                                }
                                position?.depositCents?.let { if (it > 0) Text("Pfand zusätzlich: ${OfferMatcher.formatPrice(it)}") }
                                if (row.offerId != null && position?.chosenOffer == null) Text("Gewähltes Angebot abgelaufen oder nicht mehr verfügbar. Bitte neu zuordnen.")
                            }
                            if (row.note.isNotBlank()) Text(row.note, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { expanded = if (row.itemId in expanded) expanded - row.itemId else expanded + row.itemId }) {
                                Text(if (row.itemId in expanded) "Angebote ausblenden" else "Im Angebot?")
                            }
                            if (row.itemId in expanded) {
                                val matches = OfferMatcher.matches(row.name, state.offers).filter { display ->
                                    // Future-starting offers stay listed (flagged below); only ended or unparseable ones drop out.
                                    display.offer.validUntil.isNullOrBlank() ||
                                        (BerlinDate.toLocalDate(display.offer.validUntil)?.let { it >= today.toString() } ?: false)
                                }
                                if (matches.isEmpty()) Text("Aktuell in keinem geladenen Angebot.")
                                matches.take(8).forEach { display ->
                                    val future = display.offer.validFrom?.takeIf { it.isNotBlank() }?.let { (BerlinDate.toLocalDate(it) ?: "9999-12-31") > today.toString() } ?: false
                                    key(display.offer.id) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            OfferThumbnail(display.imagePath, display.imageUrl, display.imageReferer ?: display.offer.sourceUrl,
                                                note = display.imageNote, fallbacks = display.imageFallbacks,
                                                modifier = Modifier.testTag("shopping-match-image:${row.itemId}:${display.offer.id}"),
                                                contentDescription = "Produktbild: ${display.productName}")
                                            Column(Modifier.weight(1f)) {
                                                Text("${retailerNames[display.offer.retailerId] ?: display.offer.retailerId}: ${display.productName} · ${offerPriceLabel(display, state.selectedLoyaltyPrograms)}")
                                                if (future) Text("Zukünftig ab ${display.offer.validFrom}; nicht im heutigen Gesamtpreis.")
                                                else TextButton(onClick = { actions.choose(row, display) }) { Text("Dieses Angebot auswählen") }
                                            }
                                        }
                                    }
                                }
                                if (matches.size > 8) Text("${matches.size - 8} weitere Treffer in der Angebotssuche.")
                                if (row.offerId != null) TextButton(onClick = { actions.choose(row, null) }) { Text("Zuordnung aufheben") }
                            }
                            TextButton(onClick = { actions.favorite(row.name) }) { Text("Als Favorit speichern") }
                            TextButton(onClick = { actions.remove(row) }, modifier = Modifier.testTag("shopping-remove:${row.itemId}")) { Text("Entfernen") }
                        }
                    }
                }
                if (!group.startsWith("__")) item(key = "subtotal:$group") {
                    val amounts = rows.map { positions[it.itemId]?.totalCents }
                    Text("Zwischensumme $label ohne Pfand: ${if (amounts.all { it != null }) OfferMatcher.formatPrice(amounts.filterNotNull().sum()) else "unvollständig"}",
                        modifier = Modifier.testTag("subtotal:$group"))
                }
            }
            if (state.shoppingItems.isEmpty()) item(key = "empty") { Text("Noch keine Artikel in der Einkaufsliste.") }
        }
    }
}

/** Never present a conditional derived unit price as the price of one pack. */
internal fun offerPriceLabel(display: OfferDisplay, selectedPrograms: Set<String>): String {
    val offer = display.offer
    val quantity = offer.multiBuyQuantity
    val bundle = offer.multiBuyTotalCents
    return if (quantity != null && bundle != null) {
        "$quantity Packungen für ${OfferMatcher.formatPrice(bundle)} · rechnerisch ${OfferMatcher.formatPrice(bundle / quantity)} je Packung nur bei Mehrkauf" +
            offer.multiBuyCondition?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
    } else {
        OfferPricing.quote(offer, 1, selectedPrograms).totalCents?.let(OfferMatcher::formatPrice) ?: "Preis nicht belegt"
    }
}

/** Compact plan: shops as badges, one total with a verdict, details on demand. */
@Composable
internal fun ShoppingPlanCard(plan: ShoppingPlan.Plan, retailerNames: Map<String, String>, routeLoading: Boolean, onRoute: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.testTag("shopping-plan")) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Einkaufsplan", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(plan.actualTotalCents?.let(OfferMatcher::formatPrice) ?: "–", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.testTag("shopping-total"))
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("shopping-plan-toggle")) {
                    Text(if (expanded) "▴" else "▾")
                }
            }
            if (plan.actualStores.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).testTag("shopping-plan-badges"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    plan.actualStores.forEach { id -> RetailerBadge(id, retailerNames[id] ?: id) }
                }
            }
            when (val verdict = PlanSummary.verdict(plan.actualTotalCents, plan.suggestedTotalCents)) {
                PlanSummary.Verdict.Cheapest -> Text("✓ günstigste Auswahl", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("shopping-verdict"))
                is PlanSummary.Verdict.Cheaper -> Text("Günstiger möglich: ${OfferMatcher.formatPrice(verdict.cheaperTotalCents)} " +
                    "(−${OfferMatcher.formatPrice(verdict.savingCents)})", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("shopping-verdict"))
                PlanSummary.Verdict.Unknown -> if (plan.unmatchedItems.isNotEmpty()) {
                    Text("${plan.unmatchedItems.size} ohne aktuelles Angebot", style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("shopping-verdict"))
                }
            }
            if (expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("shopping-plan-details")) {
                    val small = MaterialTheme.typography.bodySmall
                    Text("Summe deiner ausgewählten Angebote, ohne Pfand.", style = small)
                    plan.suggestedTotalCents?.let {
                        Text("Günstigster Preis für dieselben Mengen: ${OfferMatcher.formatPrice(it)} bei ${plan.cheapestStores.size} Händlern.", style = small)
                    }
                    val deposit = plan.positions.sumOf { it.depositCents ?: 0 }
                    if (deposit > 0) Text("Pfand zusätzlich: ${OfferMatcher.formatPrice(deposit)}", style = small)
                    val bonus = plan.positions.sumOf { it.cashbackCents ?: 0 }
                    if (bonus > 0) Text("Bonusguthaben nach dem Kauf: ${OfferMatcher.formatPrice(bonus)} (nicht abgezogen)", style = small)
                    if (plan.savedTrips > 0) Text("Spartipp: ${plan.savedTrips} Einkaufswege weniger für " +
                        "${OfferMatcher.formatPrice(plan.consolidationExtraCents)} mehr.", style = small)
                    if (plan.unmatchedItems.isNotEmpty()) Text("Ohne aktuelles Angebot: ${plan.unmatchedItems.joinToString()}", style = small)
                }
            }
            if (plan.actualStores.isNotEmpty()) {
                Button(onClick = onRoute, enabled = !routeLoading, modifier = Modifier.testTag("shopping-route")) { Text("Filialen & Route anzeigen") }
            }
        }
    }
}
