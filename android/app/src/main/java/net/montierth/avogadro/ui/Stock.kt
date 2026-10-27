package net.montierth.avogadro.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import net.montierth.avogadro.data.Point
import net.montierth.avogadro.data.Quote

private val SPANS = mapOf(
    "1D" to "today", "5D" to "past 5 days", "1M" to "past month", "6M" to "past 6 months",
    "YTD" to "year to date", "1Y" to "past year", "5Y" to "past 5 years", "MAX" to "all time",
)

// Exchange time: a US session reads 9:30 to 4 wherever the reader is.
private fun et(pattern: String) = DateTimeFormatter.ofPattern(pattern, Locale.US).withZone(ZoneId.of("America/New_York"))
private val etTime = et("h:mm a")
private val etDayTime = et("MMM d, h:mm a")
private val etDay = et("MMM d, yyyy")
private val etDate = et("MMM d")
private val etMonth = et("MMM yyyy")
private val etYear = et("yyyy")
private val etHour = et("h a")
private fun DateTimeFormatter.at(seconds: Long): String = format(Instant.ofEpochSecond(seconds))

private fun figures(v: Double, min: Int, max: Int): String = NumberFormat.getNumberInstance(Locale.US).apply {
    minimumFractionDigits = min
    maximumFractionDigits = max
    roundingMode = RoundingMode.HALF_UP
}.format(v)

private fun money(v: Double?): String = if (v == null) "–" else figures(v, 2, if (abs(v) < 1) 4 else 2)
private fun signed(v: Double): String = (if (v < 0) "−" else "+") + money(abs(v))
private fun percent(v: Double): String = figures(v, 2, 2)
private fun move(change: Double, pct: Double): String = "${signed(change)} (${percent(abs(pct))}%)"

private fun TextStyle.tnum(): TextStyle = copy(fontFeatureSettings = "tnum")

@Composable
private fun trend(v: Double?): Color? {
    val p = LocalPalette.current
    return when {
        v == null -> null
        v > 0 -> p.gain
        v < 0 -> p.loss
        else -> null
    }
}

/** A quote above the results, like an answer ending on a seam: price, change, a chart by range
 *  that can be scrubbed (rewriting the price and change line, no tooltip), and nine figures. */
@Composable
fun StockCardView(card: StockCard, onRange: (String) -> Unit) {
    val p = LocalPalette.current
    val s = card.base
    val d = card.quote
    val range = card.shown
    val pts = d.points
    // < 0 shows the latest price; otherwise point i of the chart.
    var scrub by remember(card.shown, d) { mutableIntStateOf(-1) }
    val i = scrub.takeIf { it in pts.indices } ?: -1

    var price = d.price
    var change: Double? = null
    var pct: Double? = null
    val label = if (i >= 0) {
        price = pts[i].v
        val t = pts[i].t
        when (range) {
            "1D" -> etTime.at(t) + " ET"
            "5D" -> etDayTime.at(t) + " ET"
            else -> etDay.at(t)
        }
    } else {
        SPANS[range] ?: ""
    }
    val ref = if (range == "1D") d.prevClose else pts.firstOrNull()?.v
    if (range == "1D" && i < 0) {
        change = d.change
        pct = d.changePct
    } else if (ref != null && ref != 0.0) {
        change = price - ref
        pct = change / ref * 100
    }

    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 22.dp).drawBehind { seamBelow(p.seam) }) {
        Text(s.name, style = type(17.sp, 500, p.vapor, lineHeight = 25.sp, tracking = (-0.01).em))
        Text(
            listOfNotNull(s.exchange, s.symbol).joinToString(": "),
            style = type(13.sp, 400, p.vapor3, lineHeight = 19.sp),
            modifier = Modifier.padding(top = 1.dp),
        )
        Row(Modifier.padding(top = 14.dp)) {
            Text(
                money(price),
                style = type(32.sp, 380, p.vapor, lineHeight = 35.sp, tracking = (-0.02).em).tnum(),
                modifier = Modifier.alignByBaseline(),
            )
            s.currency?.let {
                Spacer(Modifier.width(8.dp))
                Text(it, style = type(14.sp, 450, p.vapor3), modifier = Modifier.alignByBaseline())
            }
        }
        Text(
            buildAnnotatedString {
                if (change != null) append(move(change, pct ?: 0.0) + " ")
                withStyle(SpanStyle(fontWeight = FontWeight(400), color = p.vapor2)) { append(label) }
            },
            style = type(15.sp, 520, trend(change) ?: p.vapor, lineHeight = 22.sp).tnum(),
            modifier = Modifier.padding(top = 4.dp),
        )
        Meta(s)
        RangeTabs(card.range, onRange)
        StockChart(d, range, i, card.loading) { scrub = it }
        Stats(s)
        Spacer(Modifier.height(20.dp))
    }
}

