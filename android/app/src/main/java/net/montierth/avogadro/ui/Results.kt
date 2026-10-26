package net.montierth.avogadro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import net.montierth.avogadro.data.Hit
import net.montierth.avogadro.data.Infobox

/** Hits that can be shown as images, in the order the image grid and viewer share. */
fun imageHits(hits: List<Hit>): List<Hit> = hits.filter { Format.imageUrl(it.thumbnail ?: it.image) != null }

@Composable
fun ResultsBody(vm: SearchViewModel, bottomInset: androidx.compose.ui.unit.Dp, open: (String) -> Unit, openImage: (Int) -> Unit) {
    val r = vm.results
    if (r.category == "images") ImageGrid(vm, bottomInset, openImage) else ResultList(vm, bottomInset, open)
}

@Composable
private fun ResultList(vm: SearchViewModel, bottomInset: androidx.compose.ui.unit.Dp, open: (String) -> Unit) {
    val r = vm.results
    val p = LocalPalette.current
    val list = rememberLazyListState()
    LaunchedEffect(r.generation) { list.scrollToItem(0) }
    LazyColumn(
        state = list,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomInset + 96.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item("filters") { FilterRow(vm) }
        r.answers.forEachIndexed { i, a ->
            item("answer-$i") {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 18.dp).drawBehind { seamBelow(p.seam) }) {
                    Text(a.text, style = type(30.sp, 380, p.vapor, lineHeight = 37.sp, tracking = (-0.02).em))
                    Format.safeUrl(a.url)?.let { u ->
                        Text(Format.host(u), style = type(13.sp, 400, p.vapor3), modifier = Modifier.padding(top = 8.dp).clickable { open(u) })
                    }
                    Spacer(Modifier.height(18.dp))
                }
            }
        }
        r.correction?.let { c ->
            item("did-you-mean") {
                Row(Modifier.padding(top = 2.dp, bottom = 12.dp)) {
                    Text("Did you mean ", style = type(15.sp, 400, p.vapor2))
                    Text(c, style = type(15.sp, 600, p.vapor).copy(textDecoration = TextDecoration.Underline), modifier = Modifier.clickable { vm.search(c) })
                    Text("?", style = type(15.sp, 400, p.vapor2))
                }
            }
        }
        r.infobox?.let { box -> item("infobox") { InfoboxView(box, open) } }
        when {
            r.error -> item("error") {
                Message(
                    "The search didn't go through",
                    "Couldn't reach Avogadro. Check that Tailscale is connected, then try again.",
                ) { PrimaryButton("Try again", vm::retry) }
            }
            !r.loading && r.hits.isEmpty() && r.answers.isEmpty() && r.infobox == null -> item("empty") {
                Message("No results for “${r.query}”", null) { if (r.related.isNotEmpty()) Chips(r.related.take(6), { vm.search(it) }) }
            }
        }
        itemsIndexed(r.hits, key = { _, h -> h.url }) { i, hit ->
            Box(Modifier.condense(i, r.generation)) {
                if (hit.template == "videos.html" || r.category == "videos") VideoRow(hit, vm.visited.containsKey(hit.url)) { vm.markVisited(hit.url); open(hit.url) }
                else WebRow(hit, r.category, vm.visited.containsKey(hit.url), open) { vm.markVisited(hit.url); open(hit.url) }
            }
        }
        item("foot") { Foot(vm) }
    }
}

@Composable
private fun FilterRow(vm: SearchViewModel) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.End) {
        TimeFilter(vm.results.time, vm::setTime)
    }
}

@Composable
private fun Foot(vm: SearchViewModel) {
    val r = vm.results
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        when {
            r.loadingMore -> QuietButton("Loading…", {}, enabled = false)
            r.moreFailed -> QuietButton("Couldn't load more. Try again", vm::loadMore)
            r.canLoadMore -> QuietButton("More results", vm::loadMore)
        }
        if (r.related.isNotEmpty() && r.hits.isNotEmpty()) {
            Spacer(Modifier.height(36.dp))
            Chips(r.related, { vm.search(it) })
        }
        Format.failedNote(r.failed)?.let {
            Text(it, style = type(13.sp, 400, p.amber), modifier = Modifier.padding(top = 28.dp))
        }
    }
}

