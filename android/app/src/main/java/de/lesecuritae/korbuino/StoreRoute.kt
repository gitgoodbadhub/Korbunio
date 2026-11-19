package de.lesecuritae.korbuino

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
            val nearest = StoreRoute.nearestPerRetailer(overpass(query), known, lat, lon)
            val result = StoreRouteResult(StoreRoute.order(nearest.values, lat, lon), known.filterNot { it in nearest })
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
        error("Die OpenStreetMap-Filialsuche ist gerade überlastet ($lastProblem). Bitte in ein paar Minuten erneut versuchen.")
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
