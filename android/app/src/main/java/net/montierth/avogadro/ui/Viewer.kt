package net.montierth.avogadro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import net.montierth.avogadro.data.Hit

/** Full-screen image viewer: swipe between results; the full image loads through the proxy. */
@Composable
fun ImageViewer(images: List<Hit>, start: Int, open: (String) -> Unit, onClose: () -> Unit) {
    val p = LocalPalette.current
    BackHandler(onBack = onClose)
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (images.size - 1).coerceAtLeast(0))) { images.size }
    Box(
        Modifier
            .fillMaxSize()
            .background(p.felt.copy(alpha = 0.97f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val hit = images[page]
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(top = 64.dp, start = 12.dp, end = 12.dp), contentAlignment = Alignment.Center) {
                    // The thumbnail shows at once; the full image replaces it when it arrives.
                    AsyncImage(Format.imageUrl(hit.thumbnail), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    Format.imageUrl(hit.image)?.let { full ->
                        AsyncImage(full, Format.plain(hit.title), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                }
                Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 24.dp)) {
                    Text(
                        Format.plain(hit.title).ifEmpty { Format.host(hit.url) },
                        style = type(19.sp, 500, p.vapor, lineHeight = 26.sp),
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    val meta = listOfNotNull(Format.host(hit.url).ifEmpty { null }, hit.resolution, hit.format)
                    if (meta.isNotEmpty()) {
                        Text(meta.joinToString("   "), style = type(13.5.sp, 400, p.vapor3), modifier = Modifier.padding(top = 8.dp, bottom = 18.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Format.safeUrl(hit.url)?.let { url -> PrimaryButton("Visit page", { open(url) }) }
                        Format.originalImage(hit.image)?.let { url -> QuietButton("Open image", { open(url) }) }
                    }
                }
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(14.dp)
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(p.felt.copy(alpha = 0.6f))
                .border(1.dp, p.seam2, RoundedCornerShape(12.dp))
                .clickable(onClick = onClose),
        ) { Glyph(Icons.Close, 20, p.vapor) }
    }
}
