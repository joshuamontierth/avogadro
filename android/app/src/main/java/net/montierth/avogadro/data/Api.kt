package net.montierth.avogadro.data

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import net.montierth.avogadro.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The Avogadro instance this build talks to (from avogadro.properties at build time). */
const val BASE_URL: String = BuildConfig.BASE_URL

data class Hit(
    val url: String,
    val title: String,
    val content: String,
    val template: String,
    val engines: List<String>,
    val thumbnail: String?,
    val image: String?,
    val published: String?,
    val author: String?,
    val length: String?,
    val resolution: String?,
    val format: String?,
    val authors: List<String>,
    val journal: String?,
    val doi: String?,
    val pdf: String?,
    val maintainer: String?,
    val popularity: String?,
    val license: String?,
    val source: String?,
    val homepage: String?,
)

data class Answer(val text: String, val url: String?)

data class Infobox(
    val title: String,
    val content: String,
    val image: String?,
    val attributes: List<Pair<String, String>>,
    val links: List<Pair<String, String>>,
)

data class SearchPage(
    val hits: List<Hit>,
    val answers: List<Answer>,
    val correction: String?,
    val infobox: Infobox?,
    val related: List<String>,
    val failed: List<String>,
)

/** One `[time, close]` pair of a chart; time in unix seconds. */
data class Point(val t: Long, val v: Double)

/** A pre-market or after-hours price. */
data class Extended(val state: String?, val price: Double, val change: Double?, val changePct: Double?)

data class StockStats(
    val open: Double?,
    val high: Double?,
    val low: Double?,
    val mktCap: String?,
    val pe: Double?,
    val divYield: Double?,
    val wk52High: Double?,
    val wk52Low: Double?,
)

/** The instance's /stock: a quote and the chart for one range (README, "The stock API"). */
data class Quote(
    val symbol: String,
    val name: String,
    val exchange: String?,
    val currency: String?,
    val price: Double,
    val change: Double?,
    val changePct: Double?,
    val prevClose: Double?,
    val state: String?,
    val asOf: Long?,
    val extended: Extended?,
    val range: String,
    /** The 1D chart's time span, so a day in progress draws partway; null for other ranges. */
    val session: Pair<Long, Long>?,
    val points: List<Point>,
    val stats: StockStats,
)

/** SearXNG's JSON API, through the Avogadro instance. Every field an engine sends is optional. */
class Api(private val client: OkHttpClient = defaultClient()) {

