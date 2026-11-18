package de.lesecuritae.korbuino.providers

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Korbunio Plus: Action, KODi, KiK and Woolworth via the Marktguru/kaufDA catalogues. */
class CatalogueRetailersTest {
    private lateinit var server: MockWebServer
    private val added = mapOf("action" to "Action", "kodi" to "KODi", "kik" to "KiK", "woolworth" to "Woolworth")

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun `new retailers are selectable under their own name with the catalogue fallback`() {
        val registry = ProviderRegistry.default(OkHttpClient())
        added.forEach { (id, name) ->
            val provider = registry.byId(id)
            assertTrue("$id must be registered", provider != null)
            assertEquals(name, provider!!.displayName)
            assertTrue("$id must fall back to kaufDA", provider is FallbackRetailerProvider)
        }
    }

    @Test fun `existing retailers stay registered`() {
        val ids = ProviderRegistry.default(OkHttpClient()).all().map { it.id }.toSet()
        listOf("aldi-nord", "aldi-sued", "edeka", "trinkgut", "marktguru-lidl", "marktguru-penny").forEach {
            assertTrue("$it must remain selectable", it in ids)
        }
    }

    @Test fun `only offers of the requested advertiser are kept`() = runTest {
        server.enqueue(MockResponse().setBody("<script>{\"apiKey\":\"a\",\"clientKey\":\"c\"}</script>"))
        server.enqueue(MockResponse().setBody("""
            {"results":[
              {"id":"own","advertisers":[{"uniqueName":"kodi"}],"product":{"name":"Allzweckreiniger 1 l"},"price":"1,99"},
              {"id":"foreign","advertisers":[{"uniqueName":"kik"}],"product":{"name":"T-Shirt"},"price":"3,99"}
            ]}
        """))
        val provider = MarktguruProvider("KODi", OkHttpClient(), server.url("/home").toString(), server.url("/search").toString(),
            providerId = "kodi", providerDisplayName = "KODi")
        val result = provider.fetch(RetailerRequest("56070"))
        assertEquals(listOf(199), result.offers.map { it.priceCents })
        server.takeRequest()
        assertEquals("KODi", server.takeRequest().requestUrl!!.queryParameter("q"))
    }

    @Test fun `placeholder brands are dropped but real brands are kept`() {
        listOf("thisisnobrand", "THISISNOBRAND2", "This is no brand", "ohne Marke", "  no-brand ", "", null).forEach {
            assertEquals("'$it' is a placeholder", "", ProviderParsing.cleanBrand(it))
        }
        assertEquals("Milka", ProviderParsing.cleanBrand(" Milka "))
        assertEquals("No Name Foods", ProviderParsing.cleanBrand("No Name Foods"))
    }

    @Test fun `Woolworth placeholder brand does not end up in the product name`() = runTest {
        server.enqueue(MockResponse().setBody("<script>{\"apiKey\":\"a\",\"clientKey\":\"c\"}</script>"))
        server.enqueue(MockResponse().setBody("""
            {"results":[{"id":"golf","advertisers":[{"uniqueName":"woolworth"}],"brand":{"name":"thisisnobrand"},
              "product":{"name":"123 Miniatur Golf Set"},"price":"1,50"}]}
        """))
        val provider = MarktguruProvider("Woolworth", OkHttpClient(), server.url("/home").toString(), server.url("/search").toString(),
            providerId = "woolworth", providerDisplayName = "Woolworth")
        val result = provider.fetch(RetailerRequest("56070"))
        assertEquals(listOf("123 Miniatur Golf Set"), result.products.map { it.name })
        assertEquals("", result.products.single().brand)
    }
}
