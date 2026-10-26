package net.montierth.avogadro.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import net.montierth.avogadro.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

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

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
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

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
