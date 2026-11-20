package de.lesecuritae.korbuino

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

/** One shop to visit, taken from OpenStreetMap. */
data class StoreStop(
    val retailerId: String,
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val distanceKm: Double,
)

/** Route state shown in the shopping tab. */
data class StoreRouteUi(
    val requested: Boolean = false,
    val loading: Boolean = false,
    /** Retailers of the shopping plan this route was asked for; offered for a manual map search. */
    val retailerIds: List<String> = emptyList(),
    val stops: List<StoreStop> = emptyList(),
    val missingRetailerIds: List<String> = emptyList(),
    val error: String? = null,
)

/** Shops for the retailers of the shopping plan, in visiting order, plus retailers without a shop nearby. */
data class StoreRouteResult(val stops: List<StoreStop>, val missingRetailerIds: List<String>)

/**
 * Pure route logic (Korbunio Plus): maps retailer ids to OpenStreetMap shop brands,
 * picks the nearest shop per retailer, orders the stops and builds map links.
 * No network here, so everything is unit-testable.
 */
object StoreRoute {
    /** Google Maps accepts up to nine waypoints plus the destination. */
    const val MAX_STOPS = 10
    const val DEFAULT_RADIUS_M = 10_000

    /** Brand/name pattern (POSIX ERE and Kotlin compatible, matched case-insensitively) and allowed OSM shop types. */
    data class Brand(val pattern: String, val shopTypes: Set<String>)

    private val food = setOf("supermarket", "convenience", "department_store", "discount", "general", "wholesale")
    private val drug = setOf("chemist", "department_store", "cosmetics", "supermarket")
    private val nonFood = setOf("variety_store", "discount", "department_store", "general", "clothes", "houseware", "doityourself")

    val brands: Map<String, Brand> = mapOf(
        "rewe" to Brand("^REWE( |$)", food),
        "globus" to Brand("^Globus$", food),
        "aldi-nord" to Brand("^ALDI Nord", food),
        "aldi-sued" to Brand("^ALDI (Süd|Sued|Sud)", food),
        "kaufland" to Brand("^Kaufland", food),
        "edeka" to Brand("^(EDEKA|E center|E-Center|Marktkauf)", food),
        "marktguru-lidl" to Brand("^Lidl", food),
        "marktguru-penny" to Brand("^Penny", food),
        "netto-marken" to Brand("^Netto Marken-Discount", food),
        "netto-schwarz" to Brand("^Netto$", food),
        "marktguru-famila-nordwest" to Brand("^famila", food),
        "marktguru-combi" to Brand("^combi", food),
        "aktiv-irma" to Brand("^(aktiv|Irma)", food),
        "holab" to Brand("^HOL.?AB", food + "beverages"),
        "trinkgut" to Brand("^trinkgut", setOf("beverages", "supermarket")),
        "dm" to Brand("^dm($| |-drogerie)", drug),
        "rossmann" to Brand("^(Dirk )?Rossmann", drug),
        "mueller" to Brand("^(Drogerie )?Müller", drug + "variety_store"),
        "action" to Brand("^Action$", nonFood),
        "kodi" to Brand("^KODi", nonFood),
        "kik" to Brand("^KiK", nonFood),
        "woolworth" to Brand("^Woolworth", nonFood),
    )

    /** Plain shop name for a search in a map app (display names may carry suffixes such as "(regional)"). */
    val searchNames: Map<String, String> = mapOf(
        "rewe" to "REWE", "globus" to "Globus", "aldi-nord" to "ALDI Nord", "aldi-sued" to "ALDI SÜD",
        "kaufland" to "Kaufland", "edeka" to "EDEKA", "marktguru-lidl" to "Lidl", "marktguru-penny" to "PENNY",
        "netto-marken" to "Netto Marken-Discount", "netto-schwarz" to "Netto Scottie", "marktguru-famila-nordwest" to "famila",
        "marktguru-combi" to "Combi", "aktiv-irma" to "aktiv & irma", "holab" to "HOL'AB!", "trinkgut" to "trinkgut",
        "dm" to "dm-drogerie markt", "rossmann" to "Rossmann", "mueller" to "Müller Drogerie",
        "action" to "Action", "kodi" to "KODi", "kik" to "KiK", "woolworth" to "Woolworth",
    )