/** Close or live time in ET, and the pre-market or after-hours price. */
@Composable
private fun Meta(s: Quote) {
    val p = LocalPalette.current
    val asOf = s.asOf
    val time = when {
        asOf == null -> ""
        s.state == "open" -> "Open · ${etTime.at(asOf)} ET"
        else -> "At close · ${etDayTime.at(asOf)} ET"
    }
    val ext = s.extended
    val extColor = trend(ext?.change) ?: p.vapor3
    val text = buildAnnotatedString {
        append(time)
        if (ext != null) {
            if (time.isNotEmpty()) append(" · ")
            append(if (ext.state == "pre") "Pre-market " else "After hours ")
            append(money(ext.price) + " ")
            ext.change?.let { c -> withStyle(SpanStyle(color = extColor)) { append(move(c, ext.changePct ?: 0.0)) } }
        }
    }
    if (text.isNotEmpty()) {
        Text(text, style = type(13.sp, 400, p.vapor3, lineHeight = 19.sp).tnum(), modifier = Modifier.padding(top = 6.dp))
    }
}

/** Range buttons styled like the result tabs: the current one sits on a 2dp LED underline. */
@Composable
private fun RangeTabs(current: String, onPick: (String) -> Unit) {
    val p = LocalPalette.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.padding(top = 14.dp).fillMaxWidth().drawBehind { seamBelow(p.seam) }.horizontalScroll(rememberScrollState()),
    ) {
        RANGES.forEach { r ->
            val on = r == current
            Text(
                r,
                style = type(13.5.sp, 500, if (on) p.vapor else p.vapor3).tnum(),
                modifier = Modifier
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPick(r) }
                    .drawBehind {
                        if (on) {
                            drawRoundRect(
                                p.led, topLeft = Offset(0f, size.height - 2.dp.toPx()), size = Size(size.width, 2.dp.toPx()),
                                cornerRadius = CornerRadius(2.dp.toPx()),
                            )
                        }
                    }
                    .padding(top = 8.dp, bottom = 10.dp),
            )
        }
    }
}

