package net.montierth.avogadro.data

/** One result sent to /answer as a source, numbered by its place in the list. */
data class Source(val url: String, val title: String, val content: String)

/** A question ("how tall is everest", "boiling point of water", "rust vs go") gets an answer above
 *  the results, written by the instance's model from the top results' pages. The same rules as
 *  the web page. */
object Ask {
    private val I = RegexOption.IGNORE_CASE
    private val WH = Regex("^(who|whom|whose|what|what['’]?s|when|where|why|how|which)\\s", I)
    private val AUX = Regex("^(is|are|was|were|am|do|does|did|can|could|should|would|has|have|had)\\s", I)
    private val ANY = Regex("\\?$|\\b(vs\\.?|versus|difference between|meaning of|definition of|define|explain)\\s|\\bmeaning$", I)
    private val FACT = Regex(
        "\\b(population|capital of|height|elevation|distance|speed of|boiling point|melting point|freezing point|density|" +
            "half[- ]life|net worth|release date|side effects|symptoms|life expectancy|lifespan|calories|" +
            "atomic (number|mass|weight)|exchange rate|gdp|time ?zone|diameter|wavelength)\\b",
        I,
    )
    private val SKIP = Regex("^[!$]|\\bsite:|://", I)
    private const val SOURCES = 5

    fun intent(q: String): Boolean {
        val words = q.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2 || q.length > 200 || SKIP.containsMatchIn(q)) return false
        if (WH.containsMatchIn(q) || ANY.containsMatchIn(q)) return true
        if (AUX.containsMatchIn(q)) return words.size >= 3
        return FACT.containsMatchIn(q)
    }

    /** The top results worth reading: pages, not videos, each once, as [plain] text. */
    fun sources(hits: List<Hit>, plain: (String) -> String): List<Source> = hits.asSequence()
        .filter { it.template != "videos.html" && it.template != "images.html" }
        .filter { it.url.startsWith("http://") || it.url.startsWith("https://") }
        .distinctBy { it.url }
        .take(SOURCES)
        .map { Source(it.url, plain(it.title), plain(it.content)) }
        .toList()
}
