package net.montierth.avogadro.data

import java.net.URLDecoder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What a query says about stocks: a ticker for sure ($NVDA), a word that may be one
 *  (NVDA, nvda, on), or a stock asked for by name or ticker (nvidia stock, nvda stock). */
data class StockIntent(
    val symbol: String?,
    val sure: Boolean = false,
    val bare: Boolean = false,
    val caps: Boolean = false,
    val named: Boolean = false,
)

/** A ticker query ("NVDA", "$nvda", "nvda stock", "nvidia stock") gets a quote card above the
 *  results. A bare word counts as a ticker only when quote pages for it are among the top
 *  results, so "on" or "usa" stay plain searches. The same rules as the web page. */
object Tickers {
    private val TICKER = Regex("^[A-Z]{1,5}(?:\\.[A-Z])?$")
    private val DOLLAR = Regex("^\\$([A-Za-z]{1,5}(?:[.-][A-Za-z])?)$")
    private val WORD = Regex("^[A-Za-z]{1,5}(?:[.-][A-Za-z])?$")
    private val NAMED_WORD = Regex("^\\$?[a-z]{1,5}(?:[.-][a-z])?$")
    private val SHARE_PRICE = Regex("\\bshare price\\b")
    private val STOCK_WORDS = setOf("stock", "stocks", "share", "shares", "price", "prices", "quote", "ticker", "today", "now", "live", "chart", "current")
    private val ASKED = setOf("stock", "stocks", "shares", "ticker")

    // Hosts whose quote pages name the ticker in the path.
    private val QUOTE_PAGES = listOf(
        "(^|\\.)finance\\.yahoo\\.com$" to "^/quote/([^/?#]+)",
        "^(www\\.)?google\\.com$" to "^/finance/quote/([^/:?#]+):",
        "^(www\\.)?cnbc\\.com$" to "^/quotes/([^/?#]+)",
        "^(www\\.)?marketwatch\\.com$" to "^/investing/(?:stock|fund)/([^/?#]+)",
        "^(www\\.)?nasdaq\\.com$" to "^/market-activity/(?:stocks|etf|funds-and-etfs)/([^/?#]+)",
        "^(www\\.)?robinhood\\.com$" to "^/(?:us/en/)?stocks/([^/?#]+)",
        "^(www\\.)?stockanalysis\\.com$" to "^/(?:stocks|etf)/([^/?#]+)",
        "^(www\\.)?morningstar\\.com$" to "^/(?:stocks|etfs)/[a-z]+/([^/?#]+)",
        "^(www\\.)?tradingview\\.com$" to "^/symbols/[A-Za-z]+-([^/?#]+)",
        "^(www\\.)?zacks\\.com$" to "^/stock/quote/([^/?#]+)",
        "^(www\\.)?seekingalpha\\.com$" to "^/symbol/([^/?#]+)",
        "^(www\\.)?fool\\.com$" to "^/quote/[a-z]+/([^/?#]+)",
        "^(www\\.)?bloomberg\\.com$" to "^/quote/([^/:?#]+):US",
        "^(www\\.)?wsj\\.com$" to "^/market-data/quotes/([^/?#]+)",
        "^(www\\.)?stocktwits\\.com$" to "^/symbol/([^/?#]+)",
        "^(www\\.)?macrotrends\\.net$" to "^/stocks/charts/([^/?#]+)",
        "^markets\\.businessinsider\\.com$" to "^/stocks/([a-z.]+)-stock",
        "^(www\\.)?public\\.com$" to "^/stocks/([^/?#]+)",
    ).map { (host, path) -> Regex(host) to Regex(path) }

    private fun tickerOf(raw: String): String? {
        val decoded = runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
        val s = decoded.uppercase().replace(Regex("-([A-Z])$"), ".$1") // BRK-B is BRK.B
        return if (TICKER.matches(s)) s else null
    }

    fun quotePage(url: String): String? {
        val u = url.toHttpUrlOrNull() ?: return null
        for ((host, path) in QUOTE_PAGES) {
            if (!host.containsMatchIn(u.host)) continue
            return path.find(u.encodedPath)?.let { tickerOf(it.groupValues[1]) }
        }
        return null
    }

    fun intent(q: String): StockIntent? {
        DOLLAR.find(q)?.let { return StockIntent(tickerOf(it.groupValues[1]), sure = true) }
        val lower = q.lowercase()
        val words = lower.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size == 1) {
            val symbol = if (WORD.matches(q)) tickerOf(q) else null
            return symbol?.let { StockIntent(it, bare = true, caps = q == q.uppercase()) }
        }
        val asked = words.any { it in ASKED } || SHARE_PRICE.containsMatchIn(lower)
        if (!asked || words.size > 6) return null
        val rest = words.filter { it !in STOCK_WORDS }
        val symbol = if (rest.size == 1 && NAMED_WORD.matches(rest[0])) tickerOf(rest[0].removePrefix("$")) else null
        return StockIntent(symbol, named = true)
    }

    /** A guess worth fetching while the search runs. */
    fun guess(intent: StockIntent?): String? =
        intent?.symbol?.takeIf { intent.sure || intent.caps || intent.named }

    /** The results confirm or find the ticker: quote pages among the top ten vote for it. */
    fun symbol(intent: StockIntent?, hits: List<Hit>): String? {
        if (intent == null) return null
        if (intent.sure) return intent.symbol
        val votes = LinkedHashMap<String, Int>()
        for (hit in hits.take(10)) quotePage(hit.url)?.let { votes[it] = (votes[it] ?: 0) + 1 }
        if (intent.bare) return if ((votes[intent.symbol] ?: 0) >= 2) intent.symbol else null
        val best = votes.entries.maxByOrNull { it.value }
        return if (best != null && best.value >= 2) best.key else intent.symbol
    }
}
