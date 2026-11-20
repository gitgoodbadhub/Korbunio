package de.lesecuritae.korbuino

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfferSearchTest {
    private data class P(val name: String, val brand: String = "", val description: String = "", val pack: String? = null)

    private val catalogue = listOf(
        P("Hakle Toilettenpapier 3-lagig", "Hakle", "8 x 150 Blatt"),
        P("Zewa Wisch&Weg Küchenrolle", "Zewa"),
        P("Müller Joghurt mit der Ecke Erdbeere", "Müller", pack = "150 g"),
        P("Müller Joghurt Erdbeere", "Müller", pack = "50 g"),
        P("Zott Sahne Joghurt Erdbeere", "Zott", pack = "150 g"),
        P("Bio Erdbeerjoghurt", "", pack = "500 g"),
        P("Joghurt-Gums Fruchtgummi", "Katjes"),
        P("Joghurtbecher 4er Set", ""),
        P("Gurken-Dip", "", "mit Joghurt verfeinert"),
        P("Coca-Cola Zero", "Coca-Cola", pack = "1,5 l"),
    )

    private fun ranked(query: String): List<String> {
        val q = OfferSearch.prepare(query)
        return catalogue.map { it to OfferSearch.score(q, OfferSearch.index(it.name, it.brand, it.description, it.pack)) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first.name }
    }

    @Test fun `synonym finds toilet paper for Klopapier`() {
        assertEquals(listOf("Hakle Toilettenpapier 3-lagig"), ranked("Klopapier"))
    }

    @Test fun `general word ranks real joghurt above sweets, cups and side mentions`() {
        val result = ranked("Joghurt")
        val realJoghurts = setOf("Müller Joghurt mit der Ecke Erdbeere", "Müller Joghurt Erdbeere", "Zott Sahne Joghurt Erdbeere", "Bio Erdbeerjoghurt")
        assertEquals(realJoghurts, result.take(4).toSet())
        val rest = result.drop(4)
        assertTrue(rest.containsAll(listOf("Joghurt-Gums Fruchtgummi", "Joghurtbecher 4er Set", "Gurken-Dip")))
        assertEquals("Gurken-Dip", result.last())
    }

    @Test fun `exact product with brand and size comes first and other brands drop out`() {
        val result = ranked("Müller Joghurt Erdbeere 50g")
        assertEquals("Müller Joghurt Erdbeere", result.first())
        assertEquals("Müller Joghurt mit der Ecke Erdbeere", result[1])
        assertTrue("Zott" !in result.joinToString())
        assertEquals(ranked("Müller Joghurt Erdbeere 50g"), ranked("müller joghurt erdbeere 50 Gramm"))
    }

    @Test fun `small typos and other spellings are tolerated`() {
        assertTrue("Zott Sahne Joghurt Erdbeere" in ranked("Joghrt Erdbere"))
        assertTrue("Müller Joghurt Erdbeere" in ranked("Jogurt"))
        assertTrue("Coca-Cola Zero" in ranked("Cola"))
        assertTrue("Coca-Cola Zero" in ranked("coca cola 1,5 liter"))
    }

    @Test fun `quantities are written in one way`() {
        assertEquals(setOf("50g"), OfferSearch.quantities(OfferSearch.normalize("50 g")))
        assertEquals(setOf("50g"), OfferSearch.quantities(OfferSearch.normalize("50Gramm")))
        assertEquals(setOf("1.5l"), OfferSearch.quantities(OfferSearch.normalize("1,5 Liter")))
        assertEquals(setOf("6st"), OfferSearch.quantities(OfferSearch.normalize("6er Pack")))
    }

    @Test fun `unrelated words find nothing and an empty query matches nothing`() {
        assertEquals(emptyList<String>(), ranked("Waschmittel"))
        assertEquals(emptyList<String>(), ranked("   "))
    }

    @Test fun `did you mean suggests a synonym or the closest product word`() {
        val names = catalogue.map { it.name }
        assertEquals("toilettenpapier", OfferSearch.suggestion("Klopapier", names.filter { "Toilettenpapier" in it }))
        assertEquals("joghurt", OfferSearch.suggestion("Joghrt", names))
        assertNull(OfferSearch.suggestion("Joghurt", names))
        assertNull(OfferSearch.suggestion("Waschmittel", names))
    }

    @Test fun `stemming and distance behave`() {
        assertEquals("erdbeer", OfferSearch.stem("erdbeere"))
        assertEquals("milch", OfferSearch.stem("milch"))
        assertEquals(1, OfferSearch.distance("joghrt", "joghurt", 2))
        assertEquals(3, OfferSearch.distance("abc", "xyzabc", 2))
    }
}
