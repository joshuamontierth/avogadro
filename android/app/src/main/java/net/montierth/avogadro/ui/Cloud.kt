package net.montierth.avogadro.ui

import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.delay

/*
 * The background is a hydrogen atom's electron cloud, the same one the web page draws
 * (frontend/cloud.js). Every dot is one sample of where the electron could be found, so
 * dense regions glow and the nodes stay dark. The atom turns slowly and steps through real
 * orbitals; the two inks are the two signs of the wavefunction. A search excites it into a
 * new orbital and it gives off a photon. On home the nucleus sits behind the search field;
 * on results (a phone has no margins) the atom fades away.
 */

/** Radial part R(r) in Bohr radii (unnormalized) and the real angular part on the unit sphere. */
private class Orbital(val n: Int, val radial: (Double) -> Double, val angular: (Double, Double, Double) -> Double) {
    var rs = DoubleArray(0)
    var cdf = DoubleArray(0)
    var scale = 1.0
    var ymax = 1.0
    var ready = false
}

/** Sampling |ψ|^(2·SHARP) instead of |ψ|² tightens the lobes so each shape reads at a glance. */
private const val SHARP = 1.7
private const val PERSP = 3.6f

private val ORBITALS = listOf(
    Orbital(1, { r -> exp(-r) }, { _, _, _ -> 1.0 }), // 1s
    Orbital(2, { r -> r * exp(-r / 2) }, { _, _, z -> z }), // 2p z
    Orbital(3, { r -> r * r * exp(-r / 3) }, { _, _, z -> 3 * z * z - 1 }), // 3d z²
    Orbital(3, { r -> (27 - 18 * r + 2 * r * r) * exp(-r / 3) }, { _, _, _ -> 1.0 }), // 3s
    Orbital(3, { r -> r * r * exp(-r / 3) }, { x, y, _ -> x * x - y * y }), // 3d x²−y²
    Orbital(4, { r -> r * r * r * exp(-r / 4) }, { x, y, z -> x * y * z }), // 4f xyz
    Orbital(3, { r -> r * (6 - r) * exp(-r / 3) }, { x, _, _ -> x }), // 3p x
    Orbital(4, { r -> r * r * r * exp(-r / 4) }, { _, _, z -> z * (5 * z * z - 3) }), // 4f z³
)
private const val STILL = 2 // what reduced motion shows: 3d z²

private fun prepare(o: Orbital): Orbital {
    if (o.ready) return o
    val steps = 900
    val rmax = 3.0 * o.n * (o.n + 2)
    o.rs = DoubleArray(steps + 1)
    o.cdf = DoubleArray(steps + 1)
    var acc = 0.0
    for (i in 0..steps) {
        val r = i.toDouble() / steps * rmax
        acc += r * r * abs(o.radial(r)).pow(2 * SHARP)
        o.rs[i] = r
        o.cdf[i] = acc
    }
    for (i in 0..steps) o.cdf[i] /= acc
    var k = 0
    while (o.cdf[k] < 0.9) k++
    o.scale = 1.0 / o.rs[k] // 90% of the cloud inside the unit radius
    var ymax = 0.0
    repeat(6000) {
        val z = 2 * Random.nextDouble() - 1
        val t = 2 * PI * Random.nextDouble()
        val s = sqrt(1 - z * z)
        ymax = max(ymax, abs(o.angular(s * cos(t), s * sin(t), z)).pow(2 * SHARP))
    }
    o.ymax = ymax * 1.05
    o.ready = true
    return o
}

private fun radius(o: Orbital): Double {
    val u = Random.nextDouble()
    var lo = 0
    var hi = o.cdf.size - 1
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (o.cdf[mid] < u) lo = mid + 1 else hi = mid
    }
    if (lo == 0) return 0.0
    val span = o.cdf[lo] - o.cdf[lo - 1]
    val f = if (span > 0) (u - o.cdf[lo - 1]) / span else 0.0
    return o.rs[lo - 1] + f * (o.rs[lo] - o.rs[lo - 1])
}

/** Writes n samples (x, y, z, sign) into out. */
private fun sample(index: Int, out: FloatArray, n: Int) {
    val o = prepare(ORBITALS[index])
    for (i in 0 until n) {
        var x: Double
        var y: Double
        var z: Double
        var v: Double
        do {
            z = 2 * Random.nextDouble() - 1
            val t = 2 * PI * Random.nextDouble()
            val s = sqrt(1 - z * z)
            x = s * cos(t)
            y = s * sin(t)
            v = o.angular(x, y, z)
        } while (Random.nextDouble() * o.ymax > abs(v).pow(2 * SHARP))
        val r = radius(o)
        val k = r * o.scale
        val j = i * 4
        out[j] = (x * k).toFloat()
        out[j + 1] = (y * k).toFloat()
        out[j + 2] = (z * k).toFloat()
        out[j + 3] = if (o.radial(r) * v < 0) 1f else 0f
    }
}

