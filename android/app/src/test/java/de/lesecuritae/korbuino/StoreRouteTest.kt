package de.lesecuritae.korbuino

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.net.URLDecoder

class StoreRouteTest {
    // Koblenz city centre and four shops around it (coordinates rounded, tags as in OpenStreetMap).
    private val lat = 50.3569
    private val lon = 7.5890
    private val overpass = """
        {"elements":[
          {"type":"node","id":1,"lat":50.3600,"lon":7.5900,"tags":{"shop":"supermarket","brand":"Lidl","name":"Lidl","addr:street":"Hauptstraße","addr:housenumber":"1","addr:postcode":"56068","addr:city":"Koblenz"}},
          {"type":"node","id":2,"lat":50.4000,"lon":7.6500,"tags":{"shop":"supermarket","brand":"Lidl","name":"Lidl"}},
          {"type":"way","id":3,"center":{"lat":50.3500,"lon":7.5800},"tags":{"shop":"supermarket","brand":"ALDI Süd","name":"ALDI SÜD"}},
          {"type":"node","id":4,"lat":50.3650,"lon":7.5950,"tags":{"shop":"variety_store","brand":"Woolworth","name":"Woolworth"}},
          {"type":"node","id":5,"lat":50.3570,"lon":7.5891,"tags":{"shop":"bakery","name":"Lidl-Backshop"}},
          {"type":"node","id":6,"lat":50.3571,"lon":7.5892,"tags":{"shop":"supermarket","brand":"Netto Marken-Discount","name":"Netto"}}
        ]}
    """

    @Test fun `nearest matching shop per retailer is chosen and wrong shop types are ignored`() {
        val nearest = StoreRoute.nearestPerRetailer(overpass, listOf("marktguru-lidl", "aldi-sued", "woolworth", "netto-schwarz"), lat, lon)
        assertEquals(setOf("marktguru-lidl", "aldi-sued", "woolworth"), nearest.keys)
        val lidl = nearest.getValue("marktguru-lidl")
        assertEquals(50.3600, lidl.lat, 1e-9)
        assertEquals("Hauptstraße 1, 56068 Koblenz", lidl.address)
        // The Netto Marken-Discount shop is not a "Netto" (Dansk) shop.
        assertNull(nearest["netto-schwarz"])
        assertEquals(50.3500, nearest.getValue("aldi-sued").lat, 1e-9)
    }

    @Test fun `stops are ordered as nearest neighbour trip`() {
        val nearest = StoreRoute.nearestPerRetailer(overpass, listOf("marktguru-lidl", "aldi-sued", "woolworth"), lat, lon)
        val ordered = StoreRoute.order(nearest.values, lat, lon)
        assertEquals(listOf("marktguru-lidl", "woolworth", "aldi-sued"), ordered.map { it.retailerId })
    }

    @Test fun `google maps link has destination and waypoints, at most ten stops`() {
        val stops = (1..12).map { StoreStop("r$it", "Laden $it", "", 50.0 + it / 100.0, 7.0, it.toDouble()) }
        val url = StoreRoute.googleMapsUrl(stops)!!
        assertTrue(url.startsWith("https://www.google.com/maps/dir/?api=1&travelmode=driving&destination="))
        val decoded = URLDecoder.decode(url, "UTF-8")
        assertTrue(decoded.contains("destination=50.100000,7.000000"))
        assertEquals(9, decoded.substringAfter("waypoints=").split('|').size)
        assertNull(StoreRoute.googleMapsUrl(emptyList()))
        val single = URLDecoder.decode(StoreRoute.googleMapsUrl(stops.take(1))!!, "UTF-8")
        assertTrue(!single.contains("waypoints="))
    }

    @Test fun `geo link carries coordinates and a readable label`() {
        val uri = StoreRoute.geoUri(StoreStop("kik", "KiK (Filiale)", "Löhrstraße 2, 56068 Koblenz", 50.1, 7.5, 0.4))
        assertTrue(uri.startsWith("geo:50.100000,7.500000?q=50.100000,7.500000("))
        assertTrue(URLDecoder.decode(uri, "UTF-8").contains("KiK  Filiale  Löhrstraße 2, 56068 Koblenz"))
    }