    fun searchName(retailerId: String, fallback: String = retailerId): String =
        searchNames[retailerId] ?: fallback.replace(Regex("\\s*\\(.*\\)$"), "").trim()

    /** Google Maps search for a shop near the postal code; the Maps app opens it when installed. */
    fun googleMapsSearchUrl(shopName: String, postalCode: String = ""): String =
        "https://www.google.com/maps/search/?api=1&query=" + enc("$shopName $postalCode".trim())

    /** geo: search Android hands to any map app (Organic Maps, OsmAnd, Google Maps …); it searches around the current position. */
    fun geoSearchUri(shopName: String): String = "geo:0,0?q=" + enc(shopName)

    /** One Overpass query for all retailers: shops whose brand or name matches, within [radiusM] of the point. */
    fun overpassQuery(retailerIds: Collection<String>, lat: Double, lon: Double, radiusM: Int = DEFAULT_RADIUS_M): String? {
        val patterns = retailerIds.mapNotNull { brands[it]?.pattern }.distinct()
        if (patterns.isEmpty()) return null
        val around = "(around:$radiusM,${coord(lat)},${coord(lon)})"
        val parts = patterns.flatMap { p -> listOf("""nwr["shop"]["brand"~"$p",i]$around;""", """nwr["shop"]["name"~"$p",i]$around;""") }
        return "[out:json][timeout:25];(${parts.joinToString("")});out center tags;"
    }

    /** Nearest matching shop per retailer from an Overpass JSON answer. */
    fun nearestPerRetailer(overpassJson: String, retailerIds: Collection<String>, lat: Double, lon: Double): Map<String, StoreStop> {
        val elements = Json.parseToJsonElement(overpassJson).jsonObject["elements"]?.jsonArray.orEmpty()
        val result = linkedMapOf<String, StoreStop>()
        for (raw in elements) {
            val element = raw.jsonObject
            val tags = element["tags"]?.jsonObject ?: continue
            val (eLat, eLon) = position(element) ?: continue
            val shop = tags.text("shop") ?: continue
            val brand = tags.text("brand").orEmpty()
            val name = tags.text("name").orEmpty()
            for (id in retailerIds) {
                val rule = brands[id] ?: continue
                val regex = Regex(rule.pattern, RegexOption.IGNORE_CASE)
                // A tagged brand is authoritative; the name only counts for shops without a brand tag.
                val matches = if (brand.isNotBlank()) regex.containsMatchIn(brand) else regex.containsMatchIn(name)
                if (shop !in rule.shopTypes || !matches) continue
                val distance = distanceKm(lat, lon, eLat, eLon)
                if ((result[id]?.distanceKm ?: Double.MAX_VALUE) <= distance) continue
                result[id] = StoreStop(id, name.ifBlank { brand }, address(tags), eLat, eLon, distance)
            }
        }
        return result
    }

    /** Nearest-neighbour order starting at the given point: short enough for a shopping trip, fully deterministic. */
    fun order(stops: Collection<StoreStop>, lat: Double, lon: Double): List<StoreStop> {
        val left = stops.sortedBy { it.retailerId }.toMutableList()
        val ordered = mutableListOf<StoreStop>()
        var curLat = lat
        var curLon = lon
        while (left.isNotEmpty()) {
            val next = left.minWith(compareBy<StoreStop> { distanceKm(curLat, curLon, it.lat, it.lon) }.thenBy { it.retailerId })
            left.remove(next)
            ordered += next
            curLat = next.lat
            curLon = next.lon
        }
        return ordered
    }

    /** Google Maps directions from the current location through all stops (null without stops). */
    fun googleMapsUrl(stops: List<StoreStop>): String? {
        if (stops.isEmpty()) return null
        val limited = stops.take(MAX_STOPS)
        val destination = limited.last()
        val builder = StringBuilder("https://www.google.com/maps/dir/?api=1&travelmode=driving&destination=")
            .append(enc(point(destination)))
        val waypoints = limited.dropLast(1)
        if (waypoints.isNotEmpty()) builder.append("&waypoints=").append(enc(waypoints.joinToString("|") { point(it) }))
        return builder.toString()
    }

