package net.montierth.avogadro.ui

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import net.montierth.avogadro.AvogadroApplication
import net.montierth.avogadro.data.Answer
import net.montierth.avogadro.data.AppUpdate
import net.montierth.avogadro.data.Api
import net.montierth.avogadro.data.Ask
import net.montierth.avogadro.data.Hit
import net.montierth.avogadro.data.Infobox
import net.montierth.avogadro.data.Quote
import net.montierth.avogadro.data.Source
import net.montierth.avogadro.data.Turing
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

/** A written answer: waiting while [text] is empty, then the text so far, [done] once complete. */
data class AskCard(val sources: List<Source>, val text: String = "", val done: Boolean = false)

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
    val ask: AskCard? = null,
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
    /** The home-screen widget asked for the search field; the screen answers it once (see [focusTaken]). */
    var focusWanted by mutableStateOf(false)
        private set

    val visited = mutableStateMapOf<String, Boolean>()

    private val updater = (app as AvogadroApplication).updater

    /** A newer build from the instance, downloaded and verified: home offers "Update". */
    val update: StateFlow<AppUpdate?> = updater.ready

    /** A tapped update is downloading or installing. */
    var updating by mutableStateOf(false)
        private set
    @Volatile private var installAfterGrant = false

    /**
     * "Update": install now. When Android first needs this app allowed to install apps, returns
     * that settings screen; the install carries on when the user comes back.
     */
    fun installUpdate(): Intent? {
        if (!updater.canInstall()) {
            installAfterGrant = true
            return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${getApplication<Application>().packageName}".toUri())
        }
        startInstall()
        return null
    }

    private fun startInstall() {
        if (updating) return
        updating = true
        viewModelScope.launch {
            try {
                // A silent install closes the app; if Android asks first, give its dialog time.
                if (updater.installNow()) delay(30_000)
            } finally {
                updating = false
            }
        }
    }

    /** Back from the "install unknown apps" switch: carry on with the update if it was allowed. */
    fun onResumed() {
        if (installAfterGrant && updater.canInstall()) {
            installAfterGrant = false
            startInstall()
        }
    }

    private var searchJob: Job? = null
    private var suggestJob: Job? = null
    private var warmedAt = 0L

    fun requestFocus() {
        focusWanted = true
    }

    fun focusTaken() {
        focusWanted = false
    }

    fun edit(value: TextFieldValue) {
        val changed = value.text != text.text
        text = value
        if (changed) suggestFor(value.text)
        if (changed && results.category == "general" && Ask.intent(value.text.trim())) warm()
    }

    /** Typing a question wakes the model, so it is ready by the time the results are: Turing's on
     *  the phone when it's installed, else the instance's. */
    private fun warm() {
        val now = System.currentTimeMillis()
        if (now - warmedAt < 60_000) return
        warmedAt = now
        val app = getApplication<Application>()
        viewModelScope.launch {
            if (Turing.available(app)) withContext(Dispatchers.IO) { Turing.warm(app) } else api.warm()
        }
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
        val ask = category == "general" && Ask.intent(q)
        if (ask) warm()
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
                // SearXNG's own instant answers (sums, conversions) need no model.
                val sources = if (ask && page.answers.isEmpty()) Ask.sources(hits, Format::plain) else emptyList()
                results = results.copy(
                    loading = false,
                    hits = hits,
                    answers = page.answers,
                    correction = page.correction?.takeIf { !it.equals(q, ignoreCase = true) },
                    infobox = if (category == "general") page.infobox else null,
                    stock = stock?.let(::StockCard),
                    ask = if (sources.size >= 2) AskCard(sources) else null,
                    related = page.related.filter { !it.equals(q, ignoreCase = true) }.take(8),
                    failed = page.failed,
                    canLoadMore = hits.size >= 6,
                )
                if (results.ask != null) launch { answer(q, sources, generation) }
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

    /** Streams the answer into its card; the card goes away if the results don't answer the
     *  question or the model can't be reached. */
    private suspend fun answer(q: String, sources: List<Source>, generation: Int) {
        fun update(card: AskCard?) {
            if (results.generation == generation) results = results.copy(ask = card)
        }
        try {
            var text = ""
            suspend fun show(answer: Flow<String>) = answer.collect { so ->
                text = so
                if (so.isNotBlank()) update(results.ask?.copy(text = so))
            }
            val app = getApplication<Application>()
            if (Turing.available(app)) {
                try {
                    show(api.answerOnDevice(app, q, sources))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Turing failed before writing anything: the instance's model answers as usual.
                    if (text.isNotBlank()) throw e
                    show(api.answer(q, sources))
                }
            } else {
                show(api.answer(q, sources))
            }
            update(if (text.isBlank()) null else results.ask?.copy(text = text, done = true))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            update(null)
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