    suspend fun search(query: String, category: String, page: Int, time: String): SearchPage =
        withContext(Dispatchers.IO) {
            val url = "$BASE_URL/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("format", "json")
                .addQueryParameter("categories", category)
                .addQueryParameter("pageno", page.toString())
                .apply { if (time.isNotEmpty()) addQueryParameter("time_range", time) }
                .build()
            val request = Request.Builder().url(url).header("Accept", "application/json").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                parseSearch(response.body.string())
            }
        }

    suspend fun suggest(prefix: String): List<String> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/autocompleter".toHttpUrl().newBuilder().addQueryParameter("q", prefix).build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val root = Json.parseToJsonElement(response.body.string())
            (root as? JsonArray)?.getOrNull(1)?.strings()?.take(7) ?: emptyList()
        }
    }

    /** A quote and its chart for [range] (1D 5D 1M 6M YTD 1Y 5Y MAX), or null when there is none. */
    suspend fun stock(symbol: String, range: String): Quote? = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/stock".toHttpUrl().newBuilder()
            .addQueryParameter("symbol", symbol)
            .addQueryParameter("range", range)
            .build()
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) null else parseQuote(response.body.string())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // unreachable, or not the JSON it should be: no card
        }
    }

    /** The instance's written answer to [q] from [sources] (README, "Answers"), as the whole text so
     *  far each time more of it arrives. It ends empty when the results don't answer the question,
     *  and throws when the instance has no model or can't be reached. */
    fun answer(q: String, sources: List<Source>): Flow<String> = flow {
        val body = buildJsonObject {
            put("q", q)
            putJsonArray("results") {
                sources.forEach { s -> addJsonObject { put("url", s.url); put("title", s.title); put("content", s.content) } }
            }
        }.toString().toRequestBody(JSON)
        val call = client.newCall(Request.Builder().url("$BASE_URL/answer").post(body).build())
        // A new search cancels this collector; closing the call stops a read that is waiting on the model.
        val stop = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val source = response.body.source()
                val bytes = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val n = source.read(chunk)
                    if (n < 0) break
                    bytes.write(chunk, 0, n)
                    // A character split across reads decodes as U+FFFD until the rest of it comes.
                    emit(bytes.toString(Charsets.UTF_8.name()).trimEnd('\uFFFD'))
                }
                emit(bytes.toString(Charsets.UTF_8.name()))
            }
        } finally {
            stop.dispose()
        }
    }.flowOn(Dispatchers.IO)

    /** The same answer written on this phone by Turing: the instance reads the pages and builds the
     *  prompt ("local": true), Turing writes, and its words are relayed the way the instance relays its
     *  own model's (see [relayed]). An answer the instance's model already gave comes back as it is. */
    fun answerOnDevice(context: Context, q: String, sources: List<Source>): Flow<String> = flow {
        val body = buildJsonObject {
            put("q", q)
            put("local", true)
            put("model", "Turing")
            putJsonArray("results") {
                sources.forEach { s -> addJsonObject { put("url", s.url); put("title", s.title); put("content", s.content) } }
            }
        }.toString().toRequestBody(JSON)
        val prompt = client.newCall(Request.Builder().url("$BASE_URL/answer").post(body).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            Json.parseToJsonElement(r.body.string()).jsonObject
        }
        prompt.str("answer")?.let { emit(it); return@flow }
        val system = prompt.str("system") ?: throw IOException("no prompt from the instance")
        val user = prompt.str("user") ?: throw IOException("no prompt from the instance")
        var raw = ""
        Turing.write(context, system, user).collect { text ->
            raw = text
            relayed(text, done = false)?.takeIf { it.isNotEmpty() }?.let { emit(it) }
        }
        emit(relayed(raw, done = true) ?: "")
    }.flowOn(Dispatchers.IO)

    /** Wakes the instance's model ahead of a question; nothing comes back. */
    /** Tells the instance a search led to [url]; a similar search ranks it higher next time. */
    suspend fun click(q: String, url: String, title: String) = withContext(Dispatchers.IO) {
        runCatching {
            val body = buildJsonObject { put("q", q); put("url", url); put("title", title) }.toString().toRequestBody(JSON)
            client.newCall(Request.Builder().url("$BASE_URL/click").post(body).build()).execute().close()
        }
        Unit
    }

    suspend fun warm() = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url("$BASE_URL/answer/warm").post(ByteArray(0).toRequestBody()).build()).execute().close()
        }
        Unit
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}

// ---------- relaying an answer ----------

private val KIND_LINE = Regex("^kind:([^\\n]*)\\n", RegexOption.IGNORE_CASE)

/** What of [raw] the model has written so far is shown, as plugins/avogadro_answer.py relays its own
 *  model: the "Kind:" line held back and dropped, the first few characters held back to catch NONE,
 *  "" for NONE or "Kind: none", null while either may still be coming. */
internal fun relayed(raw: String, done: Boolean): String? {
    var s = raw.trimStart()
    if ("kind:".startsWith(s.take(5).lowercase())) {
        val m = KIND_LINE.find(s) ?: return if (done) "" else null
        if (m.groupValues[1].trim().lowercase().startsWith("none")) return ""
        s = s.substring(m.range.last + 1).trimStart()
    }
    if (s.length < 5 && !done) return null
    return if (s.uppercase().startsWith("NONE")) "" else s.trimEnd()
}

