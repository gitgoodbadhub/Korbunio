package de.lesecuritae.korbuino

import de.lesecuritae.korbuino.ShoppingCommand.Command
import de.lesecuritae.korbuino.ShoppingCommand.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NanoCommandTest {
    @Test fun `add with quantities, limits and budget, also inside a code fence`() {
        val answer = """
            ```json
            {"aktion":"hinzufuegen","produkte":[{"name":"Mehl","menge":1,"max_euro":null},
             {"name":"Eier","menge":"10","max_euro":"2,50"},{"name":"Milch","menge":2.0}],"budget_euro":15}
            ```
        """.trimIndent()
        assertEquals(
            Command.Add(listOf(Item("mehl"), Item("eier", 10, 250), Item("milch", 2)), 1500),
            NanoCommand.parse(answer),
        )
    }

    @Test fun `other actions`() {
        assertEquals(Command.Search("kaffee", 600), NanoCommand.parse("""{"aktion":"suchen","produkte":[{"name":"Kaffee","max_euro":6}]}"""))
        assertEquals(Command.Remove(listOf("butter")), NanoCommand.parse("""{"aktion":"entfernen","produkte":[{"name":"Butter"}]}"""))
        assertEquals(Command.ShowList, NanoCommand.parse("""{"aktion":"liste"}"""))
        assertEquals(Command.Route, NanoCommand.parse("""{"aktion":"route","produkte":[]}"""))
    }

    @Test fun `malformed or empty answers are rejected`() {
        assertNull(NanoCommand.parse("Ich kann leider nicht helfen."))
        assertNull(NanoCommand.parse("""{"aktion":"hinzufuegen","produkte":[]}"""))
        assertNull(NanoCommand.parse("""{"aktion":"bestellen","produkte":[{"name":"Milch"}]}"""))
        assertNull(NanoCommand.parse("""{"aktion":"hinzufuegen","produkte":[{"name":"x"}, 5, null]}"""))
        assertNull(NanoCommand.parse("""{"aktion": """))
    }

    @Test fun `quantities and prices are clamped to sane values`() {
        val add = NanoCommand.parse("""{"aktion":"hinzufuegen","produkte":[{"name":"Wasser","menge":500,"max_euro":-3}],"budget_euro":"viel"}""") as Command.Add
        assertEquals(Item("wasser", 99, null), add.items.single())
        assertNull(add.budgetCents)
    }

    @Test fun `prompt carries the request on one line and asks for json only`() {
        val prompt = NanoCommand.prompt("Ich mache Pfannkuchen\nfür vier")
        assertTrue(prompt.endsWith("Anfrage: Ich mache Pfannkuchen für vier"))
        assertTrue("nur mit dem JSON" in prompt)
    }
}
