package de.lesecuritae.korbuino

import java.text.Normalizer

/**
 * Ranked text search for the offer list (Korbunio Plus).
 *
 * Every query word must be found (as before), but each hit is weighted:
 * a whole word in the product name scores highest, the head of a German compound
 * ("Erdbeer**joghurt**") almost as high, the front of a compound ("**Joghurt**-Gums")
 * and mentions in the description low. Synonyms ("Klopapier" → "Toilettenpapier"),
 * word stems ("Erdbeere" → "Erdbeer…"), small typos and quantities written in different
 * ways ("50g", "50 g", "50 Gramm") are recognised. Pure Kotlin, no Android, fully unit-testable.
 */
object OfferSearch {
    /**
     * Everyday words that retailers name differently. Taken from the original Korbuino MCP server
     * (src/supermarkt/mcp_server.py, SYNONYMS) and extended; keys and values in normalised form.
     */
    private val synonyms: Map<String, List<String>> = mapOf(
        "osterhase" to listOf("schokohase", "osterhasen"),
        "schokohase" to listOf("osterhase", "osterhasen"),
        "weihnachtsmann" to listOf("schokoweihnachtsmann", "nikolaus"),
        "klopapier" to listOf("toilettenpapier", "hygienepapier", "wc papier"),
        "toilettenpapier" to listOf("klopapier", "hygienepapier", "wc papier"),
        "klorolle" to listOf("toilettenpapier"),
        "brotchen" to listOf("semmel", "schrippe", "broetchen"),
        "hackfleisch" to listOf("gehacktes", "hack"),
        "sprudel" to listOf("mineralwasser", "sprudelwasser"),
        "mineralwasser" to listOf("sprudel"),
        "joghurt" to listOf("jogurt", "yoghurt"),
        "jogurt" to listOf("joghurt", "yoghurt"),
        "yoghurt" to listOf("joghurt", "jogurt"),
        "ketchup" to listOf("catchup", "tomatenketchup"),
        "mayo" to listOf("mayonnaise"),
        "pommes" to listOf("pommes frites", "fritten"),
        "eis" to listOf("speiseeis", "eiscreme"),
        "spuli" to listOf("spulmittel", "geschirrspulmittel"),
        "spulmittel" to listOf("geschirrspulmittel", "handspulmittel"),
        "waschmittel" to listOf("vollwaschmittel", "colorwaschmittel", "feinwaschmittel"),
        "cola" to listOf("coca cola", "pepsi"),
        "nutella" to listOf("nuss nougat creme", "nussnougatcreme"),
        "kaffeebohnen" to listOf("bohnenkaffee", "ganze bohne"),
        "schmelzkase" to listOf("streichkase"),
        "frischkase" to listOf("doppelrahmfrischkase", "streichkase"),
        "tiefkuhlpizza" to listOf("pizza", "steinofenpizza"),
        "chips" to listOf("kartoffelchips", "kartoffelsnack"),
        "kuchenrolle" to listOf("kuchenpapier", "kuchentucher", "haushaltsrolle"),
        "taschentucher" to listOf("tempo", "papiertaschentucher"),
        "zahnpasta" to listOf("zahncreme"),
        "zahncreme" to listOf("zahnpasta"),
        "duschgel" to listOf("duschcreme", "dusche"),
        "rasierer" to listOf("rasierklingen", "rasierapparat"),
        "windeln" to listOf("baby windeln", "pants"),
        "sekt" to listOf("schaumwein", "prosecco"),
        "bier" to listOf("pils", "pilsener", "helles", "weizen"),
        "limo" to listOf("limonade"),
        "butter" to listOf("markenbutter", "suessrahmbutter", "susrahmbutter"),
        "kaffee" to listOf("rostkaffee", "filterkaffee"),
        "kekse" to listOf("gebaeck", "geback", "platzchen"),
    )

    /** Units recognised in queries and products; values map spellings onto one canonical unit. */
    private val units = mapOf(
        "g" to "g", "gr" to "g", "gramm" to "g", "kg" to "kg", "kilo" to "kg", "kilogramm" to "kg",
        "ml" to "ml", "milliliter" to "ml", "cl" to "cl", "l" to "l", "ltr" to "l", "liter" to "l",
        "st" to "st", "stk" to "st", "stuck" to "st", "stueck" to "st", "er" to "st", "rollen" to "rollen", "rolle" to "rollen",
    )
    private val quantity = Regex("""(\d+(?:[.,]\d+)?)\s*(kilogramm|milliliter|gramm|kilo|liter|stueck|stuck|rollen|rolle|ltr|stk|kg|ml|cl|gr|st|er|g|l)\b""")

    /** A prepared query: words to find (each with alternatives) and wanted quantities. */
    data class Query(val terms: List<List<String>>, val quantities: Set<String>, val raw: String) {
        val isEmpty get() = terms.isEmpty() && quantities.isEmpty()
    }

