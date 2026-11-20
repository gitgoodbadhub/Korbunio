package de.lesecuritae.korbuino

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.lesecuritae.korbuino.data.ShoppingListRow

/**
 * Confirmation step of the voice assistant: shows what was understood and what would happen.
 * The recognised sentence stays editable, so a misheard word is fixed by typing instead of speaking again.
 */
@Composable
internal fun VoiceAssistantDialog(
    initialText: String,
    offers: List<OfferDisplay>,
    shoppingItems: List<ShoppingListRow>,
    loyaltyPrograms: Set<String>,
    retailerNames: Map<String, String>,
    today: String,
    onListenAgain: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (VoiceAssistant.Plan) -> Unit,
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    val plan = remember(text, offers, shoppingItems, loyaltyPrograms) {
        VoiceAssistant.plan(ShoppingCommand.parse(text), offers, shoppingItems, loyaltyPrograms, today)
    }
    val runnable = when (plan) {
        is VoiceAssistant.Plan.Unknown -> false
        is VoiceAssistant.Plan.Remove -> plan.rows.isNotEmpty()
        is VoiceAssistant.Plan.Route -> plan.retailerIds.isNotEmpty()
        else -> true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("voice-dialog"),
        title = { Text("Sprachassistent") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(300) },
                    label = { Text("Verstanden") }, modifier = Modifier.fillMaxWidth().testTag("voice-text"),
                )
                Text(VoiceAssistant.describe(plan, retailerNames), fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("voice-summary"))
                if (plan is VoiceAssistant.Plan.Add) plan.lines.forEach { line -> VoiceLine(line, retailerNames) }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(plan) }, enabled = runnable, modifier = Modifier.testTag("voice-confirm")) {
                Text("Ausführen")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onListenAgain) { Text("🎤 Neu") }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}

@Composable
private fun VoiceLine(line: VoiceAssistant.Line, retailerNames: Map<String, String>) {
    val item = line.item
    val title = (if (item.quantity > 1) "${item.quantity}× " else "") + item.name.replaceFirstChar { it.titlecase() } +
        (item.maxPriceCents?.let { " (bis ${OfferMatcher.formatPrice(it)})" } ?: "")
    Column(modifier = Modifier.fillMaxWidth().testTag("voice-line")) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        val offer = line.offer
        val detail = when {
            offer != null -> "${offer.productName} · ${retailerNames[offer.offer.retailerId] ?: offer.offer.retailerId}" +
                (line.unitCents?.let { " · ${OfferMatcher.formatPrice(it)}" } ?: "")
            line.overLimitOnly -> "Nur teurere Angebote – kommt als Notiz auf die Liste"
            else -> "Kein Angebot – kommt als Notiz auf die Liste"
        }
        Text(detail, style = MaterialTheme.typography.bodySmall,
            color = if (offer != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
