package com.abhinavxt.newsforge.core.tag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SectorTest {

    private val map = SeedSymbols.SECTORS

    @Test
    fun symbolsResolveToTheirSector() {
        assertEquals(Sector.DEFENCE, map.sectorOf("BEL"))
        assertEquals(Sector.BANKING, map.sectorOf("HDFCBANK"))
        assertEquals(Sector.METALS, map.sectorOf("TATASTEEL"))
        assertEquals(Sector.IT, map.sectorOf("INFY"))
    }

    @Test
    fun lookupIsCaseInsensitiveAndMissesReturnNull() {
        assertEquals(Sector.DEFENCE, map.sectorOf("bel"))
        assertNull(map.sectorOf("NOSUCHCO"))
    }

    @Test
    fun mostOfTheSeedListIsClassified() {
        // An unclassified seed name silently drops out of every sector view, so this is
        // worth asserting rather than assuming.
        assertTrue("only ${map.size} classified", map.size >= 90)
    }

    @Test
    fun aStoryNamingNoCompanyStillFindsItsBasket() {
        // The half symbol tagging cannot do: this headline mentions no company while
        // being the most important thing that happened to several.
        val sectors = map.sectorsFor(emptyList(), "Government raises import duty on edible oil")
        assertTrue(Sector.FMCG in sectors)
    }

    @Test
    fun symbolAndThemeSectorsAreCombinedNotChosenBetween() {
        // A story can name a bank and still be about rates.
        val sectors = map.sectorsFor(
            listOf("HDFCBANK"),
            "HDFC Bank shares fall as RBI holds repo rate",
        )
        assertTrue(Sector.BANKING in sectors)
        assertTrue(Sector.NBFC in sectors)
    }

    @Test
    fun oneThemeCanTouchSeveralSectors() {
        // Crude is an oil story and an input-cost story at the same time.
        val sectors = ThemeRules.match("Brent crude slips below \$70 a barrel")
        assertTrue(Sector.OIL_GAS in sectors)
        assertTrue(Sector.CHEMICALS in sectors)
        assertTrue(Sector.AUTO in sectors)
    }

    @Test
    fun ratesReachBothLenderKinds() {
        val sectors = ThemeRules.match("RBI keeps repo rate unchanged")
        assertTrue(Sector.BANKING in sectors)
        assertTrue(Sector.NBFC in sectors)
    }

    @Test
    fun defencePolicyIsRecognisedWithoutACompanyName() {
        assertTrue(Sector.DEFENCE in ThemeRules.match("Defence procurement council clears proposals"))
    }

    @Test
    fun ordinaryCompanyNewsMatchesNoTheme() {
        // Themes must be specific, or every story would light up every basket.
        assertTrue(ThemeRules.match("Company appoints new chief financial officer").isEmpty())
        assertTrue(ThemeRules.match("").isEmpty())
    }

    @Test
    fun sectorNamesRoundTripThroughParse() {
        for (sector in Sector.entries) {
            assertEquals(sector, Sector.parse(sector.name))
            assertEquals(sector, Sector.parse(sector.name.lowercase()))
        }
        assertNull(Sector.parse("NOT_A_SECTOR"))
        assertNull(Sector.parse(null))
    }

    @Test
    fun theSymbolTableCarriesSectorsThroughARoundTrip() {
        val entries = listOf(
            SymbolEntry("BEL", "Bharat Electronics", listOf("BEL"), "DEFENCE"),
            // A sector with no aliases still needs the empty alias column preserved.
            SymbolEntry("NTPC", "NTPC", emptyList(), "POWER"),
            SymbolEntry("XYZ", "Unclassified Co"),
        )
        val parsed = SymbolTable.parse(SymbolTable.format(entries))
        assertEquals(entries, parsed)
        assertEquals(Sector.POWER, SectorMap(parsed).sectorOf("NTPC"))
        assertNull(SectorMap(parsed).sectorOf("XYZ"))
    }

    @Test
    fun anUnclassifiedEntryContributesNoSector() {
        val custom = SectorMap(listOf(SymbolEntry("AAA", "Aaa Co", sector = "NOT_A_SECTOR")))
        assertNull(custom.sectorOf("AAA"))
        assertFalse(custom.size > 0)
    }
}