private fun ease(k: Float): Float = if (k < 0.5f) 4 * k * k * k else 1 - (-2 * k + 2).pow(3) / 2

private class Atom(val count: Int, var orbital: Int, x: Float, y: Float, s: Float, now: Double) {
    val pos = FloatArray(count * 4)
    val src = FloatArray(count * 4)
    val dst = FloatArray(count * 4)
    val pool = FloatArray(count * 4)
    var poolAt = 0
    val blink = FloatArray(count) { Random.nextFloat() }
    var morph = 1f
    var morphStart = 0.0
    var morphDur = 1.0
    var yaw = Random.nextFloat() * 2 * PI.toFloat()
    var tilt = Random.nextFloat() * 2 * PI.toFloat()
    var next = now + 6 + Random.nextDouble() * 3
    var cx = x; var cy = y; var size = s
    var tcx = x; var tcy = y; var tsize = s
    var fade = 0f
    var tfade = 1f

    init {
        sample(orbital, pos, count)
        sample(orbital, pool, count)
    }

    fun jump(to: Int, now: Double, dur: Double) {
        pos.copyInto(src)
        sample(to, dst, count)
        sample(to, pool, count)
        orbital = to
        morph = 0f
        morphStart = now
        morphDur = dur
        next = now + dur + 7 + Random.nextDouble() * 3
    }
}

private class Photon(val cx: Float, val cy: Float, val r: Float, val born: Double)

