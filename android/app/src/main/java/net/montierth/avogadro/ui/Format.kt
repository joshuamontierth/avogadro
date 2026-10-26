package net.montierth.avogadro.ui

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import net.montierth.avogadro.data.BASE_URL

/** Text and URL helpers. Everything an engine returns is untrusted, so only http(s) links and
 *  this instance's own image proxy are ever used. */
object Format {
    private val tags = Regex("</?[a-zA-Z][^<>]{0,400}>")
    private val danglingTag = Regex("<(/[a-zA-Z]*|[a-zA-Z])[^<>]*$")
    private val spaces = Regex("\\s{2,}")
    private const val MARK_ON = ''
    private const val MARK_OFF = ''

    /** Some engines leak markup (MathML, <b>) into titles and snippets. */
    fun untag(s: String): String = s.replace(tags, "").replace(danglingTag, "").replace(spaces, " ").trim()

    fun plain(s: String): String = untag(s).replace(MARK_ON.toString(), "").replace(MARK_OFF.toString(), "")

    /** SearXNG marks query terms with U+E000 ... U+E001. */
    fun marked(s: String, strong: SpanStyle): AnnotatedString = buildAnnotatedString {
        val text = untag(s)
        var on = false
        var start = 0
        fun flush(end: Int) {
            if (end <= start) return
            val part = text.substring(start, end)
            if (on) withStyle(strong) { append(part) } else append(part)
        }
        text.forEachIndexed { i, ch ->
            if (ch == MARK_ON || ch == MARK_OFF) {
                flush(i)
                on = ch == MARK_ON
                start = i + 1
            }
        }
        flush(text.length)
    }

    fun safeUrl(u: String?): String? {
        if (u.isNullOrBlank()) return null
        val scheme = runCatching { Uri.parse(u).scheme }.getOrNull()?.lowercase()
        return if (scheme == "http" || scheme == "https") u else null
    }

    /** Images only ever come through this instance's proxy (or inline data). */
    fun imageUrl(u: String?): String? = when {
        u == null -> null
        u.startsWith("/image?") -> BASE_URL + u
        u.startsWith("data:image/") -> u
        else -> null
    }

    /** The original address behind a proxied image, for "Open image". */
    fun originalImage(u: String?): String? =
        if (u != null && u.startsWith("/image?")) safeUrl(Uri.parse(BASE_URL + u).getQueryParameter("url")) else null

    fun host(url: String): String = runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.") ?: ""

    fun favicon(url: String): String? =
        runCatching { Uri.parse(url).host }.getOrNull()?.lowercase()?.let { "$BASE_URL/favicon/$it" }

    fun crumbs(url: String): String {
        val segments = runCatching { Uri.parse(url).pathSegments }.getOrNull().orEmpty().filter { it.isNotBlank() }
        return if (segments.isEmpty()) "" else segments.joinToString(" › ", prefix = " › ")
    }

    private val dateFormat = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

    fun whenText(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        val instant = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(iso).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull()
            ?: return ""
        val millis = instant.toEpochMilli()
        val age = System.currentTimeMillis() - millis
        return if (age in 0 until 30 * DateUtils.DAY_IN_MILLIS) {
            DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        } else {
            dateFormat.format(instant.atZone(ZoneId.systemDefault()))
        }
    }

    private val names = mapOf(
        "google cse" to "Google", "google cse images" to "Google Images", "google scholar" to "Google Scholar",
        "bing" to "Bing", "bing images" to "Bing Images", "bing news" to "Bing News", "bing videos" to "Bing Videos",
        "duckduckgo web" to "DuckDuckGo", "duckduckgo images" to "DuckDuckGo Images",
        "duckduckgo news" to "DuckDuckGo News", "duckduckgo videos" to "DuckDuckGo Videos",
        "yahoo" to "Yahoo", "yep" to "Yep", "wikipedia" to "Wikipedia", "wikidata" to "Wikidata",
        "wikinews" to "Wikinews", "arxiv" to "arXiv", "pubmed" to "PubMed", "semantic scholar" to "Semantic Scholar",
        "europepmc" to "Europe PMC", "reuters" to "Reuters", "youtube" to "YouTube", "dailymotion" to "Dailymotion",
        "github" to "GitHub", "stackoverflow" to "Stack Overflow", "superuser" to "Super User",
        "askubuntu" to "Ask Ubuntu", "docker hub" to "Docker Hub", "mdn" to "MDN", "pypi" to "PyPI",
    )

    fun engineName(e: String): String =
        names[e] ?: e.split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.US) } }

    fun failedNote(engines: List<String>): String? {
        val list = engines.map(::engineName).distinct()
        return when (list.size) {
            0 -> null
            1 -> "${list[0]} didn't answer."
            2 -> "${list[0]} and ${list[1]} didn't answer."
            else -> list.dropLast(1).joinToString(", ") + ", and ${list.last()} didn't answer."
        }
    }
}
