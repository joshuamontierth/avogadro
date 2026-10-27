package net.montierth.avogadro.ui

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CITE = Regex("\\s*\\[(\\d+(?:\\s*,\\s*\\d+)*)\\]")
private val PARTIAL = Regex("\\s*\\[[\\d,\\s]*$") // a citation still being written
private val EMPHASIS = Regex("\\*\\*|__")
private val SPACES = Regex("\\s+")
private const val JOIN = '⁠' // word joiner: citations keep to the line of the word they follow

/** Source numbers cited in [text] that name one of [count] sources, in order of appearance. */
private fun cited(text: String, count: Int): List<Int> =
    CITE.findAll(text).flatMap { m -> m.groupValues[1].split(",").mapNotNull { it.trim().toIntOrNull() } }
        .filter { it in 1..count }.toList()

/** A question's written answer, above the results: waiting lines until the first words, then
 *  the answer as it is written, each sentence citing its sources, and once it is complete the
 *  cited results as chips. Like an answer it is not a box and ends on a seam. */
@Composable
fun AnswerCardView(card: AskCard, open: (String) -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 20.dp).drawBehind { seamBelow(p.seam) }) {
        if (card.text.isBlank()) WaitingLines() else AnswerText(card, open)
        if (card.done) SourceChips(card, open)
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun WaitingLines() {
    val p = LocalPalette.current
    val still = LocalReducedMotion.current
    val breath = rememberInfiniteTransition(label = "ask-wait")
    Column(Modifier.padding(top = 7.dp, bottom = 5.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(0.92f to 0, 1f to 150, 0.56f to 300).forEach { (width, delay) ->
            val a by breath.animateFloat(
                0.45f, 1f,
                infiniteRepeatable(tween(1100, easing = EaseInOut), RepeatMode.Reverse, StartOffset(delay)),
                label = "ask-line",
            )
            Box(
                Modifier.fillMaxWidth(width).height(11.dp)
                    .graphicsLayer { alpha = if (still) 1f else a }
                    .clip(RoundedCornerShape(6.dp)).background(p.felt3),
            )
        }
    }
}

@Composable
private fun AnswerText(card: AskCard, open: (String) -> Unit) {
    val p = LocalPalette.current
    val count = card.sources.size
    val text = remember(card.text, card.done) {
        var s = card.text.replace(EMPHASIS, "").replace(SPACES, " ").trim()
        if (!card.done) s = s.replace(PARTIAL, "")
        buildAnnotatedString {
            var at = 0
            for (m in CITE.findAll(s)) {
                append(s.substring(at, m.range.first))
                cited(m.value, count).forEach { n ->
                    append(JOIN)
                    appendInlineContent("cite-$n", "[$n]")
                }
                at = m.range.last + 1
            }
            append(s.substring(at))
        }
    }
    val inline = remember(card.sources, open) {
        card.sources.indices.associate { i ->
            "cite-${i + 1}" to InlineTextContent(Placeholder(23.sp, 19.sp, PlaceholderVerticalAlign.TextCenter)) {
                Cite(i + 1) { open(card.sources[i].url) }
            }
        }
    }
    Text(text, inlineContent = inline, style = type(17.sp, 400, p.vapor, lineHeight = 27.sp))
}

/** A citation: the source's number in a small bordered box that opens it. */
@Composable
private fun Cite(n: Int, onClick: () -> Unit) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.fillMaxSize().padding(start = 4.dp), contentAlignment = Alignment.Center) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxHeight().aspectRatio(1f).clip(shape).border(1.dp, p.seam2, shape).clickable(onClick = onClick),
        ) {
            Text("$n", style = type(11.5.sp, 600, p.vapor2))
        }
    }
}

@Composable
private fun SourceChips(card: AskCard, open: (String) -> Unit) {
    val p = LocalPalette.current
    val used = cited(card.text, card.sources.size).distinct().sorted()
    if (used.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 14.dp),
    ) {
        used.forEach { n ->
            val url = card.sources[n - 1].url
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, p.seam2, RoundedCornerShape(50))
                    .clickable { open(url) }.padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
            ) {
                Text("$n", style = type(13.sp, 600, p.vapor3), modifier = Modifier.widthIn(min = 10.dp))
                Spacer(Modifier.width(8.dp))
                Favicon(url, 16)
                Spacer(Modifier.width(8.dp))
                Text(Format.host(url), style = type(13.sp, 400, p.vapor2), maxLines = 1)
            }
        }
    }
}
