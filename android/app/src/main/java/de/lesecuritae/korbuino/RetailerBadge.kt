package de.lesecuritae.korbuino

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Equally sized retailer badge in the retailer's colours with a short name. No logo files are shipped:
 * the logos are trademarks of the retailers; colour and name are enough to recognise them at a glance.
 */
internal data class RetailerBadgeStyle(val label: String, val background: Long, val text: Long)

internal object RetailerBadges {
    private val styles = mapOf(
        "rewe" to RetailerBadgeStyle("REWE", 0xFFCC071E, 0xFFFFFFFF),
        "edeka" to RetailerBadgeStyle("EDEKA", 0xFFFFD400, 0xFF1C3E8C),
        "marktguru-lidl" to RetailerBadgeStyle("Lidl", 0xFF0050AA, 0xFFFFF000),
        "aldi-nord" to RetailerBadgeStyle("ALDI N", 0xFF00589F, 0xFFFFFFFF),
        "aldi-sued" to RetailerBadgeStyle("ALDI S", 0xFF1E3C8C, 0xFFFFB400),
        "kaufland" to RetailerBadgeStyle("Kaufland", 0xFFE10915, 0xFFFFFFFF),
        "marktguru-penny" to RetailerBadgeStyle("PENNY", 0xFFCD1719, 0xFFFFFFFF),
        "netto-marken" to RetailerBadgeStyle("Netto", 0xFFFFE500, 0xFFE3000F),
        "netto-schwarz" to RetailerBadgeStyle("Netto", 0xFF000000, 0xFFFFD500),
        "globus" to RetailerBadgeStyle("Globus", 0xFF005E3C, 0xFFFFFFFF),
        "marktguru-famila-nordwest" to RetailerBadgeStyle("famila", 0xFFE30613, 0xFFFFFFFF),
        "marktguru-combi" to RetailerBadgeStyle("Combi", 0xFFE30613, 0xFFFFFFFF),
        "aktiv-irma" to RetailerBadgeStyle("aktiv", 0xFFE2001A, 0xFFFFFFFF),
        "holab" to RetailerBadgeStyle("HOL'AB", 0xFF004B93, 0xFFFFFFFF),
        "trinkgut" to RetailerBadgeStyle("trinkgut", 0xFF6AB023, 0xFFFFFFFF),
        "dm" to RetailerBadgeStyle("dm", 0xFF1C3C82, 0xFFFFD400),
        "rossmann" to RetailerBadgeStyle("Rossmann", 0xFFC3002F, 0xFFFFFFFF),
        "mueller" to RetailerBadgeStyle("Müller", 0xFFF18A00, 0xFFFFFFFF),
        "action" to RetailerBadgeStyle("Action", 0xFF0050A0, 0xFFFFFFFF),
        "kodi" to RetailerBadgeStyle("KODi", 0xFFFFED00, 0xFF003C8A),
        "kik" to RetailerBadgeStyle("KiK", 0xFFE2001A, 0xFFFFFFFF),
        "woolworth" to RetailerBadgeStyle("Woolworth", 0xFFD50F25, 0xFFFFFFFF),
    )

    /** Known style, or a neutral grey badge with the first word of the display name. */
    fun style(retailerId: String, displayName: String = retailerId): RetailerBadgeStyle = styles[retailerId]
        ?: RetailerBadgeStyle(displayName.substringBefore(" (").substringBefore(' ').ifBlank { retailerId }.take(9), 0xFF5F6368, 0xFFFFFFFF)

    fun hasStyle(retailerId: String) = retailerId in styles
}

@Composable
internal fun RetailerBadge(retailerId: String, displayName: String, modifier: Modifier = Modifier) {
    val style = RetailerBadges.style(retailerId, displayName)
    Box(
        modifier = modifier.size(width = 64.dp, height = 32.dp)
            .background(Color(style.background), RoundedCornerShape(8.dp))
            .semantics { contentDescription = displayName }
            .testTag("retailer-badge-$retailerId"),
        contentAlignment = Alignment.Center,
    ) {
        Text(style.label, color = Color(style.text), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Clip,
            fontSize = if (style.label.length > 6) 10.sp else 12.sp,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 3.dp))
    }
}

/** Short verdict for the shopping plan: is the current selection the cheapest one available? */
internal object PlanSummary {
    sealed interface Verdict {
        data object Cheapest : Verdict
        data class Cheaper(val cheaperTotalCents: Int, val savingCents: Int) : Verdict
        data object Unknown : Verdict
    }

    fun verdict(actualTotalCents: Int?, cheapestTotalCents: Int?): Verdict = when {
        actualTotalCents == null || cheapestTotalCents == null -> Verdict.Unknown
        cheapestTotalCents < actualTotalCents -> Verdict.Cheaper(cheapestTotalCents, actualTotalCents - cheapestTotalCents)
        else -> Verdict.Cheapest
    }
}
