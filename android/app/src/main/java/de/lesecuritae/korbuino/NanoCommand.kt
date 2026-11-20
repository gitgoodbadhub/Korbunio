package de.lesecuritae.korbuino

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Prompt and answer format for Gemini Nano in the voice assistant.
 *
 * The model only translates free speech into a small JSON command; prices and offers still come
 * exclusively from the loaded offers ([VoiceAssistant]). Anything malformed is rejected, and the
 * rule-based [ShoppingCommand] stays the fallback.
 */
object NanoCommand {
    private const val MAX_ITEMS = 30

    fun prompt(text: String): String = """
Du bist ein Einkaufsassistent. Wandle die Anfrage des Nutzers in genau ein JSON-Objekt um.
Format: {"aktion":"hinzufuegen|suchen|entfernen|liste|route","produkte":[{"name":"Milch","menge":1,"max_euro":null}],"budget_euro":null}
Regeln:
- Produktnamen kurz, allgemein und auf Deutsch, z. B. "Toilettenpapier", "Joghurt". Marke und Größe nur, wenn genannt.
- Nennt der Nutzer ein Gericht oder Anlass, liste die üblichen Zutaten zum Einkaufen.
- "menge" ist die Stückzahl, "max_euro" die Preisgrenze je Produkt, "budget_euro" die Grenze für alles.
- Erfinde keine Preise. Antworte nur mit dem JSON, ohne Erklärung.
Anfrage: ${text.replace('\n', ' ').take(300)}
""".trim()

    fun parse(answer: String): ShoppingCommand.Command? {
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val root = runCatching { Json.parseToJsonElement(answer.substring(start, end + 1)) }.getOrNull() as? JsonObject ?: return null
        val items = (root["produkte"] as? JsonArray).orEmpty().mapNotNull { item(it) }.take(MAX_ITEMS)
        val budget = euros(root["budget_euro"])
        return when (text(root["aktion"])?.lowercase()?.replace("ü", "ue")) {
            "hinzufuegen", "add" -> if (items.isEmpty()) null else ShoppingCommand.Command.Add(items, budget)
            "suchen", "search" -> items.firstOrNull()?.let { ShoppingCommand.Command.Search(it.name, it.maxPriceCents) }
            "entfernen", "remove" -> if (items.isEmpty()) null else ShoppingCommand.Command.Remove(items.map { it.name })
            "liste", "list" -> ShoppingCommand.Command.ShowList
            "route" -> ShoppingCommand.Command.Route
            else -> null
        }
    }

    private fun item(element: JsonElement): ShoppingCommand.Item? {
        val obj = element as? JsonObject ?: return null
        val name = text(obj["name"])?.trim()?.lowercase()?.replace(Regex("""\s+"""), " ")
            ?.takeIf { it.length in 2..60 } ?: return null
        val quantity = (obj["menge"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()?.coerceIn(1, 99) ?: 1
        return ShoppingCommand.Item(name, quantity, euros(obj["max_euro"]))
    }

    private fun text(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    private fun euros(element: JsonElement?): Int? {
        val value = (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.replace(',', '.')?.toBigDecimalOrNull() ?: return null
        return value.movePointRight(2).toInt().takeIf { it in 1..1_000_000 }
    }
}
