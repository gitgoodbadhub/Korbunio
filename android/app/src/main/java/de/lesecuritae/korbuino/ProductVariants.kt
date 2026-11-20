package de.lesecuritae.korbuino

/**
 * Sorts of common products and the words people use for them (Korbunio Plus voice assistant).
 *
 * "Wasser … feinperlig" means medium water, "stilles Wasser" still water. The words also find the
 * sort in offer names ("Gerolsteiner Medium", "Saskia still"). Purely local, no model needed.
 */
object ProductVariants {
    data class Variant(val id: String, val label: String, val words: List<String>)

    private fun v(id: String, label: String, vararg words: String) =
        Variant(id, label, words.map { OfferSearch.normalize(it) }.distinct())

    private val table: Map<String, List<Variant>> = mapOf(
        "wasser" to listOf(
            v("still", "still", "still", "stilles", "stille", "ohne kohlensäure", "naturell", "naturelle", "natürlich", "pur"),
            v("medium", "medium", "medium", "feinperlig", "feinperliges", "leicht", "leichtes", "mild", "sanft", "wenig kohlensäure", "spritzig leicht"),
            v("classic", "classic", "classic", "klassisch", "spritzig", "spritziges", "sprudel", "sprudelnd", "mit kohlensäure", "kohlensäure", "prickelnd"),
        ),
        "milch" to listOf(
            v("voll", "Vollmilch", "vollmilch", "voll", "3,5", "3,8", "3.5", "3.8"),
            v("fettarm", "fettarm", "fettarm", "fettarme", "fettarmen", "1,5", "1.5", "teilentrahmt"),
            v("laktosefrei", "laktosefrei", "laktosefrei", "laktosefreie", "lactosefrei", "lactosefreie"),
            v("haltbar", "H-Milch", "h milch", "h-milch", "haltbar", "haltbare", "länger haltbar"),
        ),
        "brot" to listOf(
            v("vollkorn", "Vollkorn", "vollkorn", "vollkornbrot"),
            v("toast", "Toast", "toast", "toastbrot"),
            v("misch", "Mischbrot", "misch", "mischbrot", "weizenmisch", "roggenmisch"),
        ),
    )

    private val aliases = mapOf(
        "mineralwasser" to "wasser", "tafelwasser" to "wasser", "sprudel" to "wasser", "sprudelwasser" to "wasser",
        "vollmilch" to "milch", "frischmilch" to "milch", "hmilch" to "milch", "trinkmilch" to "milch",
        "toastbrot" to "brot", "vollkornbrot" to "brot", "mischbrot" to "brot",
    )

    private fun words(text: String) =
        OfferSearch.normalize(text).replace("-", " ").split(' ').map { it.trim('.', ',') }.filter { it.isNotBlank() }

    /** The product a wish is about ("stilles Wasser" → "wasser"), if sorts are known for it. */
    fun key(text: String): String? = words(text).firstNotNullOfOrNull { word ->
        when {
            word in table -> word
            aliases[word] != null -> aliases[word]
            else -> table.keys.firstOrNull { OfferSearch.stem(word) == OfferSearch.stem(it) }
        }
    }

    fun variants(key: String?): List<Variant> = table[key].orEmpty()

    fun byId(key: String?, id: String?): Variant? = variants(key).firstOrNull { it.id == id }

    private fun phrase(text: String) = words(text).joinToString(" ")

    private fun padded(text: String) = " " + phrase(text) + " "

    private fun phrases(variant: Variant) = variant.words.map { phrase(it) }.filter { it.isNotBlank() }

    /** The sort named in [text], the longest phrase first ("ohne kohlensäure" before "kohlensäure"). */
    fun detect(key: String?, text: String): Variant? {
        val padded = padded(text)
        return variants(key).flatMap { variant -> phrases(variant).map { it to variant } }
            .sortedByDescending { it.first.length }
            .firstOrNull { (word, _) -> padded.contains(" $word ") }?.second
    }

    /** [text] without the sort words ("stilles Wasser" → "wasser"). */
    fun strip(key: String?, text: String): String {
        var padded = padded(text)
        variants(key).flatMap { phrases(it) }.sortedByDescending { it.length }
            .forEach { word -> while (padded.contains(" $word ")) padded = padded.replace(" $word ", " ") }
        return padded.trim().replace(Regex("""\s+"""), " ")
    }

    /** True when [text] says nothing but a sort of [key] ("feinperlig" after "Wasser"). */
    fun isOnlyVariant(key: String?, text: String): Boolean =
        key != null && detect(key, text) != null && strip(key, text).isBlank()

    /** True when an offer is of this sort, judged by its name, description and size. */
    fun matches(variant: Variant, offerText: String): Boolean {
        val padded = padded(offerText)
        return phrases(variant).any { padded.contains(" $it ") }
    }
}