    @Test fun `query covers every known retailer once and skips unknown ones`() {
        val query = StoreRoute.overpassQuery(listOf("kik", "kik", "unknown"), lat, lon, 5000)!!
        assertEquals(2, Regex("~\"\\^KiK\",i").findAll(query).count())
        assertTrue(query.contains("(around:5000,50.356900,7.589000)"))
        assertNull(StoreRoute.overpassQuery(listOf("unknown"), lat, lon))
    }

    @Test fun `every registered retailer has an OpenStreetMap brand rule`() {
        val ids = de.lesecuritae.korbuino.providers.ProviderRegistry.default(OkHttpClient()).all().map { it.id }
        assertEquals(emptyList<String>(), ids.filterNot { it in StoreRoute.brands })
        assertEquals(emptyList<String>(), ids.filterNot { it in StoreRoute.searchNames })
    }

    @Test fun `finder geocodes the postal code and reports retailers without a shop`() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setBody("""[{"lat":"$lat","lon":"$lon"}]"""))
            server.enqueue(MockResponse().setBody(overpass))
            val finder = StoreFinder(OkHttpClient(), server.url("/search").toString(), listOf(server.url("/interpreter").toString()))
            val result = finder.find("56068", listOf("marktguru-lidl", "kik", "unknown"))
            assertEquals(listOf("marktguru-lidl"), result.stops.map { it.retailerId })
            assertEquals(listOf("kik", "unknown"), result.missingRetailerIds)
            assertEquals("56068", server.takeRequest().requestUrl!!.queryParameter("postalcode"))
            val overpassRequest = server.takeRequest()
            assertEquals("POST", overpassRequest.method)
            assertTrue(overpassRequest.getHeader("User-Agent")!!.startsWith("Korbunio-Plus"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `overloaded overpass server falls back to the next one and results are cached`() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setBody("""[{"lat":"$lat","lon":"$lon"}]"""))
            server.enqueue(MockResponse().setResponseCode(502))
            server.enqueue(MockResponse().setBody(overpass))
            val finder = StoreFinder(OkHttpClient(), server.url("/search").toString(),
                listOf(server.url("/main").toString(), server.url("/mirror").toString()))
            val first = finder.find("56068", listOf("marktguru-lidl"))
            assertEquals(listOf("marktguru-lidl"), first.stops.map { it.retailerId })
            assertEquals(listOf("/search", "/main", "/mirror"), (1..3).map { server.takeRequest().requestUrl!!.encodedPath })
            // Same postal code and retailers again: answered from memory, no further request.
            assertEquals(first, finder.find("56068", listOf("marktguru-lidl")))
            assertEquals(3, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `all overpass servers overloaded gives a clear retry message`() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setBody("""[{"lat":"$lat","lon":"$lon"}]"""))
            server.enqueue(MockResponse().setResponseCode(502))
            server.enqueue(MockResponse().setResponseCode(504))
            val finder = StoreFinder(OkHttpClient(), server.url("/search").toString(),
                listOf(server.url("/a").toString(), server.url("/b").toString()))
            val error = runCatching { finder.find("56068", listOf("rewe")) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("überlastet (HTTP 504)"))
        } finally {
            server.shutdown()
        }
    }

    @Test fun `a broken query is not retried on other servers`() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setBody("""[{"lat":"$lat","lon":"$lon"}]"""))
            server.enqueue(MockResponse().setResponseCode(400))
            val finder = StoreFinder(OkHttpClient(), server.url("/search").toString(),
                listOf(server.url("/a").toString(), server.url("/b").toString()))
            val error = runCatching { finder.find("56068", listOf("rewe")) }.exceptionOrNull()
            assertEquals("Filialsuche (OpenStreetMap) HTTP 400", error?.message)
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun `map search links use plain shop names`() {
        assertEquals("Lidl", StoreRoute.searchName("marktguru-lidl", "Lidl (regional)"))
        assertEquals("Neuer Laden", StoreRoute.searchName("unknown", "Neuer Laden (regional)"))
        assertEquals("https://www.google.com/maps/search/?api=1&query=KiK%2056068", StoreRoute.googleMapsSearchUrl("KiK", "56068"))
        assertEquals("https://www.google.com/maps/search/?api=1&query=Lidl", StoreRoute.googleMapsSearchUrl("Lidl"))
        assertEquals("geo:0,0?q=HOL%27AB%21", StoreRoute.geoSearchUri("HOL'AB!"))
    }

    @Test fun `map points are GeoJSON in longitude-latitude order with visiting numbers`() {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(StoreRoute.stopsGeoJson(listOf(
            StoreStop("marktguru-lidl", "Lidl", "", 50.36, 7.59, 0.4),
            StoreStop("kik", "KiK \"Mitte\"", "", 50.37, 7.60, 1.0),
        ))).jsonObject
        assertEquals("FeatureCollection", json["type"]!!.jsonPrimitive.content)
        val features = json["features"]!!.jsonArray
        assertEquals(2, features.size)
        val second = features[1].jsonObject
        val coordinates = second["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() }
        assertEquals(listOf(7.60, 50.37), coordinates)
        assertEquals("2", second["properties"]!!.jsonObject["n"]!!.jsonPrimitive.content)
        assertEquals("KiK \"Mitte\"", second["properties"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("[]", kotlinx.serialization.json.Json.parseToJsonElement(StoreRoute.stopsGeoJson(emptyList())).jsonObject["features"].toString())
    }

    @Test fun `nominatim reverse answer becomes a street address`() {
        assertEquals("Warthestraße 4, 56410 Montabaur", StoreRoute.addressFromNominatim(
            """{"address":{"road":"Warthestraße","house_number":"4","postcode":"56410","town":"Montabaur","country":"Deutschland"}}"""))
        assertEquals("Hauptstraße, 56068 Koblenz", StoreRoute.addressFromNominatim(
            """{"address":{"road":"Hauptstraße","postcode":"56068","city":"Koblenz"}}"""))
        assertEquals("", StoreRoute.addressFromNominatim("""{"address":{"postcode":"56068"}}"""))
        assertEquals("", StoreRoute.addressFromNominatim("""{"error":"Unable to geocode"}"""))
        assertEquals("", StoreRoute.addressFromNominatim("kein json"))
    }

    @Test fun `shops without address tags get their address from a reverse lookup`() = runTest {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setBody("""[{"lat":"$lat","lon":"$lon"}]"""))
            server.enqueue(MockResponse().setBody(overpass))
            server.enqueue(MockResponse().setBody("""{"address":{"road":"Löhrstraße","house_number":"7","postcode":"56068","city":"Koblenz"}}"""))
            server.enqueue(MockResponse().setResponseCode(500))
            val finder = StoreFinder(OkHttpClient(), server.url("/search").toString(), listOf(server.url("/interpreter").toString()),
                reverseUrl = server.url("/reverse").toString(), nominatimPauseMillis = 0)
            val result = finder.find("56068", listOf("marktguru-lidl", "woolworth", "aldi-sued"))
            val byId = result.stops.associateBy { it.retailerId }
            // Lidl has address tags, Woolworth is the next stop and gets the reverse address, ALDI's lookup fails.
            assertEquals("Hauptstraße 1, 56068 Koblenz", byId.getValue("marktguru-lidl").address)
            assertEquals("Löhrstraße 7, 56068 Koblenz", byId.getValue("woolworth").address)
            assertEquals("", byId.getValue("aldi-sued").address)
            assertEquals(4, server.requestCount)
            val paths = (1..4).map { server.takeRequest().requestUrl!!.encodedPath }
            assertEquals(listOf("/search", "/interpreter", "/reverse", "/reverse"), paths)
        } finally {
            server.shutdown()
        }
    }
}
