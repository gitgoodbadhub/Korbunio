package de.lesecuritae.korbuino

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * What the voice assistant learned from the user's choices: per product word the sort and the product
 * last taken ("wasser" → medium, "Gerolsteiner Medium"). Stored only on the phone.
 */
object VoiceMemory {
    @Serializable
    data class Entry(val variant: String? = null, val product: String? = null)

    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_ENTRIES = 300

    fun encode(memory: Map<String, Entry>): String = json.encodeToString(memory)

    fun decode(text: String?): Map<String, Entry> =
        text?.takeIf { it.isNotBlank() }?.let { runCatching { json.decodeFromString<Map<String, Entry>>(it) }.getOrNull() }.orEmpty()

    /** New choices win; the most recent entries are kept when the memory is full. */
    fun merge(old: Map<String, Entry>, new: Map<String, Entry>): Map<String, Entry> =
        (old - new.keys + new).entries.toList().takeLast(MAX_ENTRIES).associate { it.key to it.value }
}

/** Local storage of [VoiceMemory] (app-private preferences, included in nothing but this phone). */
class VoicePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("korbunio_voice_memory", Context.MODE_PRIVATE)

    fun load(): Map<String, VoiceMemory.Entry> = VoiceMemory.decode(prefs.getString("memory", null))

    fun save(memory: Map<String, VoiceMemory.Entry>) { prefs.edit().putString("memory", VoiceMemory.encode(memory)).apply() }
}