@Composable
private fun Message(title: String, body: String?, action: @Composable () -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 8.dp)) {
        Text(title, style = type(22.sp, 450, p.vapor, lineHeight = 28.sp, tracking = (-0.015).em))
        if (body != null) Text(body, style = type(15.sp, 400, p.vapor2, lineHeight = 22.sp), modifier = Modifier.padding(top = 6.dp))
        Spacer(Modifier.height(18.dp))
        action()
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.seamBelow(color: androidx.compose.ui.graphics.Color) {
    drawLine(color, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
}

@Composable
private fun Favicon(url: String) {
    val p = LocalPalette.current
    var failed by remember(url) { mutableStateOf(false) }
    val model = Format.favicon(url)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(18.dp).clip(RoundedCornerShape(5.dp)).background(p.felt3),
    ) {
        if (model == null || failed) {
            Text(Format.host(url).take(1).uppercase(), style = type(10.5.sp, 650, p.vapor2))
        } else {
            AsyncImage(model, null, modifier = Modifier.size(16.dp), onError = { failed = true })
        }
    }
}

@Composable
private fun SiteLine(url: String, extra: String) {
    val p = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Favicon(url)
        Spacer(Modifier.width(9.dp))
        Text(Format.host(url), style = type(13.sp, 400, p.vapor2), maxLines = 1)
        Text(Format.crumbs(url), style = type(13.sp, 400, p.vapor3), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (extra.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(extra, style = type(13.sp, 400, p.vapor3), maxLines = 1)
        }
    }
}

@Composable
private fun Title(hit: Hit, visited: Boolean) {
    val p = LocalPalette.current
    Text(
        Format.plain(hit.title).ifEmpty { Format.host(hit.url) },
        style = type(17.5.sp, 520, if (visited) p.visited else p.vapor, lineHeight = 23.sp, tracking = (-0.01).em),
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 5.dp, bottom = 4.dp),
    )
}

@Composable
private fun Snippet(hit: Hit, lines: Int) {
    val p = LocalPalette.current
    if (hit.content.isBlank()) return
    Text(
        Format.marked(hit.content, SpanStyle(fontWeight = FontWeight(600), color = p.vapor)),
        style = type(15.sp, 400, p.vapor2, lineHeight = 23.sp),
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun WebRow(hit: Hit, category: String, visited: Boolean, open: (String) -> Unit, onClick: () -> Unit) {
    val p = LocalPalette.current
    val date = if (category == "news" || hit.template == "paper.html") Format.whenText(hit.published) else ""
    val thumb = Format.imageUrl(hit.thumbnail)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            SiteLine(hit.url, date)
            Title(hit, visited)
            Snippet(hit, 3)
            Extras(hit, open)
        }
        if (thumb != null) {
            Spacer(Modifier.width(16.dp))
            AsyncImage(
                thumb, null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(p.felt3).border(1.dp, p.seam, RoundedCornerShape(10.dp)),
            )
        }
    }
}

@Composable
private fun Extras(hit: Hit, open: (String) -> Unit) {
    val p = LocalPalette.current
    val bits = mutableListOf<Pair<String, String?>>()
    if (hit.template == "paper.html") {
        if (hit.authors.isNotEmpty()) bits += (hit.authors.take(3).joinToString(", ") + if (hit.authors.size > 3) " et al." else "") to null
        hit.journal?.let { bits += it to null }
        Format.safeUrl(hit.pdf)?.let { bits += "PDF" to it }
        hit.doi?.takeIf { it.startsWith("10.") }?.let { bits += "DOI $it" to "https://doi.org/$it" }
    } else if (hit.template == "packages.html") {
        hit.maintainer?.let { bits += it to null }
        hit.popularity?.let { bits += it to null }
        hit.license?.let { bits += it to null }
        Format.safeUrl(hit.source)?.takeIf { it != hit.url }?.let { bits += "Source" to it }
        Format.safeUrl(hit.homepage)?.takeIf { it != hit.url }?.let { bits += "Homepage" to it }
    }
    if (bits.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 7.dp)) {
        bits.forEach { (label, link) ->
            if (link == null) Text(label, style = type(13.sp, 400, p.vapor3))
            else Text(label, style = type(13.sp, 400, p.vapor2).copy(textDecoration = TextDecoration.Underline), modifier = Modifier.clickable { open(link) })
        }
    }
}

