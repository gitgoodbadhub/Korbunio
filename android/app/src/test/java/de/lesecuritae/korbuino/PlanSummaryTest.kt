package de.lesecuritae.korbuino

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanSummaryTest {
    @Test fun `verdict says cheapest, cheaper or unknown`() {
        assertEquals(PlanSummary.Verdict.Cheapest, PlanSummary.verdict(4334, 4334))
        assertEquals(PlanSummary.Verdict.Cheaper(4120, 214), PlanSummary.verdict(4334, 4120))
        assertEquals(PlanSummary.Verdict.Unknown, PlanSummary.verdict(null, 4120))
        assertEquals(PlanSummary.Verdict.Unknown, PlanSummary.verdict(4334, null))
    }

    @Test fun `every registered retailer has its own badge and labels stay short`() {
        val ids = de.lesecuritae.korbuino.providers.ProviderRegistry.default(OkHttpClient()).all().map { it.id }
        assertEquals(emptyList<String>(), ids.filterNot(RetailerBadges::hasStyle))
        ids.forEach { assertTrue("label of $it too long", RetailerBadges.style(it).label.length <= 9) }
    }

    @Test fun `unknown retailer gets a neutral badge with its short name`() {
        val style = RetailerBadges.style("neu", "Neuer Markt (regional)")
        assertEquals("Neuer", style.label)
        assertEquals(0xFF5F6368, style.background)
    }
}
