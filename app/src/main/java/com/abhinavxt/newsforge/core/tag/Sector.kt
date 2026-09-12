package com.abhinavxt.newsforge.core.tag

import java.util.Locale

/**
 * Coarse sector buckets.
 *
 * Deliberately broad — eighteen buckets, not the sixty an exchange classification uses.
 * The purpose is "does this story touch what I hold", and at that question a finer
 * taxonomy splits a basket that moves together into pieces that each look uninteresting.
 */
enum class Sector(val label: String) {
    BANKING("Banking"),
    NBFC("NBFC"),
    INSURANCE("Insurance"),
    IT("IT"),
    PHARMA("Pharma"),
    AUTO("Auto"),
    METALS("Metals"),
    OIL_GAS("Oil & Gas"),
    POWER("Power"),
    CEMENT("Cement"),
    FMCG("FMCG"),
    REALTY("Realty"),
    TELECOM("Telecom"),
    INFRA("Infra"),
    DEFENCE("Defence"),
    CHEMICALS("Chemicals"),
    CAPITAL_GOODS("Capital goods"),
    CONSUMER("Consumer"),
    ;

    companion object {
        fun parse(name: String?): Sector? =
            name?.trim()?.uppercase(Locale.US)?.let { key -> entries.firstOrNull { it.name == key } }
    }
}

/**
 * Maps a story's text to the sectors it touches, even when it names no company.
 *
 * This is the half that symbol tagging cannot do. A duty change, a PLI announcement or a
 * crude move is about a *basket*, and the headline usually names none of its members —
 * "Government raises import duty on edible oil" mentions no company at all while being the
 * most important thing that happened to several.
 *
 * Rules are additive: a story can touch more than one sector, and often does. Crude moving
 * is simultaneously an oil story and a paint-and-tyre input-cost story.
 */
object ThemeRules {

    data class Rule(val sectors: Set<Sector>, val pattern: Regex)

    private fun rule(vararg sectors: Sector, pattern: String) =
        Rule(sectors.toSet(), Regex(pattern, RegexOption.IGNORE_CASE))

    val RULES: List<Rule> = listOf(
        rule(
            Sector.BANKING, Sector.NBFC,
            pattern = "\\brbi\\b|repo rate|monetary policy|\\bmpc\\b|\\bcrr\\b|\\bslr\\b|" +
                "bank credit|deposit growth|\\bnpa\\b|asset quality|priority sector",
        ),
        rule(
            Sector.OIL_GAS,
            pattern = "crude|\\bbrent\\b|\\bwti\\b|\\bopec\\b|refin(ery|ing)|" +
                "petrol price|diesel price|natural gas price|city gas",
        ),
        // Crude is an input cost long before it is an oil story for these names.
        rule(
            Sector.CHEMICALS, Sector.AUTO,
            pattern = "crude|\\bbrent\\b|rubber price|input cost",
        ),
        rule(
            Sector.METALS,
            pattern = "steel price|iron ore|\\bcoking coal\\b|aluminium|copper price|" +
                "zinc|export duty on steel|china stimulus",
        ),
        rule(
            Sector.POWER,
            pattern = "power demand|electricity|discom|coal supply|renewable|solar capacity|" +
                "wind capacity|\\bpli\\b.{0,30}(solar|battery|cell)",
        ),
        rule(
            Sector.DEFENCE,
            pattern = "defence (ministry|procurement|budget|export)|\\bdac\\b|indigenisation|" +
                "\\bmod\\b contract|armed forces|missile order|naval order",
        ),
        rule(
            Sector.IT,
            pattern = "\\bh-?1b\\b|it spending|deal wins|\\btcv\\b|offshore|" +
                "us (tech|banking) spend|rupee (appreciat|depreciat)",
        ),
        rule(
            Sector.PHARMA,
            pattern = "\\busfda\\b|\\bfda\\b (approval|warning|observation)|\\banda\\b|" +
                "drug pricing|\\bnppa\\b|price control|import alert",
        ),
        rule(
            Sector.AUTO,
            pattern = "vehicle sales|auto sales|\\bev\\b policy|\\bfame\\b|scrappage|" +
                "emission norm|\\bbs-?vi\\b|semiconductor shortage",
        ),
        rule(
            Sector.CEMENT, Sector.INFRA,
            pattern = "cement price|infrastructure spend|capex push|road project|" +
                "\\bnhai\\b|housing demand",
        ),
        rule(
            Sector.REALTY,
            pattern = "home loan rate|property registration|\\brera\\b|housing sales|" +
                "office absorption",
        ),
        rule(
            Sector.FMCG, Sector.CONSUMER,
            pattern = "monsoon|rural demand|palm oil|edible oil|\\bgst\\b (cut|hike) on|" +
                "consumption slowdown|festive demand",
        ),
        rule(
            Sector.TELECOM,
            pattern = "\\btrai\\b|spectrum|tariff hike|\\bagr\\b dues|\\barpu\\b|\\b5g\\b rollout",
        ),
        rule(
            Sector.CAPITAL_GOODS, Sector.INFRA,
            pattern = "order inflow|capex cycle|\\bpli\\b scheme|make in india|" +
                "manufacturing incentive",
        ),
        rule(
            Sector.INSURANCE,
            pattern = "\\birdai\\b|premium growth|\\bapel?\\b|surrender value|bancassurance",
        ),
    )

    fun match(text: String): Set<Sector> {
        if (text.isBlank()) return emptySet()
        val found = LinkedHashSet<Sector>()
        for (rule in RULES) {
            if (rule.pattern.containsMatchIn(text)) found += rule.sectors
        }
        return found
    }
}

/** Symbol-to-sector lookup, backed by the lexicon's own entries. */
class SectorMap(entries: List<SymbolEntry>) {

    private val bySymbol: Map<String, Sector> = entries
        .mapNotNull { entry -> Sector.parse(entry.sector)?.let { entry.symbol to it } }
        .toMap()

    fun sectorOf(symbol: String): Sector? = bySymbol[symbol.uppercase(Locale.US)]

    /**
     * Sectors a story touches: those of the companies it names, plus those its wording
     * implies.
     *
     * Both together, not either — a story can name a bank and still be about rates.
     */
    fun sectorsFor(symbols: List<String>, text: String): Set<Sector> {
        val found = LinkedHashSet<Sector>()
        symbols.forEach { symbol -> sectorOf(symbol)?.let { found += it } }
        found += ThemeRules.match(text)
        return found
    }

    val size: Int get() = bySymbol.size
}
