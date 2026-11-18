package de.lesecuritae.korbuino

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag

/**
 * Opens the retailer page of an offer in the browser. Nothing is shown when the
 * offer has no usable HTTPS link, and a missing browser is ignored instead of crashing.
 */
@Composable
internal fun OfferLinkButton(sourceUrl: String?, tag: String, modifier: Modifier = Modifier) {
    val link = offerLinkOrNull(sourceUrl) ?: return
    val uriHandler = LocalUriHandler.current
    TextButton(onClick = { runCatching { uriHandler.openUri(link) } }, modifier = modifier.testTag(tag)) {
        Text("Zum Angebot ↗")
    }
}