@Composable
private fun VideoRow(hit: Hit, visited: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    val thumb = Format.imageUrl(hit.thumbnail)
    val meta = listOfNotNull(hit.author, Format.whenText(hit.published).ifEmpty { null }).joinToString(", ")
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Box(Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(p.felt3).border(1.dp, p.seam, RoundedCornerShape(10.dp))) {
            if (thumb != null) AsyncImage(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            hit.length?.let {
                Text(
                    it,
                    style = type(12.sp, 600, androidx.compose.ui.graphics.Color.White),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                        .clip(RoundedCornerShape(5.dp)).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.72f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            SiteLine(hit.url, meta)
            Text(
                Format.plain(hit.title).ifEmpty { Format.host(hit.url) },
                style = type(16.5.sp, 520, if (visited) p.visited else p.vapor, lineHeight = 22.sp),
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun InfoboxView(box: Infobox, open: (String) -> Unit) {
    val p = LocalPalette.current
    val image = Format.imageUrl(box.image)
    val text = Format.plain(box.content).replace(Regex("\\(\\s*,\\s*(also,?\\s*)?"), "(").replace(Regex("\\(\\s*\\)"), "")
    Column(Modifier.fillMaxWidth().padding(bottom = 18.dp).drawBehind { seamBelow(p.seam2) }) {
        Row {
            Column(Modifier.weight(1f)) {
                Text(box.title, style = type(25.sp, 450, p.vapor, lineHeight = 30.sp, tracking = (-0.02).em))
                if (text.isNotBlank()) {
                    Text(text, style = type(14.5.sp, 400, p.vapor2, lineHeight = 23.sp), maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                }
            }
            if (image != null) {
                Spacer(Modifier.width(18.dp))
                AsyncImage(image, null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.size(112.dp).clip(RoundedCornerShape(12.dp)).background(p.felt3))
            }
        }
        if (box.attributes.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            box.attributes.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().drawBehind { drawLine(p.seam, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }.padding(vertical = 8.dp)) {
                    Text(label, style = type(13.5.sp, 400, p.vapor3), modifier = Modifier.width(112.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(value, style = type(13.5.sp, 400, p.vapor))
                }
            }
        }
        val links = box.links.mapNotNull { (name, url) -> Format.safeUrl(url)?.let { name to it } }
        if (links.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                links.forEach { (name, url) ->
                    Text(
                        name, style = type(14.sp, 400, p.vapor2),
                        modifier = Modifier.clip(RoundedCornerShape(50)).border(1.dp, p.seam2, RoundedCornerShape(50)).clickable { open(url) }.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun ImageGrid(vm: SearchViewModel, bottomInset: androidx.compose.ui.unit.Dp, openImage: (Int) -> Unit) {
    val r = vm.results
    val p = LocalPalette.current
    val images = imageHits(r.hits)
    val grid = rememberLazyStaggeredGridState()
    LaunchedEffect(r.generation) { grid.scrollToItem(0) }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Adaptive(150.dp),
        state = grid,
        verticalItemSpacing = 6.dp,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = bottomInset + 96.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item("filters", span = StaggeredGridItemSpan.FullLine) { FilterRow(vm) }
        if (r.error || (!r.loading && images.isEmpty())) {
            item("state", span = StaggeredGridItemSpan.FullLine) {
                if (r.error) Message("The search didn't go through", "Couldn't reach Avogadro. Check that Tailscale is connected, then try again.") { PrimaryButton("Try again", vm::retry) }
                else Message("No results for “${r.query}”", null) {}
            }
        }
        itemsIndexed(images, key = { _, h -> h.url + (h.image ?: "") }) { i, hit ->
            val ratio = Regex("(\\d+)\\s*[×x]\\s*(\\d+)").find(hit.resolution ?: "")?.let { m ->
                val w = m.groupValues[1].toFloat()
                val h = m.groupValues[2].toFloat()
                if (h > 0) (w / h).coerceIn(0.5f, 2.4f) else null
            } ?: 1.3f
            AsyncImage(
                Format.imageUrl(hit.thumbnail ?: hit.image), Format.plain(hit.title),
                contentScale = ContentScale.Crop,
                modifier = Modifier.condense(i, r.generation).fillMaxWidth().aspectRatio(ratio)
                    .clip(RoundedCornerShape(10.dp)).background(p.felt3).clickable { openImage(i) },
            )
        }
        item("foot", span = StaggeredGridItemSpan.FullLine) { Foot(vm) }
    }
}