    /** An offer prepared for searching; build it once per loaded offer, not per keystroke. */
    data class Indexed(
        /** Words that say what the product is (the last part of "Joghurt-Gums" is "gums"). */
        val nameTokens: List<String>,
        /** Front parts of hyphenated words ("joghurt" in "Joghurt-Gums"): they only describe the product. */
        val nameModifiers: List<String>,
        /** All name parts in order, for phrase synonyms such as "wc papier". */
        val nameText: String,
        val brandTokens: List<String>,
        val description: String,
        val quantities: Set<String>,
    )

    fun normalize(value: String): String =
        Normalizer.normalize(value.lowercase().replace("ß", "ss"), Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .replace("[^a-z0-9.,]+".toRegex(), " ")
            .trim()

    private fun words(normalized: String): List<String> =
        normalized.replace("[.,]".toRegex(), " ").split(' ').filter { it.isNotBlank() }

    /** "50 g", "50g", "50 Gramm" → "50g"; "1,5 l" → "1.5l". */
    fun quantities(normalized: String): Set<String> = quantity.findAll(normalized).mapNotNull { match ->
        val unit = units[match.groupValues[2]] ?: return@mapNotNull null
        val number = match.groupValues[1].replace(',', '.').toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: return@mapNotNull null
        "$number$unit"
    }.toSet()

    fun prepare(query: String): Query {
        val normalized = normalize(query)
        val wanted = quantities(normalized)
        val withoutQuantities = quantity.replace(normalized, " ")
        val terms = words(withoutQuantities).filter { it.length >= 2 }.distinct().map { word ->
            (listOf(word) + synonyms[word].orEmpty()).distinct()
        }
        return Query(terms, wanted, query.trim())
    }

    /**
     * The query as typed, plus the words written together when they are two or three:
     * "Puten Schnitzel" also finds "Putenschnitzel" (and the other way round, see [termScore]).
     */
    fun prepareAll(query: String): List<Query> {
        val first = prepare(query)
        val words = first.terms.map { it.first() }
        if (words.size !in 2..3 || words.any { it.any(Char::isDigit) }) return listOf(first)
        val joined = words.joinToString("")
        return listOf(first, Query(listOf((listOf(joined) + synonyms[joined].orEmpty()).distinct()), first.quantities, first.raw))
    }

    /** Best score over [prepareAll]'s spellings. */
    fun score(queries: List<Query>, offer: Indexed): Int = queries.maxOfOrNull { score(it, offer) } ?: 0

    /** Name parts written together in pairs: "Puten-Schnitzel" / "Puten Schnitzel" → "putenschnitzel". */
    private fun joinedPairs(offer: Indexed): List<String> =
        offer.nameText.split(' ').filter { it.isNotBlank() }.zipWithNext { a, b -> a + b }

    fun index(name: String, brand: String, description: String, packSize: String?): Indexed {
        val all = normalize("$name $description ${packSize.orEmpty()}")
        val heads = mutableListOf<String>()
        val modifiers = mutableListOf<String>()
        val parts = mutableListOf<String>()
        // Keep hyphens for this split: "Joghurt-Gums" is gums described by joghurt, "Coca-Cola" is cola.
        Normalizer.normalize(name.lowercase().replace("ß", "ss"), Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "")
            .replace("[^a-z0-9-]+".toRegex(), " ")
            .split(' ').filter { it.isNotBlank() }
            .forEach { chunk ->
                val pieces = chunk.split('-').filter { it.isNotBlank() }
                pieces.forEachIndexed { i, piece -> if (i == pieces.lastIndex) heads += piece else modifiers += piece }
                parts += pieces
            }
        return Indexed(
            nameTokens = heads,
            nameModifiers = modifiers,
            nameText = parts.joinToString(" "),
            brandTokens = words(normalize(brand)),
            description = words(normalize(description)).joinToString(" "),
            quantities = quantities(all),
        )
    }

    fun index(display: OfferDisplay): Indexed =
        index(display.productName, display.brand, display.offer.description, display.offer.packSize)

    /** 0 = not a hit; otherwise higher is a better match. */
    fun score(query: Query, offer: Indexed): Int {
        if (query.isEmpty) return 0
        var total = 0
        var allInName = true
        for (alternatives in query.terms) {
            val best = alternatives.maxOf { termScore(it, offer) }
            if (best <= 0) return 0
            if (best < NAME_THRESHOLD) allInName = false
            total += best
        }
        if (query.terms.isNotEmpty() && allInName) total += 5
        // Quantities only rank: a matching size goes up, a different size of the same product stays visible.
        if (query.quantities.isNotEmpty()) {
            total += if (query.quantities.all { it in offer.quantities }) 6 else 0
            if (query.terms.isEmpty() && query.quantities.none { it in offer.quantities }) return 0
        }
        return total
    }

    private const val NAME_THRESHOLD = 6

    private fun termScore(term: String, offer: Indexed): Int {
        // Multi-word synonyms such as "wc papier" match as a phrase in the name.
        if (' ' in term) return if (offer.nameText.contains(term)) 9 else 0
        var best = 0
        for (token in offer.nameTokens) best = maxOf(best, tokenScore(term, token))
        if (best < 10 && joinedPairs(offer).any { it == term }) best = 10
        for (token in offer.nameModifiers) if (token == term || token == stem(term) || token.endsWith(term)) best = maxOf(best, 3)
        for (token in offer.brandTokens) if (token == term) best = maxOf(best, 8)
        if (best == 0 && offer.description.split(' ').any { it.contains(term) }) best = 2
        if (best == 0 && term.length >= 5) {
            val allowed = if (term.length >= 9) 2 else 1
            if (offer.nameTokens.any { it.length >= 4 && distance(term, it, allowed) <= allowed }) best = 6
        }
        return best
    }

    private fun tokenScore(term: String, token: String): Int {
        if (token == term) return 10
        val stem = stem(term)
        if (token.endsWith(term) && token.length > term.length) return 8   // Erdbeer|joghurt: head of the compound
        if (stem != term && token == stem) return 9                         // Erdbeere ~ Erdbeer
        if (token.startsWith(term)) return if (token.length - term.length <= 2) 9 else 3 // Joghurt|s vs Joghurt|becher
        if (stem.length >= 4 && token.startsWith(stem)) return 4            // Erdbeer|joghurt for "Erdbeere"
        if (stem.length >= 4 && token.endsWith(stem)) return 7
        if (term.length >= 4 && token.contains(term)) return 2
        return 0
    }

    /**
     * Prefixes that describe a kind of the same product: "Voll|milch" and "Mineral|wasser" are milk and water,
     * "Müller|milch" (a milk drink) and "Meer|wasser" (nasal spray) are not.
     */
    private val neutralPrefixes = setOf(
        "voll", "frisch", "fettarm", "fettarme", "h", "bio", "trink", "weide", "alpen", "land", "heu", "haltbar",
        "laktosefrei", "mineral", "tafel", "quell", "heil", "sprudel", "natur", "vollkorn", "mehrkorn", "weizen",
        "roggen", "dinkel", "misch", "toast", "kaese", "kase", "marken", "sussrahm", "suessrahm", "sauerrahm",
        "rinder", "schweine", "hahnchen", "speise", "tiefkuhl",
    )

    /** True when [token] names the product [term] itself (or a neutral kind of it), not something that only contains the word. */
    fun isKind(term: String, token: String): Boolean {
        if (token == term) return true
        val stem = stem(term)
        if (token == stem || stem(token) == stem) return true
        if (token.startsWith(term) && token.length - term.length <= 2) return true // plural: Eier, Brötchen
        if (token.endsWith(term) && token.length > term.length) return token.dropLast(term.length).trimEnd('-') in neutralPrefixes
        return false
    }

    /**
     * True when the offer is the asked product itself: at least one query word names a head word of the
     * product name (see [isKind]); brand, size and other words may match elsewhere.
     */
    fun isSameProduct(query: Query, offer: Indexed): Boolean = query.terms.any { alternatives ->
        alternatives.any { term ->
            if (' ' in term) offer.nameText.contains(term)
            else offer.nameTokens.any { isKind(term, it) } || joinedPairs(offer).any { isKind(term, it) }
        }
    }

    /** Light German stemming for plural and inflection endings; keeps at least four letters. */
    fun stem(word: String): String {
        for (ending in listOf("en", "er", "e", "n", "s")) {
            if (word.length - ending.length >= 4 && word.endsWith(ending)) return word.dropLast(ending.length)
        }
        return word
    }

    /** Levenshtein distance, stopping early once it exceeds [limit]. */
    fun distance(a: String, b: String, limit: Int): Int {
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            var rowMin = current[0]
            for (j in 1..b.length) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                rowMin = minOf(rowMin, current[j])
            }
            if (rowMin > limit) return limit + 1
            previous = current
        }
        return previous[b.length]
    }

    /**
     * "Meinten Sie …?" for a query without hits: replaces each unknown word by a synonym or the closest
     * word of the loaded product names. Null when nothing sensible is found.
     */
    fun suggestion(query: String, names: Collection<String>): String? {
        val vocabulary = names.asSequence().flatMap { words(normalize(it)).asSequence() }.filter { it.length >= 4 }.toSet()
        val original = words(normalize(query)).filter { it.length >= 2 }
        if (original.isEmpty()) return null
        var changed = false
        val replaced = original.map { word ->
            if (word in vocabulary || vocabulary.any { it.endsWith(word) }) return@map word
            synonyms[word]?.firstOrNull { syn -> syn.split(' ').all { part -> vocabulary.any { it.contains(part) } } }?.let { changed = true; return@map it }
            val allowed = if (word.length >= 9) 2 else 1
            vocabulary.filter { word.length >= 4 && distance(word, it, allowed) <= allowed }
                .minByOrNull { distance(word, it, allowed) }?.let { changed = true; return@map it }
            word
        }
        return if (changed) replaced.joinToString(" ") else null
    }
}
