package net.montierth.avogadro.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.montierth.avogadro.data.Answer
import net.montierth.avogadro.data.Api
import net.montierth.avogadro.data.Hit
import net.montierth.avogadro.data.Infobox
import net.montierth.avogadro.data.Quote
import net.montierth.avogadro.data.Tickers

enum class Screen { Home, Results }

val CATEGORIES = listOf(
    "general" to "All", "images" to "Images", "news" to "News",
    "videos" to "Videos", "it" to "IT", "science" to "Science",
)

val TIMES = listOf(
    "" to "Any time", "day" to "Past day", "week" to "Past week",
    "month" to "Past month", "year" to "Past year",
)

val RANGES = listOf("1D", "5D", "1M", "6M", "YTD", "1Y", "5Y", "MAX")

/** The quote card: the range picked, the range on show (the two differ while a range loads),
 *  and every range fetched so far. It is made from the 1D quote. */
data class StockCard(val range: String, val shown: String, val data: Map<String, Quote>) {
    constructor(quote: Quote) : this("1D", "1D", mapOf("1D" to quote))

    val base: Quote get() = data.getValue("1D")
    val quote: Quote get() = data.getValue(shown)
    val loading: Boolean get() = range != shown
}

data class Results(
    val query: String = "",
    val category: String = "general",
    val time: String = "",
    val page: Int = 1,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: Boolean = false,
    val moreFailed: Boolean = false,
    val hits: List<Hit> = emptyList(),
    val answers: List<Answer> = emptyList(),
    val correction: String? = null,
    val infobox: Infobox? = null,
    val stock: StockCard? = null,
    val related: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
    val canLoadMore: Boolean = false,
    /** Bumps on every new search, so the list returns to the top. */
    val generation: Int = 0,
)

class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val api = Api()

    var screen by mutableStateOf(Screen.Home)
        private set
    var results by mutableStateOf(Results())
        private set
    var text by mutableStateOf(TextFieldValue(""))
        private set
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set
    /** Bumps on every search the user sends: the atom is excited into a new orbital. */
    var excitations by mutableIntStateOf(0)
        private set
    /** Bumps when the home-screen widget asks for the search field. */
    var focusRequests by mutableIntStateOf(0)
        private set

    val visited = mutableStateMapOf<String, Boolean>()

    private var searchJob: Job? = null
    private var suggestJob: Job? = null

    fun requestFocus() {
        focusRequests++
    }

    fun edit(value: TextFieldValue) {
        val changed = value.text != text.text
        text = value
        if (changed) suggestFor(value.text)
    }

    fun search(query: String, category: String = results.category, time: String = results.time) {
        val q = query.trim()
        if (q.isEmpty()) return home()
        clearSuggestions()
        text = TextFieldValue(q, TextRange(q.length))
        excitations++
        screen = Screen.Results
        searchJob?.cancel()
        results = Results(query = q, category = category, time = time, loading = true, generation = results.generation + 1)
        // A query that names a ticker starts its quote alongside the search.
        val intent = if (category == "general") Tickers.intent(q) else null
        val guess = Tickers.guess(intent)
        val generation = results.generation
        searchJob = viewModelScope.launch {
            val early = guess?.let { async { api.stock(it, "1D") } }
            try {
                val page = api.search(q, category, 1, time)
                val hits = page.hits.distinctBy { it.url }
                // Results confirm or find the ticker. The card gets a moment to arrive so the
                // results don't jump down under it; if it is slower, it is put in place when it comes.
                val symbol = Tickers.symbol(intent, page.hits)
                if (symbol != guess) early?.cancel()
                val pending: Deferred<Quote?>? = symbol?.let { if (it == guess) early else async { api.stock(it, "1D") } }
                val stock = pending?.let { withTimeoutOrNull(1200) { it.await() } }
                results = results.copy(
                    loading = false,
                    hits = hits,
                    answers = page.answers,
                    correction = page.correction?.takeIf { !it.equals(q, ignoreCase = true) },
                    infobox = if (category == "general") page.infobox else null,
                    stock = stock?.let(::StockCard),
                    related = page.related.filter { !it.equals(q, ignoreCase = true) }.take(8),
                    failed = page.failed,
                    canLoadMore = hits.size >= 6,
                )
                if (stock == null && pending != null && !pending.isCompleted) {
                    val late = pending.await()
                    if (late != null && results.generation == generation) results = results.copy(stock = StockCard(late))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                early?.cancel()
                results = results.copy(loading = false, error = true)
            }
        }
    }

    /** Shows the card's chart for [range], fetching it the first time. A range that fails to load
     *  leaves the card as it was; one that arrives after another was picked is only kept. */
    fun setStockRange(range: String) {
        val card = results.stock ?: return
        if (range == card.range) return
        if (range in card.data) {
            results = results.copy(stock = card.copy(range = range, shown = range))
            return
        }
        results = results.copy(stock = card.copy(range = range))
        val generation = results.generation
        viewModelScope.launch {
            val quote = api.stock(card.base.symbol, range)
            val now = results.stock?.takeIf { results.generation == generation } ?: return@launch
            results = results.copy(
                stock = when {
                    quote == null -> if (now.range == range) now.copy(range = now.shown) else now
                    now.range == range -> now.copy(shown = range, data = now.data + (range to quote))
                    else -> now.copy(data = now.data + (range to quote))
                },
            )
        }
    }

    fun retry() = search(results.query)

    fun loadMore() {
        val r = results
        if (r.loading || r.loadingMore || !r.canLoadMore) return
        results = r.copy(loadingMore = true, moreFailed = false)
        searchJob = viewModelScope.launch {
            try {
                val next = api.search(r.query, r.category, r.page + 1, r.time)
                val seen = results.hits.mapTo(HashSet()) { it.url }
                val fresh = next.hits.filter { seen.add(it.url) }
                results = results.copy(
                    page = r.page + 1,
                    loadingMore = false,
                    hits = results.hits + fresh,
                    canLoadMore = fresh.size >= 6 && r.page + 1 < 10,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                results = results.copy(loadingMore = false, moreFailed = true)
            }
        }
    }

    fun setCategory(category: String) {
        if (category != results.category) search(results.query, category = category)
    }

    fun setTime(time: String) {
        if (time != results.time) search(results.query, time = time)
    }

    fun home() {
        searchJob?.cancel()
        clearSuggestions()
        text = TextFieldValue("")
        results = Results(generation = results.generation + 1)
        screen = Screen.Home
    }

    fun clearSuggestions() {
        suggestJob?.cancel()
        suggestions = emptyList()
    }

    fun markVisited(url: String) {
        visited[url] = true
    }

    private fun suggestFor(typed: String) {
        suggestJob?.cancel()
        val q = typed.trim()
        if (q.length < 2) {
            suggestions = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(120)
            val words = try {
                api.suggest(q)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList() // suggestions are optional; a failure just means none show
            }
            ensureActive()
            suggestions = words
        }
    }
}
