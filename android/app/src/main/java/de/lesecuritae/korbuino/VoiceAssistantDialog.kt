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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material3.RadioButton
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
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
    /** What the assistant learned earlier ("wasser" → medium) and how to forget one entry. */
    memory: Map<String, VoiceMemory.Entry> = emptyMap(),
    onForget: (String) -> Unit = {},
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    // Gemini Nano (where the phone has it): its reading replaces the rule-based one until the text is edited.
    var aiStatus by remember { mutableStateOf(OnDeviceAi.Status.UNAVAILABLE) }
    var aiCommand by remember(initialText) { mutableStateOf<ShoppingCommand.Command?>(null) }
    var aiBusy by remember { mutableStateOf(false) }
    var aiNote by remember(initialText) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ruleCommand = remember(text) { ShoppingCommand.parse(text) }
    // Sorts picked here ("still"); a null value means "any sort".
    val variantPicks = remember(aiCommand, ruleCommand) { mutableStateMapOf<Int, String?>() }
    val variantSnapshot = variantPicks.toMap()
    val proposed = remember(aiCommand, ruleCommand, offers, shoppingItems, loyaltyPrograms, memory, variantSnapshot) {
        VoiceAssistant.plan(aiCommand ?: ruleCommand, offers, shoppingItems, loyaltyPrograms, today, memory, variantSnapshot)
    }
    // The user's picks among the offered sorts ("Welches Wasser?"); null = only a note on the list.
    val picks = remember(proposed) { mutableStateMapOf<Int, OfferDisplay?>() }
    val plan = if (proposed is VoiceAssistant.Plan.Add && picks.isNotEmpty()) VoiceAssistant.choose(proposed, picks.toMap(), loyaltyPrograms) else proposed
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
                if (plan is VoiceAssistant.Plan.Add) plan.lines.forEachIndexed { index, line ->
                    VoiceLine(line, retailerNames, today = today, onPick = { picks[index] = it },
                        onVariant = { variantPicks[index] = it }, onForget = { key -> onForget(key); variantPicks[index] = null })
                }
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
@OptIn(ExperimentalLayoutApi::class)
private fun VoiceLine(
    line: VoiceAssistant.Line,
    retailerNames: Map<String, String>,
    today: String = "",
    onPick: (OfferDisplay?) -> Unit,
    onVariant: (String?) -> Unit = {},
    onForget: (String) -> Unit = {},
) {
    val item = line.item
    val title = (if (item.quantity > 1) "${item.quantity}× " else "") + item.name.replaceFirstChar { it.titlecase() } +
        (item.maxPriceCents?.let { " (bis ${OfferMatcher.formatPrice(it)})" } ?: "")
    Column(modifier = Modifier.fillMaxWidth().testTag("voice-line")) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        val offer = line.offer
        if (line.variants.isNotEmpty() && line.options.isNotEmpty()) {
            val hint = MaterialTheme.typography.bodySmall
            when {
                line.variant == null -> Text("Welche Sorte? Tippen oder dazusagen (🎤 Weiter)", style = hint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                line.variantMissing -> Text("„${line.variant?.label}“ ist gerade nicht im Angebot – andere Sorten:", style = hint,
                    color = MaterialTheme.colorScheme.error)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                line.variants.forEach { variant ->
                    val selected = line.variant?.id == variant.id
                    FilterChip(
                        selected = selected,
                        onClick = { onVariant(if (selected) null else variant.id) },
                        label = { Text(variant.label + if (selected && line.variantSource == VoiceAssistant.VariantSource.REMEMBERED) " · gemerkt" else "") },
                        modifier = Modifier.testTag("voice-variant-${variant.id}"),
                    )
                }
            }
            if (line.variantSource == VoiceAssistant.VariantSource.REMEMBERED || line.rememberedProduct) line.memoryKey?.let { key ->
                TextButton(onClick = { onForget(key) }, modifier = Modifier.testTag("voice-forget")) { Text("Gemerktes vergessen", style = hint) }
            }
        }
        if (line.needsChoice) {
            Text(
                when {
                    line.uncertain -> "Kein genauer Treffer – passt eins davon?"
                    offer == null -> "Welche Sorte?"
                    else -> "Günstigste vorausgewählt – oder:"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            line.options.forEach { option ->
                ChoiceRow(selected = offer?.offer?.id == option.offer.offer.id, tag = "voice-option",
                    text = "${option.offer.productName} · ${retailerNames[option.offer.offer.retailerId] ?: option.offer.offer.retailerId}" +
                        " · ${OfferMatcher.formatPrice(option.unitCents)}" +
                        (VoiceAssistant.startsLater(option.offer, today)?.let { " · $it" } ?: ""),
                    onClick = { onPick(option.offer) })
            }
            ChoiceRow(selected = offer == null, tag = "voice-option-note", text = "Ohne Angebot, nur auf die Liste", onClick = { onPick(null) })
        } else {
            val detail = when {
                offer != null -> "${offer.productName} · ${retailerNames[offer.offer.retailerId] ?: offer.offer.retailerId}" +
                    (line.unitCents?.let { " · ${OfferMatcher.formatPrice(it)}" } ?: "") +
                    (VoiceAssistant.startsLater(offer, today)?.let { " · $it" } ?: "")
                line.overLimitOnly -> "Nur teurere Angebote – kommt als Notiz auf die Liste"
                else -> "Kein Angebot – kommt als Notiz auf die Liste"
            }
            Text(detail, style = MaterialTheme.typography.bodySmall,
                color = if (offer != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, tag: String, text: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).testTag(tag),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text, style = MaterialTheme.typography.bodySmall)
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