private class CloudModel {
    var atoms = mutableListOf<Atom>()
    val photons = mutableListOf<Photon>()
    var count = 0
    private val bins = Array(6) { FloatArray(0) }
    private val binCount = IntArray(6)
    private val dots = Paint().apply { isAntiAlias = false; strokeCap = Paint.Cap.SQUARE }
    private val ring = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE }
    private val add = PorterDuffXfermode(PorterDuff.Mode.ADD)

    val idle get() = atoms.isEmpty() && photons.isEmpty()

    private fun nextOrbital(a: Atom?): Int {
        val taken = atoms.map { it.orbital }
        var i: Int
        do {
            i = Random.nextInt(ORBITALS.size)
        } while (i == a?.orbital || (i in taken && atoms.size < ORBITALS.size))
        return i
    }

    /** Where atoms belong: one behind the search field on home, none on results. */
    fun layout(size: IntSize, density: Float, home: Boolean, nucleus: Offset?, still: Boolean, now: Double) {
        if (size.width == 0 || size.height == 0) return
        val wdp = size.width / density
        val hdp = size.height / density
        val want = (9000 * sqrt(wdp * hdp / (1440f * 900f))).roundToInt().coerceIn(3600, 14000)
        if (count == 0 || abs(want - count) / count.toFloat() > 0.3f) {
            count = want
            atoms.clear()
            bins.indices.forEach { bins[it] = FloatArray(count * 2) }
        }
        val places = if (home && nucleus != null) listOf(Triple(nucleus.x, nucleus.y, min(size.width, size.height) * 0.36f)) else emptyList()
        places.forEachIndexed { i, (x, y, s) ->
            val a = atoms.getOrNull(i)
            if (a != null && a.tfade > 0) {
                a.tcx = x; a.tcy = y; a.tsize = s
            } else {
                val orbital = if (still) STILL else a?.orbital ?: 2
                val fresh = Atom(count, orbital, x, y, s, now)
                if (a != null) atoms[i] = fresh else atoms.add(fresh)
            }
        }
        atoms.drop(places.size).forEach { it.tfade = 0f }
        if (still) {
            atoms.removeAll { it.tfade <= 0f }
            atoms.forEach { a ->
                a.cx = a.tcx; a.cy = a.tcy; a.size = a.tsize; a.fade = 1f; a.yaw = 0.7f; a.tilt = 0.4f
            }
        }
    }

    fun excite(now: Double) {
        for (a in atoms) {
            if (a.tfade <= 0f) continue
            photons.add(Photon(a.cx, a.cy, a.size, now))
            a.jump(nextOrbital(a), now, 0.9)
        }
    }

    fun step(now: Double, dt: Float, home: Boolean) {
        val k = 1 - exp(-dt * 3.2f)
        val calm = if (home) 1f else 0.55f
        for (a in atoms) {
            a.cx += (a.tcx - a.cx) * k
            a.cy += (a.tcy - a.cy) * k
            a.size += (a.tsize - a.size) * k
            a.fade += (a.tfade - a.fade) * (1 - exp(-dt * 2.4f))
            a.yaw += dt * 0.16f * calm
            a.tilt += dt * 0.05f * calm
            if (a.morph < 1f) {
                a.morph = min(1f, ((now - a.morphStart) / a.morphDur).toFloat())
                val e = ease(a.morph)
                val twist = sin(PI.toFloat() * e) * 0.9f
                val c = cos(twist)
                val s = sin(twist)
                var j = 0
                while (j < a.count * 4) {
                    val x = a.src[j] + (a.dst[j] - a.src[j]) * e
                    val y = a.src[j + 1] + (a.dst[j + 1] - a.src[j + 1]) * e
                    a.pos[j] = x * c - y * s
                    a.pos[j + 1] = x * s + y * c
                    a.pos[j + 2] = a.src[j + 2] + (a.dst[j + 2] - a.src[j + 2]) * e
                    a.pos[j + 3] = if (e < 0.5f) a.src[j + 3] else a.dst[j + 3]
                    j += 4
                }
            } else {
                // The cloud is never still: a few electrons are "measured" somewhere new each frame.
                repeat((a.count * 0.02f * calm).toInt() + 1) {
                    val i = Random.nextInt(a.count) * 4
                    val p = a.poolAt * 4
                    a.pos[i] = a.pool[p]
                    a.pos[i + 1] = a.pool[p + 1]
                    a.pos[i + 2] = a.pool[p + 2]
                    a.pos[i + 3] = a.pool[p + 3]
                    a.poolAt = (a.poolAt + 1) % a.count
                }
                if (now >= a.next) a.jump(nextOrbital(a), now, 2.2)
            }
        }
        atoms.removeAll { it.tfade <= 0f && it.fade < 0.01f }
        photons.removeAll { now - it.born >= 1.4 }
    }

    fun draw(scope: DrawScope, p: Palette, density: Float, now: Double) = scope.drawIntoCanvas { canvas ->
        val c = canvas.nativeCanvas
        dots.xfermode = if (p.dark) add else null
        val radii = floatArrayOf(1.1f, 0.85f, 0.6f)
        val t = (now * 0.9).toFloat()
        for (a in atoms) {
            if (a.fade < 0.01f) continue
            val pitch = 0.42f + sin(a.tilt) * 0.22f
            val cosYaw = cos(a.yaw); val sinYaw = sin(a.yaw)
            val cosP = cos(pitch); val sinP = sin(pitch)
            binCount.fill(0)
            for (i in 0 until a.count) {
                // Each dot blinks out briefly at its own time, so the cloud shimmers.
                val b = a.blink[i]
                if ((b + t * (0.6f + b * 0.5f)) % 1f < 0.1f) continue
                val j = i * 4
                val x = a.pos[j] * cosYaw - a.pos[j + 1] * sinYaw
                val y = a.pos[j] * sinYaw + a.pos[j + 1] * cosYaw
                val z = a.pos[j + 2] * cosP - y * sinP
                val depth = a.pos[j + 2] * sinP + y * cosP
                val f = PERSP / (PERSP + depth)
                val bin = (if (depth < -0.3f) 0 else if (depth < 0.3f) 1 else 2) + (if (a.pos[j + 3] > 0.5f) 3 else 0)
                val n = binCount[bin]
                bins[bin][n * 2] = a.cx + x * a.size * f
                bins[bin][n * 2 + 1] = a.cy - z * a.size * f
                binCount[bin] = n + 1
            }
            for (bin in 0 until 6) {
                if (binCount[bin] == 0) continue
                val near = bin % 3
                val ink = if (bin < 3) p.inkPlus else p.inkMinus
                dots.color = ink.copy(alpha = p.dotAlpha[near] * a.fade).toArgb()
                dots.strokeWidth = radii[near] * 2 * density
                c.drawPoints(bins[bin], 0, binCount[bin] * 2, dots)
            }
        }
        // Photons: the light given off when the atom drops to its new orbital.
        ring.strokeWidth = density
        for (ph in photons) {
            val u = ((now - ph.born) / 1.4).toFloat().coerceIn(0f, 1f)
            ring.color = p.inkMinus.copy(alpha = 0.55f * (1 - u) * (1 - u)).toArgb()
            c.drawCircle(ph.cx, ph.cy, ph.r * (0.35f + u * 1.6f), ring)
        }
        dots.xfermode = null
    }
}

private fun seconds(): Double = System.nanoTime() / 1e9

@Composable
fun ElectronCloud(home: Boolean, nucleus: Offset?, excitations: Int, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val still = LocalReducedMotion.current
    val density = LocalDensity.current.density
    val model = remember { CloudModel() }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember { mutableLongStateOf(0L) }
    val onHome by rememberUpdatedState(home)

    LaunchedEffect(size, home, nucleus, still) {
        model.layout(size, density, home, nucleus, still, seconds())
        frame++
    }
    LaunchedEffect(excitations) {
        if (excitations > 0 && !still) model.excite(seconds())
    }
    if (!still) {
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                if (model.idle) {
                    delay(250)
                    continue
                }
                withFrameNanos { t ->
                    if (t - last >= 33_000_000L) {
                        val dt = if (last == 0L) 0f else min(0.1f, (t - last) / 1e9f)
                        model.step(t / 1e9, dt, onHome)
                        last = t
                        frame = t
                    }
                }
            }
        }
    }
    Canvas(modifier.onSizeChanged { size = it }) {
        frame // redraw whenever the model advances
        model.draw(this, palette, density, seconds())
    }
}
