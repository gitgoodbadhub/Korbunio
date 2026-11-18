package de.lesecuritae.korbuino

import de.lesecuritae.korbuino.data.FavoriteEntity
import de.lesecuritae.korbuino.data.OfferEntity
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FavoriteOffersTest {
    private val today = LocalDate.parse("2026-12-30")
    private val favorite = FavoriteEntity("fav", 0, query = "Coca-Cola")
    private fun offer(id: String, retailer: String, cents: Int, from: String? = "2026-12-28", until: String? = "2027-01-03",
                      name: String = "Coca Cola Zero 1l", url: String = "https://example.org/$id") =
        OfferDisplay(OfferEntity(id, retailer, "p-$id", externalId = id, priceCents = cents,
            validFrom = from, validUntil = until, sourceUrl = url, cachedAt = 0), productName = name)

    private fun best(fav: FavoriteEntity = favorite, offers: List<OfferDisplay>, programs: Set<String> = emptySet()) =
        FavoriteOffers.best(fav, offers, "12345", today, programs)

    @Test fun picksCheapestCurrentOfferAcrossRetailers() {
        val result = best(offers = listOf(offer("a", "rewe", 149), offer("b", "aldi-nord", 119), offer("c", "lidl", 129)))!!
        assertEquals("b", result.display.offer.id)
        assertEquals(119, result.unitPriceCents)
        assertEquals(3, result.matchCount)
        assertTrue(result.withinLimit)
    }

    @Test fun ignoresExpiredFutureUndatedAndUnrelatedOffers() {
        val offers = listOf(
            offer("expired", "rewe", 50, until = "2026-12-29"),
            offer("future", "rewe", 60, from = "2026-12-31"),
            offer("undated", "rewe", 70, from = null),
            offer("other", "rewe", 80, name = "Pepsi Cola 1l"),
            offer("ok", "rewe", 149),
        )
        val result = best(offers = offers)!!
        assertEquals("ok", result.display.offer.id)
        assertEquals(1, result.matchCount)
    }

    @Test fun noMatchGivesNull() {
        assertNull(best(offers = listOf(offer("other", "rewe", 80, name = "Pepsi"))))
        assertNull(best(offers = emptyList()))
    }

    @Test fun priceLimitIsReportedButDoesNotHideTheBestOffer() {
        val result = best(favorite.copy(maxPriceCents = 100), listOf(offer("a", "rewe", 149)))!!
        assertEquals("a", result.display.offer.id)
        assertFalse(result.withinLimit)
        assertTrue(best(favorite.copy(maxPriceCents = 149), listOf(offer("a", "rewe", 149)))!!.withinLimit)
    }

    @Test fun respectsPreferredRetailersAndPostalCode() {
        val offers = listOf(offer("a", "rewe", 149), offer("b", "aldi-nord", 119))
        assertEquals("a", best(favorite.copy(preferredRetailerIdsJson = "[\"rewe\"]"), offers)!!.display.offer.id)
        assertNull(best(favorite.copy(postalCode = "99999"), offers))
    }

    @Test fun multiBuyIsComparedPerPackAndLimitAppliesToBundle() {
        val bundle = offer("bundle", "kaufland", 0).let {
            it.copy(offer = it.offer.copy(multiBuyQuantity = 2, multiBuyTotalCents = 200))
        }
        val single = offer("single", "rewe", 110)
        val result = best(favorite.copy(maxPriceCents = 150), listOf(single, bundle))!!
        assertEquals("bundle", result.display.offer.id)
        assertEquals(100, result.unitPriceCents)
        assertEquals(200, result.payableCents)
        assertFalse(result.withinLimit)
    }

    @Test fun equalPricesAreOrderedStably() {
        val first = best(offers = listOf(offer("x", "rewe", 100), offer("y", "aldi-nord", 100)))!!
        val second = best(offers = listOf(offer("y", "aldi-nord", 100), offer("x", "rewe", 100)))!!
        assertEquals(first.display.offer.id, second.display.offer.id)
        assertEquals("aldi-nord", first.display.offer.retailerId)
    }

    @Test fun onlyHttpsLinksWithHostAreOpened() {
        assertEquals("https://www.rewe.de/angebote/x", offerLinkOrNull(" https://www.rewe.de/angebote/x "))
        assertNull(offerLinkOrNull(null))
        assertNull(offerLinkOrNull(""))
        assertNull(offerLinkOrNull("http://www.rewe.de/x"))
        assertNull(offerLinkOrNull("javascript:alert(1)"))
        assertNull(offerLinkOrNull("https:///nohost"))
        assertNull(offerLinkOrNull("rewe.de/x"))
    }
}
