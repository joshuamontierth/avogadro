package net.montierth.avogadro.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Out = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)

@Composable
fun Glyph(icon: ImageVector, size: Int, tint: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Image(icon, contentDescription = null, modifier = modifier.size(size.dp), colorFilter = ColorFilter.tint(tint))
}

/** The search field: raised felt, one seam, an LED caret. */
@Composable
fun SearchField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
    large: Boolean,
    focus: FocusRequester,
    onBounds: (Rect) -> Unit,
    onFocused: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = LocalPalette.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(if (large) 16.dp else 14.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = type(if (large) 18.sp else 16.sp, 420, p.vapor),
        cursorBrush = SolidColor(p.led),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(onSearch = { onSearch(value.text) }),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused; onFocused(it.isFocused) }
            .onGloballyPositioned { onBounds(it.boundsInRoot()) },
        decorationBox = { inner ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (large) 58.dp else 48.dp)
                    .clip(shape)
                    .background(p.felt2)
                    .border(1.dp, if (focused) p.led.copy(alpha = 0.55f) else p.seam2, shape)
                    .padding(start = 17.dp, end = 6.dp),
            ) {
                Glyph(Icons.Search, 20, p.vapor3)
                Spacer(Modifier.width(13.dp))
                Box(Modifier.weight(1f)) { inner() }
                if (value.text.isNotEmpty()) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClear),
                    ) { Glyph(Icons.Close, 20, p.vapor3) }
                }
            }
        },
    )
}

/** Suggestions float 6dp under the field, over whatever is below. */
@Composable
fun SuggestionList(words: List<String>, typed: String, field: Rect, onPick: (String) -> Unit) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val shape = RoundedCornerShape(14.dp)
    val strong = SpanStyle(fontWeight = FontWeight(600), color = p.vapor)
    Column(
        Modifier
            .offset { IntOffset(field.left.toInt(), (field.bottom + with(density) { 6.dp.toPx() }).toInt()) }
            .width(with(density) { field.width.toDp() })
            .shadow(16.dp, shape, ambientColor = p.shadow, spotColor = p.shadow)
            .clip(shape)
            .background(p.felt2)
            .border(1.dp, p.seam2, shape)
            .padding(6.dp),
    ) {
        words.forEach { word ->
            val lead = typed.trim()
            val label = buildAnnotatedString {
                if (word.startsWith(lead, ignoreCase = true) && lead.isNotEmpty()) {
                    append(word.substring(0, lead.length))
                    withStyle(strong) { append(word.substring(lead.length)) }
                } else {
                    append(word)
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .clickable { onPick(word) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Glyph(Icons.Search, 17, p.vapor3)
                Spacer(Modifier.width(14.dp))
                Text(label, style = type(16.sp, 400, p.vapor2), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Text tabs; the current one sits on a 2dp LED underline. */
@Composable
fun Tabs(current: String, onPick: (String) -> Unit) {
    val p = LocalPalette.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
    ) {
        CATEGORIES.forEach { (id, label) ->
            val on = id == current
            Text(
                label,
                style = type(14.5.sp, 500, if (on) p.vapor else p.vapor3),
                modifier = Modifier
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPick(id) }
                    .drawBehind {
                        if (on) drawRect(p.led, topLeft = Offset(0f, size.height - 2.dp.toPx()), size = androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx()))
                    }
                    .padding(top = 10.dp, bottom = 12.dp),
            )
        }
    }
}

/** The header's lower edge is the light source; while a search runs, an ion streaks along it. */
@Composable
fun LightEdge(loading: Boolean) {
    val p = LocalPalette.current
    BoxWithConstraints(Modifier.fillMaxWidth().height(1.dp).background(p.led.copy(alpha = 0.26f))) {
        if (loading) {
            val width = constraints.maxWidth.toFloat()
            val streak = with(LocalDensity.current) { 180.dp.toPx() }
            val x by rememberInfiniteTransition(label = "ion").animateFloat(
                initialValue = -streak,
                targetValue = width,
                animationSpec = infiniteRepeatable(tween(1050, easing = LinearEasing), RepeatMode.Restart),
                label = "ion-x",
            )
            Box(
                Modifier
                    .offset { IntOffset(x.toInt(), 0) }
                    .width(180.dp)
                    .height(1.dp)
                    .background(Brush.horizontalGradient(listOf(p.led.copy(alpha = 0f), p.led, p.vapor))),
            )
        }
    }
}

/** The light spilling from the header onto the page. */
@Composable
fun LightSpill(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(modifier.fillMaxWidth().height(64.dp).background(Brush.verticalGradient(listOf(p.led.copy(alpha = 0.075f), p.led.copy(alpha = 0f)))))
}

@Composable
fun TimeFilter(current: String, onPick: (String) -> Unit) {
    val p = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    val label = TIMES.firstOrNull { it.first == current }?.second ?: "Any time"
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(9.dp))
                .border(1.dp, p.seam2, RoundedCornerShape(9.dp))
                .clickable { open = true }
                .padding(start = 11.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Text(label, style = type(13.5.sp, 500, p.vapor2))
            Spacer(Modifier.width(6.dp))
            Glyph(Icons.ChevronDown, 15, p.vapor3)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = p.felt2) {
            TIMES.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name, style = type(15.sp, if (id == current) 600 else 400, if (id == current) p.vapor else p.vapor2)) },
                    onClick = { open = false; onPick(id) },
                )
            }
        }
    }
}

@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(11.dp)).background(p.vapor).clickable(onClick = onClick).padding(horizontal = 18.dp),
    ) { Text(label, style = type(14.5.sp, 550, p.felt)) }
}

@Composable
fun QuietButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = LocalPalette.current
    val shape = RoundedCornerShape(11.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(p.felt2)
            .border(1.dp, p.seam2, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
    ) { Text(label, style = type(14.5.sp, 550, p.vapor)) }
}

@Composable
fun Chips(words: List<String>, onPick: (String) -> Unit, withIcon: Boolean = true) {
    val p = LocalPalette.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        words.forEach { word ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, p.seam2, RoundedCornerShape(50))
                    .clickable { onPick(word) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                if (withIcon) {
                    Glyph(Icons.Search, 15, p.vapor3)
                    Spacer(Modifier.width(8.dp))
                }
                Text(word, style = type(14.sp, 400, p.vapor2))
            }
        }
    }
}

/** The one authored moment: results condense out of blur, staggered 32ms. */
@Composable
fun Modifier.condense(index: Int, generation: Int): Modifier {
    if (LocalReducedMotion.current) return this
    val progress = remember(generation) { Animatable(if (index < 15) 0f else 1f) }
    LaunchedEffect(generation) {
        if (progress.value < 1f) {
            kotlinx.coroutines.delay(index * 32L)
            progress.animateTo(1f, tween(620, easing = Out))
        }
    }
    return graphicsLayer {
        val k = progress.value
        alpha = k
        translationY = (1 - k) * 6.dp.toPx()
        if (Build.VERSION.SDK_INT >= 31 && k < 1f) {
            val r = (1 - k) * 7.dp.toPx()
            renderEffect = if (r > 0.5f) BlurEffect(r, r, TileMode.Decal) else null
        } else {
            renderEffect = null
        }
    }
}
