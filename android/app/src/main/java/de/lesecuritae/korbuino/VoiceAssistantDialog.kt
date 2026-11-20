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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
    onListenAgain: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (VoiceAssistant.Plan) -> Unit,
    ai: OnDeviceAi = NoOnDeviceAi,
    /** True while the app is still listening; the text grows with every pause until "Fertig". */
    listening: Boolean = false,
    partial: String = "",
    note: String? = null,
    onStopListening: () -> Unit = {},
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    // Gemini Nano (where the phone has it): its reading replaces the rule-based one until the text is edited.
    var aiStatus by remember { mutableStateOf(OnDeviceAi.Status.UNAVAILABLE) }
    var aiCommand by remember(initialText) { mutableStateOf<ShoppingCommand.Command?>(null) }
    var aiBusy by remember { mutableStateOf(false) }
    var aiNote by remember(initialText) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ruleCommand = remember(text) { ShoppingCommand.parse(text) }
    val plan = remember(aiCommand, ruleCommand, offers, shoppingItems, loyaltyPrograms) {
        VoiceAssistant.plan(aiCommand ?: ruleCommand, offers, shoppingItems, loyaltyPrograms, today)
    }
    val askNano: () -> Unit = {
        val asked = text
        if (!aiBusy && asked.isNotBlank()) {
            aiBusy = true; aiNote = null
            scope.launch {
                val command = ai.generate(NanoCommand.prompt(asked))?.let(NanoCommand::parse)
                aiBusy = false
                if (text == asked) {
                    if (command != null) aiCommand = command
                    else aiNote = "Gemini Nano hat keine verwertbare Antwort gegeben."
                }
            }
        }
    }
    LaunchedEffect(ai) { aiStatus = ai.status() }
    // What the rules cannot read, or whole sentences without a command word, go to Gemini Nano straight away.
    // Asked automatically at most once per sentence: effects can be relaunched, the on-device quota is limited.
    var autoAsked by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(aiStatus, initialText, listening) {
        if (!listening && aiStatus == OnDeviceAi.Status.AVAILABLE && autoAsked != text && ShoppingCommand.needsHelp(text)) {
            autoAsked = text
            askNano()
        }
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
                    value = text, onValueChange = { text = it.take(300); aiCommand = null; aiNote = null },
                    label = { Text(if (listening) "🎤 Ich höre zu … sprechen Sie ruhig mit Pausen" else "Verstanden") },
                    readOnly = listening, modifier = Modifier.fillMaxWidth().testTag("voice-text"),
                )
                if (listening && partial.isNotBlank()) Text("… $partial", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("voice-partial"))
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Text(VoiceAssistant.describe(plan, retailerNames), fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("voice-summary"))
                if (!listening) NanoRow(aiStatus, aiBusy, usedNano = aiCommand != null, note = aiNote, onAsk = askNano, onDownload = {
                    aiStatus = OnDeviceAi.Status.DOWNLOADING
                    scope.launch { ai.download(); aiStatus = ai.status() }
                })
                if (plan is VoiceAssistant.Plan.Add) plan.lines.forEach { line -> VoiceLine(line, retailerNames) }
            }
        },
        confirmButton = {
            if (listening) Button(onClick = onStopListening, modifier = Modifier.testTag("voice-stop")) { Text("Fertig") }
            else Button(onClick = { onConfirm(plan) }, enabled = runnable, modifier = Modifier.testTag("voice-confirm")) {
                Text("Ausführen")
            }
        },
        dismissButton = {
            Row {
                if (!listening) TextButton(onClick = { onListenAgain(text) }, modifier = Modifier.testTag("voice-continue")) { Text("🎤 Weiter") }
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

@Composable
private fun NanoRow(status: OnDeviceAi.Status, busy: Boolean, usedNano: Boolean, note: String?, onAsk: () -> Unit, onDownload: () -> Unit) {
    val hint = MaterialTheme.typography.bodySmall
    when {
        busy -> Text("✨ Gemini Nano denkt …", style = hint, modifier = Modifier.testTag("voice-nano-busy"))
        status == OnDeviceAi.Status.AVAILABLE -> Column {
            if (usedNano) Text("✨ Von Gemini Nano auf dem Gerät verstanden", style = hint, color = MaterialTheme.colorScheme.primary)
            note?.let { Text(it, style = hint) }
            TextButton(onClick = onAsk, modifier = Modifier.testTag("voice-nano")) {
                Text(if (usedNano) "✨ Erneut fragen" else "✨ Gemini Nano fragen")
            }
        }
        status == OnDeviceAi.Status.DOWNLOADABLE -> TextButton(onClick = onDownload, modifier = Modifier.testTag("voice-nano-download")) {
            Text("✨ Gemini Nano laden (einmalig, über WLAN empfohlen)")
        }
        status == OnDeviceAi.Status.DOWNLOADING -> Text("✨ Gemini Nano wird auf das Gerät geladen …", style = hint)
        else -> Unit
    }
}