    /** geo: link for one stop; Android offers every installed map app (OsmAnd, Organic Maps, Google Maps …). */
    fun geoUri(stop: StoreStop): String {
        val label = "${stop.name} ${stop.address}".trim().replace(Regex("[()]"), " ")
        return "geo:${point(stop)}?q=${point(stop)}(${enc(label)})"
    }

    /** "Straße Nr, PLZ Ort" from a Nominatim reverse answer, or "" when it has no usable street. */
    fun addressFromNominatim(json: String): String =
        addressOf(runCatching { Json.parseToJsonElement(json).jsonObject["address"]?.jsonObject }.getOrNull())

    private fun addressOf(address: JsonObject?): String {
        if (address == null) return ""
        val road = address.text("road") ?: address.text("pedestrian") ?: address.text("square") ?: return ""
        val street = listOfNotNull(road, address.text("house_number")).joinToString(" ")
        val town = address.text("city") ?: address.text("town") ?: address.text("village") ?: address.text("municipality")
        val place = listOfNotNull(address.text("postcode"), town).joinToString(" ")
        return listOf(street, place).filter { it.isNotBlank() }.joinToString(", ")
    }

    /** Search box for a Nominatim shop search of about [radiusM] around the point (left,top,right,bottom). */
    fun viewbox(lat: Double, lon: Double, radiusM: Int = DEFAULT_RADIUS_M): String {
        val dLat = radiusM / 111_320.0
        val dLon = radiusM / (111_320.0 * kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.1))
        return listOf(lon - dLon, lat + dLat, lon + dLon, lat - dLat).joinToString(",") { coord(it) }
    }

    /**
     * Nearest matching shop of one retailer from a Nominatim search answer (fallback when Overpass is overloaded).
     * Nominatim has no brand tag in its default answer, so the name and the shop type have to match.
     */
    fun nearestFromNominatim(json: String, retailerId: String, lat: Double, lon: Double): StoreStop? {
        val rule = brands[retailerId] ?: return null
        val regex = Regex(rule.pattern, RegexOption.IGNORE_CASE)
        val rows = runCatching { Json.parseToJsonElement(json).jsonArray }.getOrNull() ?: return null
        return rows.mapNotNull { raw ->
            val row = raw as? JsonObject ?: return@mapNotNull null
            val category = row.text("category") ?: row.text("class")
            val type = row.text("type") ?: return@mapNotNull null
            val name = row.text("name") ?: return@mapNotNull null
            if (category != "shop" || type !in rule.shopTypes || !regex.containsMatchIn(name)) return@mapNotNull null
            val sLat = row.text("lat")?.toDoubleOrNull() ?: return@mapNotNull null
            val sLon = row.text("lon")?.toDoubleOrNull() ?: return@mapNotNull null
            if (sLat !in -90.0..90.0 || sLon !in -180.0..180.0) return@mapNotNull null
            StoreStop(retailerId, name, addressOf(row["address"] as? JsonObject), sLat, sLon, distanceKm(lat, lon, sLat, sLon))
        }.minByOrNull { it.distanceKm }
    }

    /**
     * Last resort without coordinates: one Google Maps route through all shops by name and postal code.
     * Google resolves each name itself; the order cannot be optimised without coordinates.
     */
    fun googleMapsTextRouteUrl(shopNames: List<String>, postalCode: String = ""): String? {
        val stops = shopNames.distinct().take(MAX_STOPS).map { "$it $postalCode".trim() }
        if (stops.isEmpty()) return null
        val builder = StringBuilder("https://www.google.com/maps/dir/?api=1&travelmode=driving&destination=").append(enc(stops.last()))
        if (stops.size > 1) builder.append("&waypoints=").append(enc(stops.dropLast(1).joinToString("|")))
        return builder.toString()
    }

    /** Stops as GeoJSON points for the in-app map; "n" is the visiting number shown in the marker. */
    fun stopsGeoJson(stops: List<StoreStop>): String = buildJsonObject {
        put("type", JsonPrimitive("FeatureCollection"))
        put("features", JsonArray(stops.mapIndexed { index, stop ->
            buildJsonObject {
                put("type", JsonPrimitive("Feature"))
                put("geometry", buildJsonObject {
                    put("type", JsonPrimitive("Point"))
                    // GeoJSON order is longitude, latitude.
                    put("coordinates", JsonArray(listOf(
                        JsonPrimitive(stop.lon), JsonPrimitive(stop.lat))))
                })
                put("properties", buildJsonObject {
                    put("n", JsonPrimitive((index + 1).toString()))
                    put("name", JsonPrimitive(stop.name))
                    put("retailer", JsonPrimitive(stop.retailerId))
                })
            }
        }))
    }.toString()

    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) * kotlin.math.sin(dLon / 2).let { it * it }
        return 6371.0 * 2 * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
    }

    private fun position(element: JsonObject): Pair<Double, Double>? {
        val source = element["center"]?.jsonObject ?: element
        val lat = source["lat"]?.jsonPrimitive?.doubleOrNull ?: return null
        val lon = source["lon"]?.jsonPrimitive?.doubleOrNull ?: return null
        return (lat to lon).takeIf { lat in -90.0..90.0 && lon in -180.0..180.0 }
    }

    private fun address(tags: JsonObject): String {
        val street = listOfNotNull(tags.text("addr:street"), tags.text("addr:housenumber")).joinToString(" ")
        val place = listOfNotNull(tags.text("addr:postcode"), tags.text("addr:city")).joinToString(" ")
        return listOf(street, place).filter { it.isNotBlank() }.joinToString(", ")
    }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    private fun point(stop: StoreStop) = "${coord(stop.lat)},${coord(stop.lon)}"
    private fun coord(value: Double) = String.format(java.util.Locale.ROOT, "%.6f", value)
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/**
 * Network part: postal code via Nominatim, shops via one Overpass query. Both are public OpenStreetMap services.
 * Public Overpass servers are often overloaded (HTTP 429/502/503/504): the query then moves on to the next public
 * instance. Results are kept in memory for [cacheMillis] so repeated taps do not query OpenStreetMap again.
 */
