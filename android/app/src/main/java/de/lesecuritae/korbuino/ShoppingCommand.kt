package de.lesecuritae.korbuino

/**
 * Rule-based interpretation of a spoken or typed shopping command ("Pseudo-KI", Korbunio Plus).
 *
 * Works offline and deterministically: it recognises the command, the products, quantities,
 * a price limit per product and a budget for the whole purchase. It never invents prices;
 * products are only matched against the loaded offers afterwards (see [OfferSearch]).
 */
object ShoppingCommand {
    data class Item(val name: String, val quantity: Int = 1, val maxPriceCents: Int? = null)

    sealed interface Command {
        data class Add(val items: List<Item>, val budgetCents: Int? = null) : Command
        data class Search(val query: String, val maxPriceCents: Int? = null) : Command
        data class Remove(val names: List<String>) : Command
        data object ShowList : Command
        data object Route : Command
        data class Unknown(val text: String) : Command
    }

    private val numberWords = mapOf(
        "ein" to 1, "eine" to 1, "einen" to 1, "einer" to 1, "eins" to 1, "zwei" to 2, "drei" to 3, "vier" to 4,
        "fünf" to 5, "fuenf" to 5, "sechs" to 6, "sieben" to 7, "acht" to 8, "neun" to 9, "zehn" to 10, "elf" to 11,
        "zwölf" to 12, "zwoelf" to 12, "zwanzig" to 20, "dreißig" to 30, "dreissig" to 30, "vierzig" to 40,
        "fünfzig" to 50, "fuenfzig" to 50, "sechzig" to 60, "siebzig" to 70, "achtzig" to 80, "neunzig" to 90,
        "hundert" to 100, "dutzend" to 12, "paar" to 2,
    )
    private val packWords = "x|mal|stück|stueck|stk|packung|packungen|pack|packs|flasche|flaschen|dose|dosen|becher|" +
        "tüte|tüten|tuete|tueten|beutel|glas|gläser|glaeser|kiste|kisten|rolle|rollen|tafel|tafeln|kilo|liter"

    private val removeVerbs = Regex("""\b(lösch\w*|loesch\w*|entfern\w*|streich\w*|nimm|wegnehmen|raus)\b""")
    private val routeWords = Regex("""\b(route|navigation|navigier\w*|wo muss ich hin|welche (läden|laeden|märkte|maerkte|geschäfte|geschaefte)|wegbeschreibung)\b""")
    private val listWords = Regex("""\b(zeig\w*|was steht|lies|vorlesen)\b.*\b(liste|einkaufsliste|einkaufszettel)\b""")
    private val addWords = Regex("""\b(füg\w*|fueg\w*|setz\w*|pack\w*|schreib\w*|notier\w*|brauche|brauchen|benötige|benoetige|kaufen|hinzu|dazu|auf die liste|auf die einkaufsliste|auf den einkaufszettel)\b""")
    private val searchWords = Regex("""\b(zeig\w*|such\w*|finde\w*|wo gibt es|wo gibts|was kostet|was kosten|angebot\w*|gibt es)\b""")

    private val numberAlternatives = numberWords.keys.sortedByDescending { it.length }.joinToString("|").let { "(?:$it)(?:und(?:$it))?" }

    /** "neunundneunzig" → 99, "zwei" → 2, "17" → 17. */
    fun number(word: String): Int? {
        word.toIntOrNull()?.let { return it }
        numberWords[word]?.let { return it }
        val parts = word.split("und")
        if (parts.size == 2) {
            val ones = numberWords[parts[0]]; val tens = numberWords[parts[1]]
            if (ones != null && tens != null && ones in 1..9 && tens in 20..90) return tens + ones
        }
        return null
    }
    private val amount = """(\d+(?:[.,]\d{1,2})?|$numberAlternatives)(?:\s*(?:€|euro|eur))?(?:\s+(\d{1,2}|$numberAlternatives)\b)?"""
    private val budget = Regex("""\b(?:insgesamt|gesamt|zusammen|budget|alles zusammen)\s*(?:von|für|fuer)?\s*(?:höchstens|hoechstens|maximal|max|unter|bis|bis zu|nicht mehr als)?\s*$amount\s*(?:€|euro|eur)?""")
    private val limit = Regex("""\b(?:für|fuer)?\s*(?:höchstens|hoechstens|maximal|max\.?|unter|bis zu|bis|nicht mehr als|nicht über|nicht ueber)\s+$amount\s*(?:€|euro|eur)?""")

    fun parse(text: String): Command {
        var t = " " + text.lowercase().replace('\n', ' ').replace(Regex("""[!?;:"„“]"""), " ") + " "
        if (t.isBlank()) return Command.Unknown(text)
        val budgetCents = budget.find(t)?.let { match -> cents(match.groupValues[1], match.groupValues[2]).also { t = t.replace(match.value, " ") } }

        return when {
            removeVerbs.containsMatchIn(t) && !addWords.containsMatchIn(t.replace(Regex("""\bauf die (einkaufs)?liste\b"""), "")) ->
                Command.Remove(items(t).map { it.name }.ifEmpty { return Command.Unknown(text) })
            routeWords.containsMatchIn(t) -> Command.Route
            listWords.containsMatchIn(t) -> Command.ShowList
            addWords.containsMatchIn(t) -> {
                val items = items(t)
                if (items.isEmpty()) Command.Unknown(text) else Command.Add(items, budgetCents)
            }
            searchWords.containsMatchIn(t) -> {
                val item = items(t).firstOrNull() ?: return Command.Unknown(text)
                Command.Search(item.name, item.maxPriceCents)
            }
            else -> {
                // A bare product list ("Milch, Butter und Eier") is read as "add to the list".
                val items = items(t)
                if (items.isEmpty()) Command.Unknown(text) else Command.Add(items, budgetCents)
            }
        }
    }

