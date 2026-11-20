package de.lesecuritae.korbuino

import de.lesecuritae.korbuino.ShoppingCommand.Command
import de.lesecuritae.korbuino.ShoppingCommand.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShoppingCommandTest {
    private fun parse(text: String) = ShoppingCommand.parse(text)

    @Test fun `add several products with quantities and limits`() {
        assertEquals(
            Command.Add(listOf(Item("klopapier", 1, 300), Item("müller joghurt erdbeere", 2))),
            parse("Füge Klopapier für höchstens 3 Euro und zwei Müller Joghurt Erdbeere hinzu"),
        )
    }

    @Test fun `budget for the whole purchase`() {
        val command = parse("Ich brauche Milch, Butter und Eier, insgesamt höchstens 30 Euro") as Command.Add
        assertEquals(listOf("milch", "butter", "eier"), command.items.map { it.name })
        assertEquals(3000, command.budgetCents)
    }

    @Test fun `prices in words and with cents`() {
        assertEquals(250, ShoppingCommand.cents("2,50"))
        assertEquals(250, ShoppingCommand.cents("2", "50"))
        assertEquals(350, ShoppingCommand.cents("drei", "fünfzig"))
        assertEquals(400, ShoppingCommand.cents("vier"))
        assertEquals(99, ShoppingCommand.number("neunundneunzig"))
        val command = parse("Setz Kaffee auf die Liste, maximal fünf Euro neunundneunzig") as Command.Add
        assertEquals("kaffee", command.items.single().name)
        assertEquals(599, command.items.single().maxPriceCents)
    }

    @Test fun `quantities with pack words and x`() {
        val command = parse("Ich brauche 3 Flaschen Wasser und Toast 2x") as Command.Add
        assertEquals(listOf(Item("wasser", 3), Item("toast", 2)), command.items)
        val articles = parse("Ich brauche eine Gurke und einen Salat") as Command.Add
        assertEquals(listOf(Item("gurke"), Item("salat")), articles.items)
    }

    @Test fun `bare product list is added`() {
        assertEquals(Command.Add(listOf(Item("milch"), Item("brot"))), parse("Milch und Brot"))
    }

    @Test fun `search for offers with an optional limit`() {
        assertEquals(Command.Search("klopapier"), parse("Wo gibt es Klopapier im Angebot?"))
        assertEquals(Command.Search("kaffee", 600), parse("Zeig mir Kaffee unter 6 Euro"))
        assertEquals(Command.Search("butter"), parse("Was kostet Butter"))
    }

    @Test fun `remove, list and route`() {
        assertEquals(Command.Remove(listOf("milch")), parse("Lösch Milch von der Liste"))
        assertEquals(Command.Remove(listOf("butter", "eier")), parse("Streiche Butter und Eier"))
        assertEquals(Command.ShowList, parse("Zeig mir meine Einkaufsliste"))
        assertEquals(Command.Route, parse("Zeig mir die Route"))
        assertEquals(Command.Route, parse("Wo muss ich hin?"))
    }

    @Test fun `nothing usable stays unknown`() {
        assertTrue(parse("bitte") is Command.Unknown)
        assertTrue(parse("") is Command.Unknown)
        assertTrue(parse("Füge hinzu") is Command.Unknown)
    }
}
