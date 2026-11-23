package com.hisbaan.orbit.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class MapsCalloutsTest {
    // Lines as text recognition reads a navigation screen after "Gas stations" along the route
    // (pixel boxes from a 1080x2410 screenshot, Maps 26.44).
    private val screen = listOf(
        OcrLine("60 m", 247, 214, 400, 306),
        OcrLine("Richmond St E", 247, 306, 660, 404),
        OcrLine("$1.74/Regular", 440, 580, 710, 630),
        OcrLine("+7 min", 440, 640, 560, 690),
        OcrLine("Carlton St.", 230, 720, 420, 760),
        OcrLine("Jonty Emergency G...", 25, 1000, 460, 1060),
        OcrLine("+3 min", 25, 1070, 150, 1120),
        OcrLine("Shell", 795, 1005, 890, 1060),
        OcrLine("+5 min", 795, 1070, 920, 1120),
        OcrLine("Circle K", 35, 1215, 230, 1270),
        OcrLine("Quick detour", 35, 1280, 285, 1330),
        OcrLine("Circle K", 465, 1295, 620, 1350),
        OcrLine("+4 min", 465, 1355, 590, 1410),
        OcrLine("OLD TORONTO", 155, 1450, 450, 1490),
        OcrLine("14 min", 431, 2136, 648, 2242),
        OcrLine("3.7 km • 10:52 a.m.", 231, 2242, 849, 2310),
    )

    @Test
    fun `labels are named by the line above their detour, quickest first`() {
        val found = MapsCallouts.find(screen)
        assertEquals(
            listOf("Circle K (quick detour)", "Jonty Emergency G... (+3 min)", "Circle K (+4 min)", "Shell (+5 min)"),
            found.map { it.describe() }.take(4),
        )
    }

    @Test
    fun `a fuel price between name and detour is kept, and the name looked for above it`() {
        val lines = listOf(
            OcrLine("Esso", 100, 100, 200, 140),
            OcrLine("$1.74/Regular", 100, 145, 330, 185),
            OcrLine("+2 min", 100, 190, 210, 230),
        )
        assertEquals(listOf(Callout("Esso", 2, "$1.74/Regular", 215, 165)), MapsCallouts.find(lines))
    }

    @Test
    fun `a label whose name is hidden can't be offered`() {
        // The +7 min one's name is under the turn banner: only its price shows above it.
        assertEquals(emptyList<Callout>(), MapsCallouts.find(screen).filter { it.addedMinutes == 7 })
    }

    @Test
    fun `a label is tapped in its middle`() {
        val shell = MapsCallouts.find(screen).single { it.name == "Shell" }
        assertEquals(857, shell.x)
        assertEquals(1062, shell.y)
    }

    @Test
    fun `hours count, and lines that only look like times don't`() {
        val lines = listOf(
            OcrLine("Costco Gas", 100, 100, 300, 140),
            OcrLine("+1 hr 5 min", 100, 145, 260, 185),
            OcrLine("14 min", 100, 600, 200, 640),
        )
        assertEquals(listOf(Callout("Costco Gas", 65, null, 200, 142)), MapsCallouts.find(lines))
    }
}