    private val sentenceWords = Regex(
        """\b(ich|wir|mache|machen|koche|kochen|backe|backen|grille|grillen|für|fuer|zum|zur|mit|gäste|gaeste|party|""" +
            """frühstück|fruehstueck|abendessen|mittagessen|wochenende|morgen|heute|rezept|geburtstag|feier|picknick)\b"""
    )

    /**
     * True when the rules probably did not get the meaning: nothing understood, or a whole sentence without
     * a command word ("Ich mache Pfannkuchen für vier") that would otherwise be read as a product list.
     * A plain list ("Milch, Butter und Eier") stays with the rules.
     */
    fun needsHelp(text: String): Boolean {
        val command = parse(text)
        if (command is Command.Unknown) return text.isNotBlank()
        val t = " " + text.lowercase() + " "
        val hasCommandWord = removeVerbs.containsMatchIn(t) || routeWords.containsMatchIn(t) || listWords.containsMatchIn(t) ||
            addWords.containsMatchIn(t) || searchWords.containsMatchIn(t)
        if (hasCommandWord) return false
        val parts = t.split(Regex("""\s*(?:,|\bund\b|\bsowie\b)\s*""")).filter { it.isNotBlank() }
        return sentenceWords.containsMatchIn(t) || parts.any { it.trim().split(Regex("""\s+""")).size > 3 }
    }

    private val fillers = Regex(
        """\b(bitte|mal|noch|doch|auch|mir|mich|uns|ich|wir|du|kannst|könntest|koenntest|würdest|wuerdest|möchte|moechte|will|""" +
            """hätte|haette|gern|gerne|bitte|zur|zum|die|den|der|das|dem|des|auf|in|im|von|vom|liste|einkaufsliste|einkaufszettel|""" +
            """füg\w*|fueg\w*|setz\w*|pack|packe|schreib\w*|notier\w*|brauche|brauchen|benötige|benoetige|kaufen|hinzu|dazu|""" +
            """zeig\w*|such\w*|finde\w*|wo|gibt|gibts|es|was|kostet|kosten|angebot\w*|lösch\w*|loesch\w*|entfern\w*|streich\w*|nimm|wegnehmen|raus|""" +
            """günstig\w*|guenstig\w*|billig\w*|aktuell\w*|im angebot)\b"""
    )

    /** Splits the product part into items with quantity and price limit. */
    fun items(text: String): List<Item> {
        val parts = text.split(Regex("""\s*(?:,|\bund\b|\bsowie\b|\bplus\b|\bdazu\b|\bausserdem\b|\baußerdem\b)\s*"""))
        val result = mutableListOf<Item>()
        for (raw in parts) {
            val parsed = item(" $raw ")
            when {
                parsed.item != null -> result += parsed.item
                // "Kaffee, maximal 5 Euro": a limit on its own belongs to the product before it.
                parsed.maxPriceCents != null && result.isNotEmpty() && result.last().maxPriceCents == null ->
                    result[result.lastIndex] = result.last().copy(maxPriceCents = parsed.maxPriceCents)
            }
        }
        return result
    }

    private data class Parsed(val item: Item?, val maxPriceCents: Int?)

    private fun item(raw: String): Parsed {
        var t = raw
        val maxPrice = limit.find(t)?.let { m -> cents(m.groupValues[1], m.groupValues[2]).also { t = t.replace(m.value, " ") } }
        var quantity = 1
        Regex("""\b(\d{1,3}|${numberWords.keys.joinToString("|")})\s*(?:$packWords)?\b\s""").find(t)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: numberWords[m.groupValues[1]]
            // "ein"/"eine" are articles as often as numbers; they only count together with a pack word.
            val isArticle = m.groupValues[1] in setOf("ein", "eine", "einen", "einer") && !Regex(packWords).containsMatchIn(m.value)
            if (n != null && n in 1..99 && !isArticle) { quantity = n; t = t.replaceFirst(m.value, " ") }
            else if (isArticle) t = t.replaceFirst(m.value, " ")
        }
        Regex("""\b(\d{1,2})\s*x\b""").find(t)?.let { m -> quantity = m.groupValues[1].toInt(); t = t.replace(m.value, " ") }
        val name = t.replace(fillers, " ").replace(Regex("""[.,]"""), " ").replace(Regex("""\s+"""), " ").trim()
        if (name.length < 2) return Parsed(null, maxPrice)
        return Parsed(Item(name, quantity.coerceIn(1, 99), maxPrice), maxPrice)
    }

    /** "2,50" / "2" "50" / "zwei" "fünfzig" / "3" → cents. */
    fun cents(euros: String, extra: String = ""): Int? {
        val main = euros.replace(',', '.').toBigDecimalOrNull()
            ?: number(euros)?.toBigDecimal() ?: return null
        val centsPart = extra.takeIf { it.isNotBlank() }?.let { number(it) }
        val total = if (centsPart != null && main.scale() <= 0 && centsPart in 0..99) main.toInt() * 100 + centsPart
        else main.movePointRight(2).toInt()
        return total.takeIf { it in 1..1_000_000 }
    }
}
