package de.lesecuritae.korbuino

import de.lesecuritae.korbuino.data.FavoriteEntity
import de.lesecuritae.korbuino.data.ProductEntity
import de.lesecuritae.korbuino.notifications.WatchMatcher
import java.time.LocalDate
import java.time.ZoneId

/** The cheapest offer for a favorite that is valid today, independent of the favorite's price limit. */
data class FavoriteBestOffer(
    val display: OfferDisplay,
    /** Price per pack when bought as advertised (whole bundle for multi-buy offers), in cents. */
    val unitPriceCents: Int,
    /** Cash amount that must be paid for the advertised quantity; this is what the price limit refers to. */
    val payableCents: Int,
    /** False when the favorite has a price limit and even the cheapest offer exceeds it. */
    val withinLimit: Boolean,
    /** Number of matching offers valid today, including the best one. */
    val matchCount: Int,
)

/**
 * Pure selection logic for the favorites overview and later the widget.
 *
 * Matching reuses [WatchMatcher] so the overview shows the same products, retailers
 * and postal code as the ntfy notifications. Differences on purpose:
 * - the price limit does not hide the best offer; it is reported as [FavoriteBestOffer.withinLimit],
 * - only offers valid today count ("aktuell"), so previews for later this week or next week are ignored.
 */
object FavoriteOffers {
    fun today(): LocalDate = LocalDate.now(ZoneId.of("Europe/Berlin"))

    fun best(
        favorite: FavoriteEntity,
        offers: List<OfferDisplay>,
        postalCode: String,
        today: LocalDate = today(),
        selectedPrograms: Set<String> = emptySet(),
    ): FavoriteBestOffer? {
        val currentOnly = favorite.copy(maxPriceCents = null, includeCurrentWeek = true, includeNextWeek = false)
        val candidates = offers.mapNotNull { display ->
            val offer = display.offer
            val from = offer.validFrom?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            if (from > today) return@mapNotNull null
            val product = ProductEntity(offer.productId, display.productName, display.brand, normalizedKey = "")
            if (!WatchMatcher.matches(currentOnly, product, offer, postalCode, today, selectedPrograms)) return@mapNotNull null
            val quantity = (offer.multiBuyQuantity ?: 1).coerceAtLeast(1)
            val payable = OfferPricing.quote(offer, quantity, selectedPrograms).totalCents ?: return@mapNotNull null
            Triple(display, payable, payable / quantity)
        }
        val (display, payable, unit) = candidates
            .minWithOrNull(compareBy<Triple<OfferDisplay, Int, Int>> { it.third }.thenBy { it.second }
                .thenBy { it.first.offer.retailerId }.thenBy { it.first.offer.id })
            ?: return null
        val limit = favorite.maxPriceCents
        return FavoriteBestOffer(display, unit, payable, limit == null || payable <= limit, candidates.size)
    }
}

/** Only absolute HTTPS links with a host are opened; anything else hides the link button. */
internal fun offerLinkOrNull(url: String?): String? {
    val value = url?.trim().orEmpty()
    if (value.isEmpty()) return null
    val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return null
    return value.takeIf { uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() }
}