// ---------- tolerant JSON mapping ----------

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonElement.strings(): List<String> =
    (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        ?: emptyList()

private fun JsonObject.strings(key: String): List<String> = this[key]?.strings() ?: emptyList()

internal fun parseSearch(text: String): SearchPage {
    val root = Json.parseToJsonElement(text).jsonObject
    val hits = (root["results"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toHit() }
    val answers = (root["answers"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        o.str("answer")?.let { Answer(it, o.str("url")) }
    }
    val infobox = (root["infoboxes"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.toInfobox() }
    val failed = (root["unresponsive_engines"] as? JsonArray).orEmpty().mapNotNull { el ->
        when (el) {
            is JsonArray -> (el.firstOrNull() as? JsonPrimitive)?.contentOrNull
            is JsonPrimitive -> el.contentOrNull
            else -> null
        }
    }
    return SearchPage(
        hits = hits,
        answers = answers,
        correction = root.strings("corrections").firstOrNull(),
        infobox = infobox,
        related = root.strings("suggestions"),
        failed = failed.distinct(),
    )
}

private fun JsonObject.toHit(): Hit? {
    val url = str("url") ?: return null
    return Hit(
        url = url,
        title = str("title") ?: "",
        content = str("content") ?: "",
        template = str("template") ?: "default.html",
        engines = strings("engines"),
        thumbnail = str("thumbnail") ?: str("thumbnail_src"),
        image = str("img_src"),
        published = str("publishedDate"),
        author = str("author"),
        length = str("length"),
        resolution = str("resolution"),
        format = str("img_format"),
        authors = strings("authors"),
        journal = str("journal"),
        doi = str("doi"),
        pdf = str("pdf_url"),
        maintainer = str("maintainer"),
        popularity = str("popularity"),
        license = str("license_name"),
        source = str("source_code_url"),
        homepage = str("homepage"),
    )
}

private fun JsonObject.toInfobox(): Infobox? {
    val title = str("infobox") ?: return null
    val attributes = (this["attributes"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val label = o.str("label") ?: return@mapNotNull null
        val value = o.str("value") ?: return@mapNotNull null
        label to value
    }.take(8)
    val links = (this["urls"] as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val name = o.str("title") ?: return@mapNotNull null
        val url = o.str("url") ?: return@mapNotNull null
        if (Regex("^P\\d+$").matches(name)) null else name to url
    }.take(5)
    return Infobox(title, str("content") ?: "", str("img_src"), attributes, links)
}

private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

private fun JsonObject.num(key: String): Double? = this[key].num()

internal fun parseQuote(text: String): Quote? {
    val root = Json.parseToJsonElement(text) as? JsonObject ?: return null
    val price = root.num("price") ?: return null
    val points = (root["points"] as? JsonArray ?: return null).mapNotNull { el ->
        val pair = el as? JsonArray ?: return@mapNotNull null
        val t = pair.getOrNull(0).num() ?: return@mapNotNull null
        val v = pair.getOrNull(1).num() ?: return@mapNotNull null
        Point(t.toLong(), v)
    }
    val symbol = root.str("symbol") ?: return null
    val session = (root["session"] as? JsonArray)?.let { s ->
        val a = s.getOrNull(0).num()?.toLong()
        val b = s.getOrNull(1).num()?.toLong()
        if (a != null && b != null && b > a) a to b else null
    }
    val extended = (root["extended"] as? JsonObject)?.let { e ->
        e.num("price")?.let { Extended(e.str("state"), it, e.num("change"), e.num("change_pct")) }
    }
    val st = root["stats"] as? JsonObject ?: JsonObject(emptyMap())
    return Quote(
        symbol = symbol,
        name = root.str("name") ?: symbol,
        exchange = root.str("exchange"),
        currency = root.str("currency"),
        price = price,
        change = root.num("change"),
        changePct = root.num("change_pct"),
        prevClose = root.num("prev_close"),
        state = root.str("state"),
        asOf = root.num("as_of")?.toLong(),
        extended = extended,
        range = root.str("range") ?: "1D",
        session = session,
        points = points,
        stats = StockStats(
            open = st.num("open"),
            high = st.num("high"),
            low = st.num("low"),
            mktCap = st.str("mkt_cap"),
            pe = st.num("pe"),
            divYield = st.num("div_yield"),
            wk52High = st.num("wk52_high"),
            wk52Low = st.num("wk52_low"),
        ),
    )
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