class StoreFinder(
    private val http: OkHttpClient,
    private val nominatimUrl: String = "https://nominatim.openstreetmap.org/search",
    private val overpassUrls: List<String> = DEFAULT_OVERPASS_URLS,
    private val cacheMillis: Long = 24 * 60 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis,
    private val reverseUrl: String = "https://nominatim.openstreetmap.org/reverse",
    /** Nominatim allows one request per second; tests pass 0. */
    private val nominatimPauseMillis: Long = 1100,
) {
    private val userAgent = "Korbunio-Plus (+https://github.com/gitgoodbadhub/Korbunio)"
    private val routes = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, StoreRouteResult>>()
    private val postalPoints = java.util.concurrent.ConcurrentHashMap<String, Pair<Double, Double>>()

    companion object {
        /** Public Overpass instances listed on the OpenStreetMap wiki; tried in this order. */
        val DEFAULT_OVERPASS_URLS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        )
        private val retryableCodes = setOf(429, 500, 502, 503, 504)
    }

    suspend fun find(postalCode: String, retailerIds: List<String>, radiusM: Int = StoreRoute.DEFAULT_RADIUS_M): StoreRouteResult =
        withContext(Dispatchers.IO) {
            require(Regex("\\d{5}").matches(postalCode)) { "Bitte zuerst eine fünfstellige Postleitzahl festlegen" }
            val known = retailerIds.distinct().filter { it in StoreRoute.brands }
            val unknown = retailerIds.distinct().filterNot { it in StoreRoute.brands }
            val key = "$postalCode|$radiusM|${known.sorted().joinToString(",")}"
            routes[key]?.takeIf { now() - it.first < cacheMillis }?.let { return@withContext it.second.copy(missingRetailerIds = it.second.missingRetailerIds + unknown) }
            val (lat, lon) = postalPoints[postalCode] ?: geocode(postalCode).also { postalPoints[postalCode] = it }
            val query = StoreRoute.overpassQuery(known, lat, lon, radiusM) ?: return@withContext StoreRouteResult(emptyList(), unknown)
            var overload: OverpassOverloadedException? = null
            val nearest = try {
                StoreRoute.nearestPerRetailer(overpass(query), known, lat, lon).toMutableMap()
            } catch (busy: OverpassOverloadedException) {
                overload = busy
                mutableMapOf()
            }
            // Second source: Nominatim shop search for retailers Overpass could not deliver (overload or no match).
            for (id in known.filterNot { it in nearest }.take(StoreRoute.MAX_STOPS)) {
                nominatimShop(id, lat, lon, radiusM)?.let { nearest[id] = it }
            }
            if (nearest.isEmpty() && overload != null) throw overload
            val ordered = StoreRoute.order(nearest.values, lat, lon).map { stop ->
                if (stop.address.isNotBlank()) stop else stop.copy(address = reverseAddress(stop.lat, stop.lon))
            }
            val result = StoreRouteResult(ordered, known.filterNot { it in nearest })
            routes[key] = now() to result
            result.copy(missingRetailerIds = result.missingRetailerIds + unknown)
        }

    /** Tries each public Overpass instance; only overload and connection errors move on to the next one. */
    private fun overpass(query: String): String {
        var lastProblem = "keine Antwort"
        for (url in overpassUrls) {
            val request = Request.Builder().url(url).header("User-Agent", userAgent)
                .post(FormBody.Builder().add("data", query).build()).build()
            try {
                http.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return response.body?.string().orEmpty()
                    if (response.code !in retryableCodes) error("Filialsuche (OpenStreetMap) HTTP ${response.code}")
                    lastProblem = "HTTP ${response.code}"
                }
            } catch (io: java.io.IOException) {
                lastProblem = io.javaClass.simpleName
            }
        }
        throw OverpassOverloadedException("Die OpenStreetMap-Filialsuche ist gerade überlastet ($lastProblem). Bitte in ein paar Minuten erneut versuchen.")
    }

    /** Nearest shop of one retailer via the Nominatim search (one request per second); failures give null. */
    private suspend fun nominatimShop(retailerId: String, lat: Double, lon: Double, radiusM: Int): StoreStop? {
        kotlinx.coroutines.delay(nominatimPauseMillis)
        return runCatching {
            val url = nominatimUrl.toHttpUrl().newBuilder()
                .addQueryParameter("q", StoreRoute.searchName(retailerId)).addQueryParameter("format", "jsonv2")
                .addQueryParameter("limit", "20").addQueryParameter("addressdetails", "1")
                .addQueryParameter("bounded", "1").addQueryParameter("viewbox", StoreRoute.viewbox(lat, lon, radiusM))
                .addQueryParameter("countrycodes", "de").build()
            http.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { response ->
                if (!response.isSuccessful) null
                else StoreRoute.nearestFromNominatim(response.body?.string().orEmpty(), retailerId, lat, lon)
            }
        }.getOrNull()
    }

    /** Street address for a shop without address tags; failures only leave the address empty. */
    private suspend fun reverseAddress(lat: Double, lon: Double): String {
        kotlinx.coroutines.delay(nominatimPauseMillis)
        return runCatching { reverseAddressNow(lat, lon) }.getOrDefault("")
    }

    private fun reverseAddressNow(lat: Double, lon: Double): String {
        val url = reverseUrl.toHttpUrl().newBuilder()
            .addQueryParameter("lat", lat.toString()).addQueryParameter("lon", lon.toString())
            .addQueryParameter("format", "jsonv2").addQueryParameter("zoom", "18").addQueryParameter("addressdetails", "1").build()
        return http.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { response ->
            if (!response.isSuccessful) "" else StoreRoute.addressFromNominatim(response.body?.string().orEmpty())
        }
    }

    private fun geocode(postalCode: String): Pair<Double, Double> {
        val url = nominatimUrl.toHttpUrl().newBuilder()
            .addQueryParameter("postalcode", postalCode).addQueryParameter("country", "de")
            .addQueryParameter("format", "jsonv2").addQueryParameter("limit", "1").build()
        val body = http.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use { response ->
            check(response.isSuccessful) { "PLZ-Suche (OpenStreetMap) HTTP ${response.code}" }
            response.body?.string().orEmpty()
        }
        val row = Json.parseToJsonElement(body).jsonArray.firstOrNull()?.jsonObject ?: error("Postleitzahl $postalCode nicht gefunden")
        val lat = row["lat"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: error("PLZ-Koordinaten fehlen")
        val lon = row["lon"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: error("PLZ-Koordinaten fehlen")
        check(lat in -90.0..90.0 && lon in -180.0..180.0) { "PLZ-Koordinaten ungültig" }
        return lat to lon
    }
}

/** All public Overpass servers answered with overload or did not answer. */
class OverpassOverloadedException(message: String) : IllegalStateException(message)
