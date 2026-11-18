package de.lesecuritae.korbuino

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Shops of the shopping plan in visiting order, with one map link per stop and the whole trip for Google Maps. */
@Composable
internal fun StoreRouteCard(route: StoreRouteUi, retailerNames: Map<String, String>, onClose: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    fun open(uri: String?) { if (uri != null) runCatching { uriHandler.openUri(uri) } }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().testTag("route-card")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Einkaufsroute", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose, modifier = Modifier.testTag("route-close")) { Text("Schließen") }
            }
            when {
                route.loading -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Text("Suche die nächsten Filialen …")
                }
                route.error != null -> Text(route.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("route-error"))
                else -> {
                    if (route.stops.isEmpty()) Text("Keine passende Filiale im Umkreis von 10 km gefunden.")
                    route.stops.forEachIndexed { index, stop ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("route-stop-${stop.retailerId}")) {
                            Column(Modifier.weight(1f)) {
                                Text("${index + 1}. ${retailerNames[stop.retailerId] ?: stop.name}", style = MaterialTheme.typography.titleSmall)
                                Text(listOf(stop.address.ifBlank { stop.name }, "%.1f km".format(java.util.Locale.GERMANY, stop.distanceKm))
                                    .joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { open(StoreRoute.geoUri(stop)) }) { Text("Navigation") }
                        }
                    }
                    if (route.stops.isNotEmpty()) {
                        Button(onClick = { open(StoreRoute.googleMapsUrl(route.stops)) }, modifier = Modifier.testTag("route-google-maps")) {
                            Text(if (route.stops.size > 1) "Ganze Route in Google Maps" else "In Google Maps öffnen")
                        }
                        if (route.stops.size > StoreRoute.MAX_STOPS) {
                            Text("Google Maps übernimmt höchstens ${StoreRoute.MAX_STOPS} Stopps.", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("„Navigation“ öffnet einen einzelnen Laden in einer Karten-App deiner Wahl (z. B. OsmAnd, Organic Maps).",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (route.missingRetailerIds.isNotEmpty()) {
                        Text("Keine Filiale in der Nähe gefunden: " + route.missingRetailerIds.joinToString { retailerNames[it] ?: it },
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("route-missing"))
                    }
                    Text("Filialdaten: © OpenStreetMap-Mitwirkende. Entfernung ab dem Mittelpunkt der Postleitzahl; die Route startet am aktuellen Standort.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
