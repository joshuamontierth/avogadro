// The background is a hydrogen atom's electron cloud. Every dot is one sample of
// where the electron could be found (|ψ|²), so dense regions glow and the nodes stay
// dark. The atom turns slowly and steps through real orbitals (1s, 2p, 3d, 3s, 4f...);
// the two colours are the two signs of the wavefunction. A search excites the atom:
// it jumps to another orbital and gives off a photon. The search page sets the mode
// and the reading column to keep clear (atoms sit in the margins on results).
(() => {
  "use strict";

  const canvas = document.getElementById("cloud");
  const ctx = canvas.getContext("2d");
  const reduce = matchMedia("(prefers-reduced-motion: reduce)");
  const daylight = matchMedia("(prefers-color-scheme: light)");
  const field = document.querySelector(".query");

  const exp = Math.exp;
  // Radial part R(r) in Bohr radii (unnormalized) and the real angular part on the unit sphere.
  const ORBITALS = [
    { n: 1, R: (r) => exp(-r), Y: () => 1 }, // 1s
    { n: 2, R: (r) => r * exp(-r / 2), Y: (x, y, z) => z }, // 2p z
    { n: 3, R: (r) => r * r * exp(-r / 3), Y: (x, y, z) => 3 * z * z - 1 }, // 3d z²
    { n: 3, R: (r) => (27 - 18 * r + 2 * r * r) * exp(-r / 3), Y: () => 1 }, // 3s
    { n: 3, R: (r) => r * r * exp(-r / 3), Y: (x, y) => x * x - y * y }, // 3d x²−y²
    { n: 4, R: (r) => r * r * r * exp(-r / 4), Y: (x, y, z) => x * y * z }, // 4f xyz
    { n: 3, R: (r) => r * (6 - r) * exp(-r / 3), Y: (x) => x }, // 3p x
    { n: 4, R: (r) => r * r * r * exp(-r / 4), Y: (x, y, z) => z * (5 * z * z - 3) }, // 4f z³
  ];
  const STILL = [2, 4, 5]; // what reduced motion shows: 3d z², 3d x²−y², 4f xyz

  const FRAME_MS = 1000 / 30;
  const PERSP = 3.6;
  // Sampling |ψ|^(2·SHARP) instead of |ψ|² tightens the lobes so each shape reads at a glance.
  const SHARP = 1.7;

  let W = 0, H = 0, count = 4000;
  let mode = "home";
  let clear = null; // [left, right] of the reading column
  let lightY = 0;
  let atoms = [];
  let photons = [];
  let running = false, lastFrame = 0, lastTick = 0;
  let inks, alphas, blend;

  const rand = (a, b) => a + Math.random() * (b - a);
  const ease = (k) => (k < 0.5 ? 4 * k * k * k : 1 - Math.pow(-2 * k + 2, 3) / 2);

  function theme() {
    if (daylight.matches) {
      inks = ["20,28,40", "10,97,192"];
      alphas = [0.5, 0.36, 0.22];
      blend = "source-over";
    } else {
      inks = ["226,234,246", "140,202,255"];
      alphas = [0.8, 0.55, 0.32];
      blend = "lighter";
    }
  }

  // ---------- sampling |ψ|² ----------

  function prepare(orb) {
    if (orb.cdf) return orb;
    const steps = 900, rmax = 3 * orb.n * (orb.n + 2);
    const rs = new Float64Array(steps + 1), cdf = new Float64Array(steps + 1);
    let acc = 0;
    for (let i = 0; i <= steps; i++) {
      const r = (i / steps) * rmax;
      acc += r * r * Math.pow(Math.abs(orb.R(r)), 2 * SHARP);
      rs[i] = r;
      cdf[i] = acc;
    }
    let k = 0;
    for (let i = 0; i <= steps; i++) cdf[i] /= acc;
    while (cdf[k] < 0.9) k++;
    let ymax = 0;
    for (let i = 0; i < 6000; i++) {
      const z = 2 * Math.random() - 1, t = 2 * Math.PI * Math.random(), s = Math.sqrt(1 - z * z);
      ymax = Math.max(ymax, Math.pow(Math.abs(orb.Y(s * Math.cos(t), s * Math.sin(t), z)), 2 * SHARP));
    }
    orb.rs = rs;
    orb.cdf = cdf;
    orb.scale = 1 / rs[k]; // 90% of the cloud inside the unit radius
    orb.ymax = ymax * 1.05;
    return orb;
  }

  function radius(orb) {
    const u = Math.random(), c = orb.cdf;
    let lo = 0, hi = c.length - 1;
    while (lo < hi) {
      const mid = (lo + hi) >> 1;
      if (c[mid] < u) lo = mid + 1;
      else hi = mid;
    }
    if (lo === 0) return 0;
    const f = (u - c[lo - 1]) / (c[lo] - c[lo - 1] || 1);
    return orb.rs[lo - 1] + f * (orb.rs[lo] - orb.rs[lo - 1]);
  }

  // Writes n samples (x, y, z, sign) into out from index i0.
  function sample(index, out, i0, n) {
    const orb = prepare(ORBITALS[index]);
    for (let i = i0; i < i0 + n; i++) {
      let x, y, z, v;
      do {
        z = 2 * Math.random() - 1;
        const t = 2 * Math.PI * Math.random(), s = Math.sqrt(1 - z * z);
        x = s * Math.cos(t);
        y = s * Math.sin(t);
        v = orb.Y(x, y, z);
      } while (Math.random() * orb.ymax > Math.pow(Math.abs(v), 2 * SHARP));
      const r = radius(orb), k = r * orb.scale, o = i * 4;
      out[o] = x * k;
      out[o + 1] = y * k;
      out[o + 2] = z * k;
      out[o + 3] = orb.R(r) * v < 0 ? 1 : 0;
    }
  }

  // ---------- atoms ----------

  function makeAtom(orbital, place, now) {
    const a = {
      orbital,
      pos: new Float32Array(count * 4),
      src: new Float32Array(count * 4),
      dst: new Float32Array(count * 4),
      pool: new Float32Array(count * 4),
      poolAt: 0,
      blink: new Float32Array(count),
      proj: new Float32Array(count * 3),
      morph: 1, morphStart: 0, morphDur: 1,
      yaw: rand(0, Math.PI * 2),
      tilt: rand(0, Math.PI * 2),
      next: now + rand(6, 9),
      cx: place.cx, cy: place.cy, size: place.size,
      tcx: place.cx, tcy: place.cy, tsize: place.size,
      fade: 0, tfade: 1,
    };
    sample(orbital, a.pos, 0, count);
    sample(orbital, a.pool, 0, count);
    for (let i = 0; i < count; i++) a.blink[i] = Math.random();
    return a;
  }

  function jump(a, orbital, now, dur) {
    a.src.set(a.pos);
    sample(orbital, a.dst, 0, count);
    sample(orbital, a.pool, 0, count);
    a.orbital = orbital;
    a.morph = 0;
    a.morphStart = now;
    a.morphDur = dur;
    a.next = now + dur + rand(7, 10);
  }

  function nextOrbital(a) {
    const others = atoms.map((b) => b.orbital);
    let i;
    do i = (Math.random() * ORBITALS.length) | 0;
    while (i === a.orbital || (others.includes(i) && atoms.length < ORBITALS.length));
    return i;
  }

  // Where the atoms belong: the nucleus behind the field on home, the margins on results.
  function places() {
    if (mode === "home") {
      const r = field.getBoundingClientRect();
      return [{ cx: r.left + r.width / 2, cy: r.top + r.height / 2, size: Math.min(W, H) * 0.36 }];
    }
    if (!clear) return null; // the page has not measured its column yet
    const top = lightY, h = H - top, out = [];
    const right = W - clear[1], left = clear[0];
    const sides = [right, left].filter((m) => m >= 180);
    if (!sides.length) return out;
    // Both atoms share one height and one size, so the page stays balanced.
    const cy = top + h * 0.45, size = Math.min(Math.min(...sides) * 0.38, h * 0.3);
    if (right >= 180) out.push({ cx: clear[1] + right / 2, cy, size });
    if (left >= 180) out.push({ cx: left / 2, cy, size });
    return out;
  }

  function layout(now = performance.now() / 1000) {
    const want = places();
    if (!want) return;
    want.forEach((p, i) => {
      const a = atoms[i];
      if (a && a.tfade > 0) Object.assign(a, { tcx: p.cx, tcy: p.cy, tsize: p.size });
      else atoms.splice(i, a ? 1 : 0, makeAtom(i === 0 ? atoms[0]?.orbital ?? 2 : nextOrbital({ orbital: -1 }), p, now));
    });
    atoms.slice(want.length).forEach((a) => (a.tfade = 0));
    if (reduce.matches) still();
    else start();
  }

  // ---------- drawing ----------

  function update(a, now, dt) {
    const k = 1 - Math.exp(-dt * 3.2);
    a.cx += (a.tcx - a.cx) * k;
    a.cy += (a.tcy - a.cy) * k;
    a.size += (a.tsize - a.size) * k;
    a.fade += (a.tfade - a.fade) * (1 - Math.exp(-dt * 2.4));
    const calm = mode === "results" ? 0.55 : 1;
    a.yaw += dt * 0.16 * calm;
    a.tilt += dt * 0.05 * calm;
    if (a.morph < 1) {
      a.morph = Math.min(1, (now - a.morphStart) / a.morphDur);
      const e = ease(a.morph), twist = Math.sin(Math.PI * e) * 0.9;
      const c = Math.cos(twist), s = Math.sin(twist);
      for (let i = 0; i < count * 4; i += 4) {
        const x = a.src[i] + (a.dst[i] - a.src[i]) * e;
        const y = a.src[i + 1] + (a.dst[i + 1] - a.src[i + 1]) * e;
        a.pos[i] = x * c - y * s;
        a.pos[i + 1] = x * s + y * c;
        a.pos[i + 2] = a.src[i + 2] + (a.dst[i + 2] - a.src[i + 2]) * e;
        a.pos[i + 3] = e < 0.5 ? a.src[i + 3] : a.dst[i + 3];
      }
    } else {
      // The cloud is never still: a few electrons are "measured" somewhere new each frame.
      const swaps = Math.ceil(count * 0.02 * calm);
      for (let j = 0; j < swaps; j++) {
        const i = ((Math.random() * count) | 0) * 4, p = a.poolAt * 4;
        a.pos[i] = a.pool[p];
        a.pos[i + 1] = a.pool[p + 1];
        a.pos[i + 2] = a.pool[p + 2];
        a.pos[i + 3] = a.pool[p + 3];
        a.poolAt = (a.poolAt + 1) % count;
      }
      if (now >= a.next) jump(a, nextOrbital(a), now, 2.2);
    }
  }

  function draw(now) {
    ctx.clearRect(0, 0, W, H);
    ctx.globalCompositeOperation = blend;
    for (const a of atoms) {
      if (a.fade < 0.01) continue;
      const pitch = 0.42 + Math.sin(a.tilt) * 0.22;
      const cosYaw = Math.cos(a.yaw), sinYaw = Math.sin(a.yaw), cosP = Math.cos(pitch), sinP = Math.sin(pitch);
      const P = a.proj, pos = a.pos;
      for (let i = 0, j = 0; i < count; i++, j += 4) {
        // Turn about the vertical (z) axis, then tip toward the viewer.
        const x = pos[j] * cosYaw - pos[j + 1] * sinYaw;
        const y = pos[j] * sinYaw + pos[j + 1] * cosYaw;
        const z = pos[j + 2] * cosP - y * sinP;
        const depth = pos[j + 2] * sinP + y * cosP;
        const f = PERSP / (PERSP + depth);
        P[i * 3] = a.cx + x * a.size * f;
        P[i * 3 + 1] = a.cy - z * a.size * f;
        P[i * 3 + 2] = (depth < -0.3 ? 0 : depth < 0.3 ? 1 : 2) + pos[j + 3] * 3;
      }
      const t = now * 0.9;
      for (let bin = 0; bin < 6; bin++) {
        const near = bin % 3, r = [1.1, 0.85, 0.6][near];
        ctx.beginPath();
        for (let i = 0; i < count; i++) {
          if (P[i * 3 + 2] !== bin) continue;
          // Each dot blinks out briefly at its own time, so the cloud shimmers.
          if ((a.blink[i] + t * (0.6 + a.blink[i] * 0.5)) % 1 < 0.1) continue;
          ctx.rect(P[i * 3] - r, P[i * 3 + 1] - r, r * 2, r * 2);
        }
        ctx.fillStyle = `rgba(${inks[bin < 3 ? 0 : 1]},${(alphas[near] * a.fade).toFixed(3)})`;
        ctx.fill();
      }
    }
    // Photons: the light given off when the atom drops to its new orbital.
    photons = photons.filter((p) => now - p.born < 1.4);
    for (const p of photons) {
      const u = (now - p.born) / 1.4;
      ctx.beginPath();
      ctx.arc(p.cx, p.cy, p.r * (0.35 + u * 1.6), 0, Math.PI * 2);
      ctx.lineWidth = 1;
      ctx.strokeStyle = `rgba(${inks[1]},${(0.55 * (1 - u) * (1 - u)).toFixed(3)})`;
      ctx.stroke();
    }
    ctx.globalCompositeOperation = "source-over";
  }

  function frame(ts) {
    if (!running) return;
    requestAnimationFrame(frame);
    if (ts - lastFrame < FRAME_MS) return;
    const now = ts / 1000;
    const dt = Math.min(0.1, lastTick ? now - lastTick : 0);
    lastFrame = ts;
    lastTick = now;
    for (const a of atoms) update(a, now, dt);
    atoms = atoms.filter((a) => a.tfade > 0 || a.fade > 0.01);
    draw(now);
    if (!atoms.length && !photons.length) stop(); // nothing to show (a phone's results page)
  }

  function start() {
    if (running || reduce.matches || document.hidden || !atoms.length) return;
    running = true;
    lastTick = 0;
    requestAnimationFrame(frame);
  }
  function stop() {
    running = false;
    if (!atoms.length) ctx.clearRect(0, 0, W, H);
  }

  // Reduced motion: one still picture of the orbitals in place, redrawn on layout changes.
  function still() {
    stop();
    atoms = atoms.filter((a) => a.tfade > 0);
    atoms.forEach((a, i) => {
      if (a.orbital !== STILL[i]) {
        a.orbital = STILL[i];
        sample(a.orbital, a.pos, 0, count);
      }
      Object.assign(a, { cx: a.tcx, cy: a.tcy, size: a.tsize, fade: 1, yaw: 0.7, tilt: 0.4 });
    });
    draw(0);
  }

  // Dots per atom: about 9,000 on a 1440 x 900 view, fewer on a phone, capped for big screens.
  function budget() {
    return Math.round(Math.min(14000, Math.max(3600, 9000 * Math.sqrt((window.innerWidth * window.innerHeight) / (1440 * 900)))));
  }

  function resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    W = window.innerWidth;
    H = window.innerHeight;
    canvas.width = Math.round(W * dpr);
    canvas.height = Math.round(H * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    const want = budget();
    if (Math.abs(want - count) / count > 0.3) {
      count = want;
      atoms = [];
    }
    layout();
  }

  // ---------- page API ----------

  function burst() {
    if (reduce.matches) return;
    const now = performance.now() / 1000;
    for (const a of atoms) {
      if (a.tfade <= 0) continue;
      photons.push({ cx: a.cx, cy: a.cy, r: a.size, born: now });
      jump(a, nextOrbital(a), now, 0.9);
    }
    start();
  }

  window.Cloud = {
    burst,
    mode(name) { mode = name; layout(); },
    light(y) { lightY = Math.max(0, y || 0); if (mode === "results") layout(); },
    avoid(range) { clear = range; if (mode === "results") layout(); },
  };

  theme();
  count = budget();
  mode = document.documentElement.classList.contains("is-results") ? "results" : "home";
  resize();
  window.addEventListener("resize", resize);
  document.addEventListener("visibilitychange", () => (document.hidden ? stop() : start()));
  reduce.addEventListener("change", () => layout());
  daylight.addEventListener("change", () => { theme(); if (reduce.matches) still(); });
})();