/** Open, high, low; market cap, P/E, dividend yield; previous close, 52-week high and low. */
@Composable
private fun Stats(s: Quote) {
    val p = LocalPalette.current
    val st = s.stats
    val columns = listOf(
        listOf("Open" to money(st.open), "High" to money(st.high), "Low" to money(st.low)),
        listOf("Mkt cap" to (st.mktCap ?: "–"), "P/E ratio" to money(st.pe), "Div yield" to (st.divYield?.let { percent(it) + "%" } ?: "–")),
        listOf("Prev close" to money(s.prevClose), "52-wk high" to money(st.wk52High), "52-wk low" to money(st.wk52Low)),
    )
    Row(Modifier.padding(top = 14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        columns.forEach { rows ->
            Column(Modifier.weight(1f)) {
                rows.forEach { (k, v) ->
                    Column(
                        Modifier.fillMaxWidth()
                            .drawBehind { drawLine(p.seam, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
                            .padding(vertical = 7.dp),
                    ) {
                        Text(k, style = type(13.5.sp, 400, p.vapor3, lineHeight = 20.sp), maxLines = 1, softWrap = false)
                        Text(v, style = type(13.5.sp, 400, p.vapor, lineHeight = 20.sp).tnum(), maxLines = 1, modifier = Modifier.padding(top = 1.dp))
                    }
                }
            }
        }
    }
}

/** The chart's layout in px: the web card's, from dp. 1D spans the session (so a day in progress
 *  draws partway) with the previous close in the scale; other ranges are spaced by point. */
private class Plot(d: Quote, range: String, width: Float, height: Float, density: Density) {
    val pts: List<Point> = d.points
    val top: Float = with(density) { 8.dp.toPx() }
    val pw: Float = width - with(density) { 46.dp.toPx() }
    val ph: Float = height - with(density) { 22.dp.toPx() } - top
    val ref: Double? = if (range == "1D") d.prevClose else null
    private val session = if (range == "1D") d.session else null
    private val lo: Double
    private val hi: Double

    init {
        val vals = pts.map { it.v } + listOfNotNull(ref)
        val min = vals.minOrNull() ?: 0.0
        val max = vals.maxOrNull() ?: 0.0
        val pad = ((max - min) * 0.08).takeIf { it != 0.0 } ?: (abs(max) * 0.01).takeIf { it != 0.0 } ?: 1.0
        lo = min - pad
        hi = max + pad
    }

    fun x(i: Int): Float {
        val s = session ?: return i.toFloat() / (pts.size - 1) * pw
        return ((pts[i].t - s.first).toDouble() / (s.second - s.first) * pw).toFloat().coerceIn(0f, pw)
    }

    fun y(v: Double): Float = (top + (1 - (v - lo) / (hi - lo)) * ph).toFloat()

    /** The point under a finger at [px]. */
    fun pick(px: Float): Int {
        val x = px.coerceIn(0f, pw)
        val s = session ?: return (x / pw * (pts.size - 1)).roundToInt()
        val t = s.first + (x / pw).toDouble() * (s.second - s.first)
        var best = 0
        for (i in 1 until pts.size) if (abs(pts[i].t - t) < abs(pts[best].t - t)) best = i
        return best
    }

    fun priceTicks(): List<Double> = niceTicks(lo, hi, 3)

    /** (x, label, anchor) along the bottom: session hours on 1D, day starts on 5D, else four even dates. */
    fun timeTicks(range: String, density: Density): List<Triple<Float, String, Anchor>> {
        val out = mutableListOf<Triple<Float, String, Anchor>>()
        val s = session
        if (s != null) {
            val edge = pw - with(density) { 20.dp.toPx() }
            for (hr in listOf(10, 12, 14, 16)) {
                val t = s.first + ((hr - 9.5) * 3600).toLong()
                if (t > s.second) continue
                val x = ((t - s.first).toDouble() / (s.second - s.first) * pw).toFloat()
                out += Triple(x, etHour.at(t), if (x > edge) Anchor.End else Anchor.Middle)
            }
            return out
        }
        if (range == "5D") {
            var day = ""
            pts.forEachIndexed { i, pt ->
                val d = etDate.at(pt.t)
                if (d != day) out += Triple(x(i), d, if (i > 0) Anchor.Middle else Anchor.Start)
                day = d
            }
            return out
        }
        val fmt = when (range) {
            "1M" -> etDate
            "5Y", "MAX" -> etYear
            else -> etMonth
        }
        for (k in 0 until 4) {
            val i = (k * (pts.size - 1) / 4.0).roundToInt()
            out += Triple(x(i), fmt.at(pts[i].t), if (k > 0) Anchor.Middle else Anchor.Start)
        }
        return out
    }
}

private enum class Anchor { Start, Middle, End }

private fun niceTicks(lo: Double, hi: Double, n: Int): List<Double> {
    val raw = (hi - lo) / n
    val mag = 10.0.pow(floor(log10(raw)))
    val f = raw / mag
    val step = (if (f < 1.5) 1.0 else if (f < 3) 2.0 else if (f < 7) 5.0 else 10.0) * mag
    val out = mutableListOf<Double>()
    var v = ceil(lo / step) * step
    while (v <= hi && out.size < 20) {
        out += BigDecimal(v).setScale(10, RoundingMode.HALF_UP).toDouble()
        v += step
    }
    return out
}

private fun priceTick(v: Double, step: Double): String {
    val digits = if (step >= 1) 0 else if (step >= 0.1) 1 else 2
    return figures(v, digits, digits)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.label(
    measurer: TextMeasurer, style: TextStyle, x: Float, baseline: Float, text: String, anchor: Anchor,
) {
    val layout = measurer.measure(text, style)
    val left = when (anchor) {
        Anchor.Start -> x
        Anchor.Middle -> x - layout.size.width / 2f
        Anchor.End -> x - layout.size.width
    }
    drawText(layout, topLeft = Offset(left, baseline - layout.firstBaseline))
}

/** A 1.7dp line in gain or loss over a fade, seam gridlines labelled in the right gutter, times
 *  along the bottom, the previous close dashed on 1D. A horizontal drag scrubs; a vertical one
 *  scrolls the results. */
@Composable
private fun StockChart(d: Quote, range: String, index: Int, loading: Boolean, onScrub: (Int) -> Unit) {
    val p = LocalPalette.current
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val labelStyle = type(11.5.sp, 400, p.vapor3).tnum()
    val dim by animateFloatAsState(if (loading) 0.45f else 1f, tween(200), label = "chart")
    val scrub by rememberUpdatedState(onScrub)
    val pts = d.points
    val last = pts.lastOrNull()?.v
    val first = pts.firstOrNull()?.v
    val ref = if (range == "1D") d.prevClose else null
    val color = trend(if (last != null && first != null) last - (ref ?: first) else null) ?: p.gain
    val description = if (first != null && last != null) "${d.symbol}, ${SPANS[range]}: ${money(first)} to ${money(last)}" else d.symbol

    Canvas(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .height(176.dp)
            .graphicsLayer { alpha = dim }
            .semantics { contentDescription = description }
            .pointerInput(d, range) {
                if (pts.size < 2) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val plot = Plot(d, range, size.width.toFloat(), size.height.toFloat(), this)
                    scrub(plot.pick(down.position.x))
                    val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                    if (drag != null) {
                        scrub(plot.pick(drag.position.x))
                        horizontalDrag(drag.id) { change ->
                            scrub(plot.pick(change.position.x))
                            change.consume()
                        }
                    }
                    scrub(-1)
                }
            },
    ) {
        if (pts.size < 2) return@Canvas
        val plot = Plot(d, range, size.width, size.height, this)
        val ticks = plot.priceTicks()
        val step = if (ticks.size > 1) ticks[1] - ticks[0] else 1.0
        val hairline = 1.dp.toPx()
        for (v in ticks) {
            val y = plot.y(v)
            drawLine(p.seam, Offset(0f, y), Offset(plot.pw, y), hairline)
            label(measurer, labelStyle, plot.pw + 8.dp.toPx(), y + 4.dp.toPx(), priceTick(v, step), Anchor.Start)
        }
        for ((x, text, anchor) in plot.timeTicks(range, this)) label(measurer, labelStyle, x, size.height - 5.dp.toPx(), text, anchor)
        plot.ref?.let { r ->
            val y = plot.y(r)
            drawLine(
                p.vapor3, Offset(0f, y), Offset(plot.pw, y), hairline,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())),
            )
        }

        val line = Path()
        var peak = Float.MAX_VALUE
        pts.forEachIndexed { i, pt ->
            val x = plot.x(i)
            val y = plot.y(pt.v)
            peak = minOf(peak, y)
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val floor = plot.top + plot.ph
        val area = Path().apply {
            addPath(line)
            lineTo(plot.x(pts.size - 1), floor)
            lineTo(plot.x(0), floor)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.2f), color.copy(alpha = 0f)), startY = peak, endY = floor))
        drawPath(line, color, style = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        if (index in pts.indices) {
            val x = plot.x(index)
            val y = plot.y(pts[index].v)
            drawLine(p.vapor3, Offset(x, plot.top), Offset(x, floor), hairline)
            drawCircle(p.felt, 5.dp.toPx(), Offset(x, y))
            drawCircle(color, 3.dp.toPx(), Offset(x, y))
        }
    }
}
