// Avogadro: a search page over SearXNG's JSON API on the same origin.
// Everything an engine returns is untrusted, so the DOM is built with textContent
// and only http(s) links and this server's proxied images are ever used.
(() => {
  "use strict";

  const $ = (sel, el = document) => el.querySelector(sel);
  const root = document.documentElement;
  const SVG = "http://www.w3.org/2000/svg";
  const reduceMotion = matchMedia("(prefers-reduced-motion: reduce)");

  const form = $(".query");
  const input = $("#q");
  const clearBtn = $(".query-clear");
  const suggestBox = $("#suggest");
  const tabs = [...document.querySelectorAll(".tabs button")];
  const timeSel = $("#time");
  const lead = $("#lead");
  const side = $("#side");
  const list = $("#list");
  const foot = $("#foot");
  const status = $("#status");
  const viewer = $("#viewer");

  const CATS = ["general", "images", "news", "videos", "it", "science"];
  const NAMES = {
    "google cse": "Google", "google cse images": "Google Images", "google scholar": "Google Scholar",
    bing: "Bing", "bing images": "Bing Images", "bing news": "Bing News", "bing videos": "Bing Videos",
    "duckduckgo web": "DuckDuckGo", "duckduckgo images": "DuckDuckGo Images",
    "duckduckgo news": "DuckDuckGo News", "duckduckgo videos": "DuckDuckGo Videos",
    yahoo: "Yahoo", yep: "Yep", wikipedia: "Wikipedia", wikidata: "Wikidata", wikinews: "Wikinews",
    arxiv: "arXiv", pubmed: "PubMed", "semantic scholar": "Semantic Scholar", europepmc: "Europe PMC",
    reuters: "Reuters", youtube: "YouTube", dailymotion: "Dailymotion", github: "GitHub",
    stackoverflow: "Stack Overflow", superuser: "Super User", askubuntu: "Ask Ubuntu",
    "docker hub": "Docker Hub", mdn: "MDN", pypi: "PyPI", "arch linux wiki": "Arch Wiki",
  };

  const state = { q: "", cat: "general", time: "", page: 1 };
  let items = [];        // rendered result elements, in order, for the keyboard
  let images = [];       // image results, for the viewer
  let seen = new Set();
  let sel = -1;
  let searchCtrl = null;
  let lastData = null;

  // ---------- small helpers ----------

  function h(tag, props, ...kids) {
    const el = document.createElement(tag);
    if (props) {
      for (const [k, v] of Object.entries(props)) {
        if (v == null || v === false) continue;
        if (k === "class") el.className = v;
        else if (k === "text") el.textContent = v;
        else if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
        else if (k === "vars") for (const [p, val] of Object.entries(v)) el.style.setProperty(p, val);
        else el.setAttribute(k, v === true ? "" : v);
      }
    }
    for (const kid of kids.flat(Infinity)) {
      if (kid == null || kid === false || kid === "") continue;
      el.append(kid instanceof Node ? kid : String(kid));
    }
    return el;
  }

  function icon(id) {
    const s = document.createElementNS(SVG, "svg");
    s.setAttribute("aria-hidden", "true");
    const u = document.createElementNS(SVG, "use");
    u.setAttribute("href", "#" + id);
    s.append(u);
    return s;
  }

  function safeUrl(u) {
    if (!u || typeof u !== "string") return null;
    try {
      const url = new URL(u);
      return url.protocol === "https:" || url.protocol === "http:" ? url.href : null;
    } catch {
      return null;
    }
  }

  // Only this server's image proxy (and inline data images) may be loaded.
  function safeImg(u) {
    if (typeof u !== "string") return null;
    if (u.startsWith("/image?") || /^data:image\/(png|jpe?g|gif|webp);base64,/.test(u)) return u;
    return null;
  }

  function originalOf(proxied) {
    try { return safeUrl(new URL(proxied, location.origin).searchParams.get("url")); } catch { return null; }
  }

  // SearXNG marks query terms with U+E000 ... U+E001.
  function marked(text) {
    const frag = document.createDocumentFragment();
    if (!text) return frag;
    text = untag(text);
    const parts = text.split(/[\uE000\uE001]/);
    const opens = [...text.matchAll(/[\uE000\uE001]/g)].map((m) => m[0] === "\uE000");
    let on = false;
    parts.forEach((part, i) => {
      if (part) frag.append(on ? h("mark", { text: part }) : document.createTextNode(part));
      if (i < opens.length) on = opens[i];
    });
    return frag;
  }

  // Some engines leak markup (MathML, <b>) into titles and snippets; show it as text without the tags.
  function untag(text) {
    return String(text || "")
      .replace(/<\/?[a-zA-Z][^<>]{0,400}>/g, "")
      .replace(/<(\/[a-zA-Z]*|[a-zA-Z])[^<>]*$/, "")
      .replace(/\s{2,}/g, " ")
      .trim();
  }

  function plain(text) {
    return untag(text).replace(/[\uE000\uE001]/g, "");
  }

  function hostOf(u) {
    try { return new URL(u).hostname.replace(/^www\./, ""); } catch { return ""; }
  }

  function crumbs(u) {
    try {
      const url = new URL(u);
      const segs = url.pathname.split("/").filter(Boolean).map((s) => {
        try { return decodeURIComponent(s); } catch { return s; }
      });
      return segs.length ? " › " + segs.join(" › ") : "";
    } catch {
      return "";
    }
  }

  const rtf = new Intl.RelativeTimeFormat("en", { numeric: "auto" });
  function when(s) {
    if (!s) return "";
    const d = new Date(s);
    if (isNaN(d)) return "";
    const sec = (d - Date.now()) / 1000;
    const abs = Math.abs(sec);
    if (abs < 3600) return rtf.format(Math.round(sec / 60), "minute");
    if (abs < 86400) return rtf.format(Math.round(sec / 3600), "hour");
    if (abs < 86400 * 30) return rtf.format(Math.round(sec / 86400), "day");
    return d.toLocaleDateString("en", { year: "numeric", month: "short", day: "numeric" });
  }

  function engineName(e) {
    return NAMES[e] || e.replace(/(^|\s)\S/g, (c) => c.toUpperCase());
  }

  function favicon(url) {
    const host = (() => { try { return new URL(url).host.toLowerCase(); } catch { return ""; } })();
    const letter = () => h("span", { class: "fav-none", "aria-hidden": "true", text: host.replace(/^www\./, "").charAt(0) });
    if (!host) return letter();
    const img = h("img", { class: "fav", src: "/favicon/" + host, alt: "", width: 18, height: 18, loading: "lazy", decoding: "async" });
    img.addEventListener("error", () => img.replaceWith(letter()), { once: true });
    return img;
  }

  // ---------- URL state ----------

  function readURL() {
    const p = new URLSearchParams(location.search);
    state.q = (p.get("q") || "").trim();
    state.cat = CATS.includes(p.get("c")) ? p.get("c") : "general";
    state.time = ["day", "week", "month", "year"].includes(p.get("t")) ? p.get("t") : "";
    state.page = 1;
  }

  function writeURL(push) {
    const p = new URLSearchParams();
    if (state.q) p.set("q", state.q);
    if (state.cat !== "general") p.set("c", state.cat);
    if (state.time) p.set("t", state.time);
    const url = p.toString() ? "/?" + p : "/";
    if (url === location.pathname + location.search) return;
    history[push ? "pushState" : "replaceState"](null, "", url);
  }

  function syncControls() {
    input.value = state.q;
    clearBtn.hidden = !state.q;
    tabs.forEach((b) => b.setAttribute("aria-current", String(b.dataset.cat === state.cat)));
    timeSel.value = state.time;
    root.classList.toggle("cat-images", state.cat === "images");
  }

  function setMode(mode) {
    root.classList.toggle("is-home", mode === "home");
    root.classList.toggle("is-results", mode === "results");
    if (window.Cloud) window.Cloud.mode(mode);
    requestAnimationFrame(() => layoutCloud());
  }

  function setLoading(on) {
    root.classList.toggle("is-loading", on);
    list.setAttribute("aria-busy", String(on));
  }

  function goHome(push) {
    searchCtrl?.abort();
    setLoading(false);
    state.q = "";
    state.page = 1;
    if (push !== null) writeURL(push);
    document.title = "Avogadro";
    syncControls();
    clear();
    transition(() => setMode("home"));
    input.focus({ preventScroll: true });
  }

  function transition(fn) {
    if (document.startViewTransition && !reduceMotion.matches && document.visibilityState === "visible") {
      document.startViewTransition(fn);
    } else {
      fn();
    }
  }

  // ---------- search ----------

  async function search({ push = true, append = false } = {}) {
    state.q = state.q.trim();
    if (!state.q) return goHome(push);
    if (!append) state.page = 1;
    if (push !== null && !append) writeURL(push);
    document.title = state.q + " – Avogadro";
    syncControls();
    closeSuggest();

    // The query excites the atom the moment it is sent.
    if (!append && push !== null && window.Cloud) window.Cloud.burst(form.getBoundingClientRect());
    if (root.classList.contains("is-home")) transition(() => setMode("results"));
    else setMode("results");

    searchCtrl?.abort();
    const ctrl = (searchCtrl = new AbortController());
    setLoading(true);

    const params = new URLSearchParams({ q: state.q, format: "json", categories: state.cat, pageno: String(state.page) });
    if (state.time) params.set("time_range", state.time);

    // A query that names a ticker starts its quote alongside the search.
    const intent = !append && state.cat === "general" ? stockIntent(state.q) : null;
    const guess = stockGuess(intent);
    const early = guess ? loadStock(guess, "1D", ctrl.signal) : null;

    let data;
    try {
      const res = await fetch("/search?" + params, { signal: ctrl.signal, headers: { Accept: "application/json" } });
      if (!res.ok) throw new Error("HTTP " + res.status);
      data = await res.json();
    } catch (err) {
      if (err.name === "AbortError") return;
      setLoading(false);
      if (append) moreFailed();
      else renderError();
      return;
    }
    if (ctrl !== searchCtrl) return;

    // Results confirm or find the ticker. The card gets a moment to arrive so the results
    // don't jump down under it; if it is slower, it is put in place when it comes.
    let stock = null;
    const symbol = stockSymbol(intent, data.results || []);
    if (symbol) {
      const pending = symbol === guess ? early : loadStock(symbol, "1D", ctrl.signal);
      stock = await Promise.race([pending, new Promise((done) => setTimeout(done, 1200, undefined))]);
      if (ctrl !== searchCtrl) return;
      if (stock === undefined) {
        pending.then((late) => { if (late && ctrl === searchCtrl) lead.prepend(stockCard(late)); });
        stock = null;
      }
    }
    setLoading(false);
    render(data, append, stock);
  }

  function clear() {
    stockSizer.disconnect();
    stockView = null;
    lead.replaceChildren();
    side.replaceChildren();
    list.replaceChildren();
    foot.replaceChildren();
    items = [];
    images = [];
    seen = new Set();
    sel = -1;
  }

  function render(data, append, stock) {
    if (!append) {
      clear();
      lastData = data;
      window.scrollTo({ top: 0 });
    }
    const fresh = (data.results || []).filter((r) => {
      const key = safeUrl(r.url) || r.img_src;
      if (!key || seen.has(key)) return false;
      seen.add(key);
      return true;
    });

    if (!append) {
      if (stock) lead.append(stockCard(stock));
      renderLead(data);
      if (state.cat === "general" && data.infoboxes && data.infoboxes.length) renderInfobox(data.infoboxes[0]);
    }

    const added = state.cat === "images" ? renderTiles(fresh) : renderRows(fresh);
    added.forEach((el, i) => {
      if (reduceMotion.matches) return;
      el.classList.add("condense");
      el.style.setProperty("--i", String(Math.min(i, 14)));
      el.addEventListener("animationend", () => el.classList.remove("condense"), { once: true });
    });

    const nothing = !append && !fresh.length && !$(".answer, .stock", lead) && !side.childElementCount;
    if (nothing) renderEmpty(data);
    renderFoot(data, fresh.length, append);

    status.textContent = nothing ? "No results" : items.length === 1 ? "1 result" : `${items.length} results`;
    layoutCloud();
  }

  function renderLead(data) {
    for (const a of data.answers || []) {
      if (!a || typeof a.answer !== "string" || !a.answer.trim()) continue;
      const src = safeUrl(a.url);
      lead.append(h("div", { class: "answer" },
        h("p", { text: a.answer }),
        src && h("a", { href: src, rel: "noreferrer noopener", text: hostOf(src) })));
    }
    const corr = (data.corrections || []).find((c) => typeof c === "string" && c.trim() && c !== state.q);
    if (corr) {
      lead.append(h("p", { class: "didyou" }, "Did you mean ",
        h("a", { href: "/?q=" + encodeURIComponent(corr), onclick: (e) => { e.preventDefault(); state.q = corr; search(); }, text: corr }),
        "?"));
    }
  }

  function renderInfobox(ib) {
    const img = safeImg(ib.img_src);
    const links = (ib.urls || [])
      .map((u) => ({ title: u.title, url: safeUrl(u.url) }))
      .filter((u) => u.url && u.title && !/^P\d+$/.test(u.title))
      .slice(0, 5);
    const attrs = (ib.attributes || [])
      .filter((a) => a && typeof a.label === "string" && typeof a.value === "string" && a.value.trim())
      .slice(0, 8);
    // Wikipedia leads often lose their pronunciation markup and keep a stray "(, also, ".
    let text = plain(ib.content || "").replace(/\(\s*,\s*(also,?\s*)?/g, "(").replace(/\(\s*\)/g, "");
    if (text.length > 460) text = text.slice(0, 460).replace(/\s+\S*$/, "") + "…";

    side.append(h("section", { class: "info", "aria-label": ib.infobox || "About" },
      img && h("img", { class: "info-img", src: img, alt: "", loading: "lazy", decoding: "async" }),
      ib.infobox && h("h2", { text: ib.infobox }),
      text && h("p", { text }),
      attrs.length ? h("dl", null, attrs.map((a) => h("div", null, h("dt", { text: a.label }), h("dd", { text: a.value })))) : null,
      links.length ? h("div", { class: "info-links" },
        links.map((l) => h("a", { class: "chip", href: l.url, rel: "noreferrer noopener", text: l.title }))) : null));
  }

  // ---------- stocks ----------
  // A ticker query ("NVDA", "$nvda", "nvda stock", "nvidia stock") gets a quote card above
  // the results, from this server's /stock. A bare word counts as a ticker only when quote
  // pages for it are among the top results, so "on" or "usa" stay plain searches.

  const RANGES = ["1D", "5D", "1M", "6M", "YTD", "1Y", "5Y", "MAX"];
  const SPANS = {
    "1D": "today", "5D": "past 5 days", "1M": "past month", "6M": "past 6 months",
    YTD: "year to date", "1Y": "past year", "5Y": "past 5 years", MAX: "all time",
  };
  const TICKER = /^[A-Z]{1,5}(?:\.[A-Z])?$/;
  const STOCK_WORDS = new Set(["stock", "stocks", "share", "shares", "price", "prices", "quote", "ticker", "today", "now", "live", "chart", "current"]);
  // Hosts whose quote pages name the ticker in the path.
  const QUOTE_PAGES = [
    [/(^|\.)finance\.yahoo\.com$/, /^\/quote\/([^/?#]+)/],
    [/^(www\.)?google\.com$/, /^\/finance\/quote\/([^/:?#]+):/],
    [/^(www\.)?cnbc\.com$/, /^\/quotes\/([^/?#]+)/],
    [/^(www\.)?marketwatch\.com$/, /^\/investing\/(?:stock|fund)\/([^/?#]+)/],
    [/^(www\.)?nasdaq\.com$/, /^\/market-activity\/(?:stocks|etf|funds-and-etfs)\/([^/?#]+)/],
    [/^(www\.)?robinhood\.com$/, /^\/(?:us\/en\/)?stocks\/([^/?#]+)/],
    [/^(www\.)?stockanalysis\.com$/, /^\/(?:stocks|etf)\/([^/?#]+)/],
    [/^(www\.)?morningstar\.com$/, /^\/(?:stocks|etfs)\/[a-z]+\/([^/?#]+)/],
    [/^(www\.)?tradingview\.com$/, /^\/symbols\/[A-Za-z]+-([^/?#]+)/],
    [/^(www\.)?zacks\.com$/, /^\/stock\/quote\/([^/?#]+)/],
    [/^(www\.)?seekingalpha\.com$/, /^\/symbol\/([^/?#]+)/],
    [/^(www\.)?fool\.com$/, /^\/quote\/[a-z]+\/([^/?#]+)/],
    [/^(www\.)?bloomberg\.com$/, /^\/quote\/([^/:?#]+):US/],
    [/^(www\.)?wsj\.com$/, /^\/market-data\/quotes\/([^/?#]+)/],
    [/^(www\.)?stocktwits\.com$/, /^\/symbol\/([^/?#]+)/],
    [/^(www\.)?macrotrends\.net$/, /^\/stocks\/charts\/([^/?#]+)/],
    [/^markets\.businessinsider\.com$/, /^\/stocks\/([a-z.]+)-stock/],
    [/^(www\.)?public\.com$/, /^\/stocks\/([^/?#]+)/],
  ];

  function tickerOf(raw) {
    let s = String(raw);
    try { s = decodeURIComponent(s); } catch { /* keep it as it came */ }
    s = s.toUpperCase().replace(/-([A-Z])$/, ".$1"); // BRK-B is BRK.B
    return TICKER.test(s) ? s : null;
  }

  function quotePage(url) {
    let u;
    try { u = new URL(url); } catch { return null; }
    for (const [host, path] of QUOTE_PAGES) {
      if (!host.test(u.hostname)) continue;
      const m = path.exec(u.pathname);
      return m ? tickerOf(m[1]) : null;
    }
    return null;
  }

  // What a query says about stocks: a ticker for sure ($NVDA), a word that may be one
  // (NVDA, nvda, on), or a stock asked for by name or ticker (nvidia stock, nvda stock).
  function stockIntent(q) {
    const m = /^\$([A-Za-z]{1,5}(?:[.-][A-Za-z])?)$/.exec(q);
    if (m) return { symbol: tickerOf(m[1]), sure: true };
    const words = q.toLowerCase().split(/\s+/).filter(Boolean);
    if (words.length === 1) {
      const symbol = /^[A-Za-z]{1,5}(?:[.-][A-Za-z])?$/.test(q) ? tickerOf(q) : null;
      return symbol ? { symbol, bare: true, caps: q === q.toUpperCase() } : null;
    }
    const asked = words.some((w) => w === "stock" || w === "stocks" || w === "shares" || w === "ticker") || /\bshare price\b/.test(q.toLowerCase());
    if (!asked || words.length > 6) return null;
    const rest = words.filter((w) => !STOCK_WORDS.has(w));
    return { symbol: rest.length === 1 && /^\$?[a-z]{1,5}(?:[.-][a-z])?$/.test(rest[0]) ? tickerOf(rest[0].replace(/^\$/, "")) : null, named: true };
  }

  // A guess worth fetching while the search runs.
  function stockGuess(intent) {
    return intent && intent.symbol && (intent.sure || intent.caps || intent.named) ? intent.symbol : null;
  }

  function stockSymbol(intent, results) {
    if (!intent) return null;
    if (intent.sure) return intent.symbol;
    const votes = new Map();
    for (const r of results.slice(0, 10)) {
      const s = quotePage(r.url);
      if (s) votes.set(s, (votes.get(s) || 0) + 1);
    }
    if (intent.bare) return (votes.get(intent.symbol) || 0) >= 2 ? intent.symbol : null;
    const [best, n] = [...votes].sort((a, b) => b[1] - a[1])[0] || [];
    return n >= 2 ? best : intent.symbol;
  }

  async function loadStock(symbol, range, signal) {
    try {
      const res = await fetch(`/stock?symbol=${encodeURIComponent(symbol)}&range=${range}`, { signal });
      if (!res.ok) return null;
      const s = await res.json();
      return s && typeof s.price === "number" && Array.isArray(s.points) ? s : null;
    } catch {
      return null;
    }
  }

  const money = (v) => (v == null ? "–" : v.toLocaleString("en", { minimumFractionDigits: 2, maximumFractionDigits: Math.abs(v) < 1 ? 4 : 2 }));
  const signed = (v) => (v < 0 ? "−" : "+") + money(Math.abs(v));
  const percent = (v) => v.toLocaleString("en", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const move = (change, pct) => `${signed(change)} (${percent(Math.abs(pct))}%)`;
  const trend = (v) => (v > 0 ? "is-up" : v < 0 ? "is-down" : null);
  // Exchange time: a US session reads 9:30 to 4 wherever the reader is.
  const et = (opts) => new Intl.DateTimeFormat("en-US", { timeZone: "America/New_York", ...opts });
  const etTime = et({ hour: "numeric", minute: "2-digit" });
  const etDayTime = et({ month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
  const etDay = et({ month: "short", day: "numeric", year: "numeric" });
  const etDate = et({ month: "short", day: "numeric" });
  const etMonth = et({ month: "short", year: "numeric" });
  const etYear = et({ year: "numeric" });
  const etHour = et({ hour: "numeric" });

  let stockView = null; // the card on screen, redrawn when its width changes
  const stockSizer = new ResizeObserver(() => stockView?.draw());

  function stockCard(s) {
    const view = { range: "1D", data: { "1D": s }, idx: -1, draw };
    const val = h("span", { class: "stock-val" });
    const moveEl = h("p", { class: "stock-move" });
    const chart = h("div", { class: "stock-chart" });
    const buttons = RANGES.map((r) => h("button", { type: "button", "aria-pressed": String(r === "1D"), text: r, onclick: () => setRange(r) }));
    const ext = s.extended;
    const meta = [
      s.state === "open" ? `Open · ${etTime.format(s.as_of * 1000)} ET` : s.as_of ? `At close · ${etDayTime.format(s.as_of * 1000)} ET` : "",
    ];
    const card = h("section", { class: "stock", "aria-label": `${s.name} stock` },
      h("p", { class: "stock-name", text: s.name }),
      h("p", { class: "stock-sym", text: [s.exchange, s.symbol].filter(Boolean).join(": ") }),
      h("p", { class: "stock-now" }, val, s.currency && h("small", { text: s.currency })),
      moveEl,
      h("p", { class: "stock-meta" }, meta.filter(Boolean),
        ext && typeof ext.price === "number" ? [
          meta[0] ? " · " : "",
          ext.state === "pre" ? "Pre-market " : "After hours ",
          money(ext.price), " ",
          typeof ext.change === "number" ? h("span", { class: trend(ext.change), text: move(ext.change, ext.change_pct || 0) }) : null,
        ] : null),
      h("div", { class: "stock-ranges", role: "group", "aria-label": "Chart range" }, buttons),
      chart,
      stockStats(s));

    // i < 0 shows the latest price; otherwise point i of the chart.
    function show(i) {
      const d = view.data[view.range];
      const pts = d.points;
      const ref = view.range === "1D" ? d.prev_close : pts.length ? pts[0][1] : null;
      let price = d.price, change, pct, label;
      if (i >= 0 && pts[i]) {
        price = pts[i][1];
        const t = pts[i][0] * 1000;
        label = view.range === "1D" ? etTime.format(t) + " ET" : view.range === "5D" ? etDayTime.format(t) + " ET" : etDay.format(t);
      } else {
        label = SPANS[view.range];
      }
      if (view.range === "1D" && i < 0) {
        change = d.change;
        pct = d.change_pct;
      } else if (ref) {
        change = price - ref;
        pct = (change / ref) * 100;
      }
      val.textContent = money(price);
      moveEl.className = "stock-move " + (trend(change) || "");
      moveEl.replaceChildren(typeof change === "number" ? move(change, pct || 0) + " " : "", h("span", { class: "stock-when", text: label }));
    }

    function draw() {
      const d = view.data[view.range];
      const pts = d.points;
      const W = Math.round(chart.clientWidth);
      if (!W) return;
      chart.replaceChildren();
      if (pts.length < 2) return;
      const H = narrow.matches ? 176 : 216;
      const GUT = 46, AXIS = 22, TOP = 8;
      const pw = W - GUT, ph = H - AXIS - TOP;
      const ref = view.range === "1D" && typeof d.prev_close === "number" ? d.prev_close : null;
      const vals = pts.map((p) => p[1]).concat(ref == null ? [] : [ref]);
      let lo = Math.min(...vals), hi = Math.max(...vals);
      const pad = (hi - lo) * 0.08 || Math.abs(hi) * 0.01 || 1;
      lo -= pad;
      hi += pad;
      const sess = view.range === "1D" && Array.isArray(d.session) && d.session[1] > d.session[0] ? d.session : null;
      const xOf = sess
        ? (i) => Math.max(0, Math.min(pw, ((pts[i][0] - sess[0]) / (sess[1] - sess[0])) * pw))
        : (i) => (i / (pts.length - 1)) * pw;
      const yOf = (v) => TOP + (1 - (v - lo) / (hi - lo)) * ph;
      const last = pts[pts.length - 1][1];
      const dir = trend(last - (ref ?? pts[0][1])) || "is-up";

      const svg = svgEl("svg", { viewBox: `0 0 ${W} ${H}`, class: dir, role: "img", tabindex: "0",
        "aria-label": `${s.symbol}, ${SPANS[view.range]}: ${money(pts[0][1])} to ${money(last)}` });
      const defs = svgEl("defs");
      const fill = svgEl("linearGradient", { id: "stock-fill", x1: "0", y1: "0", x2: "0", y2: "1" });
      fill.append(svgEl("stop", { offset: "0", "stop-color": "currentColor", "stop-opacity": "0.2" }),
        svgEl("stop", { offset: "1", "stop-color": "currentColor", "stop-opacity": "0" }));
      defs.append(fill);
      svg.append(defs);

      const ticks = niceTicks(lo, hi, 3);
      const step = ticks.length > 1 ? ticks[1] - ticks[0] : hi - lo;
      for (const v of ticks) {
        const y = yOf(v);
        svg.append(svgEl("line", { class: "grid", x1: 0, x2: pw, y1: y, y2: y }));
        svg.append(svgText(pw + 8, y + 4, priceTick(v, step), "start"));
      }
      for (const [x, text, anchor] of timeTicks(view.range, pts, sess, pw, xOf)) svg.append(svgText(x, H - 5, text, anchor));
      if (ref != null) svg.append(svgEl("line", { class: "prev", x1: 0, x2: pw, y1: yOf(ref), y2: yOf(ref) }));

      const line = pts.map((p, i) => `${i ? "L" : "M"}${xOf(i).toFixed(1)},${yOf(p[1]).toFixed(1)}`).join("");
      svg.append(svgEl("path", { class: "area", d: `${line}L${xOf(pts.length - 1).toFixed(1)},${TOP + ph}L${xOf(0).toFixed(1)},${TOP + ph}Z` }));
      svg.append(svgEl("path", { class: "line", d: line }));
      const hair = svgEl("line", { class: "hair", y1: TOP, y2: TOP + ph, visibility: "hidden" });
      const dot = svgEl("circle", { class: "dot", r: 4, visibility: "hidden" });
      svg.append(hair, dot);
      chart.append(svg);

      const scrub = (i) => {
        view.idx = i;
        const on = i >= 0;
        hair.setAttribute("visibility", on ? "visible" : "hidden");
        dot.setAttribute("visibility", on ? "visible" : "hidden");
        if (on) {
          const x = xOf(i);
          hair.setAttribute("x1", x);
          hair.setAttribute("x2", x);
          dot.setAttribute("cx", x);
          dot.setAttribute("cy", yOf(pts[i][1]));
        }
        show(i);
      };
      const pick = (clientX) => {
        const box = svg.getBoundingClientRect();
        const x = Math.max(0, Math.min(pw, ((clientX - box.left) * W) / box.width));
        if (!sess) return Math.round((x / pw) * (pts.length - 1));
        const t = sess[0] + (x / pw) * (sess[1] - sess[0]);
        let best = 0;
        for (let i = 1; i < pts.length; i++) if (Math.abs(pts[i][0] - t) < Math.abs(pts[best][0] - t)) best = i;
        return best;
      };
      svg.addEventListener("pointerdown", (e) => scrub(pick(e.clientX)));
      svg.addEventListener("pointermove", (e) => scrub(pick(e.clientX)));
      svg.addEventListener("pointerleave", () => scrub(-1));
      svg.addEventListener("pointercancel", () => scrub(-1));
      svg.addEventListener("blur", () => scrub(-1));
      svg.addEventListener("keydown", (e) => {
        const step = { ArrowLeft: -1, ArrowRight: 1 }[e.key];
        if (step) scrub(Math.max(0, Math.min(pts.length - 1, (view.idx < 0 ? pts.length - 1 : view.idx) + step)));
        else if (e.key === "Home") scrub(0);
        else if (e.key === "End") scrub(pts.length - 1);
        else if (e.key === "Escape") scrub(-1);
        else return;
        e.preventDefault();
      });
      if (view.idx >= pts.length) view.idx = -1;
    }

    async function setRange(r) {
      if (r === view.range) return;
      const was = view.range;
      view.range = r;
      view.idx = -1;
      buttons.forEach((b) => b.setAttribute("aria-pressed", String(b.textContent === r)));
      if (!view.data[r]) {
        chart.classList.add("is-loading");
        const d = await loadStock(s.symbol, r);
        chart.classList.remove("is-loading");
        if (d) view.data[r] = d;
        if (view.range !== r) return; // another range was picked meanwhile
        if (!d) {
          view.range = was;
          buttons.forEach((b) => b.setAttribute("aria-pressed", String(b.textContent === was)));
          return;
        }
      }
      draw();
      show(-1);
    }

    show(-1);
    stockSizer.disconnect();
    stockSizer.observe(chart);
    stockView = view;
    return card;
  }

  function stockStats(s) {
    const st = s.stats || {};
    const rows = [
      ["Open", money(st.open)], ["High", money(st.high)], ["Low", money(st.low)],
      ["Mkt cap", st.mkt_cap || "–"], ["P/E ratio", money(st.pe)], ["Div yield", st.div_yield == null ? "–" : percent(st.div_yield) + "%"],
      ["Prev close", money(s.prev_close)], ["52-wk high", money(st.wk52_high)], ["52-wk low", money(st.wk52_low)],
    ];
    return h("dl", { class: "stock-stats" }, rows.map(([k, v]) => h("div", null, h("dt", { text: k }), h("dd", { text: v }))));
  }

  function svgEl(tag, attrs) {
    const el = document.createElementNS(SVG, tag);
    for (const [k, v] of Object.entries(attrs || {})) el.setAttribute(k, String(v));
    return el;
  }

  function svgText(x, y, text, anchor) {
    const t = svgEl("text", { x: x.toFixed(1), y: y.toFixed(1), "text-anchor": anchor });
    t.textContent = text;
    return t;
  }

  function niceTicks(lo, hi, n) {
    const raw = (hi - lo) / n, mag = 10 ** Math.floor(Math.log10(raw)), f = raw / mag;
    const step = (f < 1.5 ? 1 : f < 3 ? 2 : f < 7 ? 5 : 10) * mag;
    const out = [];
    for (let v = Math.ceil(lo / step) * step; v <= hi; v += step) out.push(+v.toFixed(10));
    return out;
  }

  function priceTick(v, step) {
    const digits = step >= 1 ? 0 : step >= 0.1 ? 1 : 2;
    return v.toLocaleString("en", { minimumFractionDigits: digits, maximumFractionDigits: digits });
  }

  // [x, label, anchor] along the bottom: session hours on 1D, day starts on 5D, else four even dates.
  function timeTicks(range, pts, sess, pw, xOf) {
    const out = [];
    if (sess) {
      for (const hr of [10, 12, 14, 16]) {
        const t = sess[0] + (hr - 9.5) * 3600;
        if (t > sess[1]) continue;
        const x = ((t - sess[0]) / (sess[1] - sess[0])) * pw;
        out.push([x, etHour.format(t * 1000), x > pw - 20 ? "end" : "middle"]);
      }
      return out;
    }
    if (range === "5D") {
      let day = "";
      pts.forEach((p, i) => {
        const d = etDate.format(p[0] * 1000);
        if (d !== day) out.push([xOf(i), d, i ? "middle" : "start"]);
        day = d;
      });
      return out;
    }
    const fmt = range === "1M" ? etDate : range === "5Y" || range === "MAX" ? etYear : etMonth;
    for (let k = 0; k < 4; k++) {
      const i = Math.round((k * (pts.length - 1)) / 4);
      out.push([xOf(i), fmt.format(pts[i][0] * 1000), k ? "middle" : "start"]);
    }
    return out;
  }

  function siteLine(r, extra) {
    const url = safeUrl(r.url);
    return h("div", { class: "r-site" },
      favicon(url),
      h("span", { class: "r-host", text: hostOf(url) }),
      h("span", { class: "r-path", text: crumbs(url) }),
      extra && h("span", { class: "r-date", text: extra }));
  }

  function renderRows(rows) {
    const added = [];
    for (const r of rows) {
      const url = safeUrl(r.url);
      if (!url) continue;
      const el = r.template === "videos.html" || state.cat === "videos" ? videoRow(r, url) : webRow(r, url);
      list.append(el);
      items.push(el);
      added.push(el);
    }
    return added;
  }

  function title(r, url) {
    return h("a", { class: "r-title", href: url, rel: "noreferrer noopener" }, plain(r.title) || hostOf(url));
  }

  function webRow(r, url) {
    const thumb = safeImg(r.thumbnail);
    const date = state.cat === "news" || r.template === "paper.html" ? when(r.publishedDate) : "";
    const body = h("div", { class: "r-body" },
      siteLine(r, date),
      title(r, url),
      r.content && h("p", { class: "r-snip" }, marked(r.content)),
      extras(r));
    return h("article", { class: "r" },
      body,
      thumb && h("img", { class: "r-thumb", src: thumb, alt: "", loading: "lazy", decoding: "async" }));
  }

  function extras(r) {
    const bits = [];
    if (r.template === "paper.html") {
      const authors = Array.isArray(r.authors) ? r.authors.filter(Boolean) : [];
      if (authors.length) bits.push(h("span", { text: authors.slice(0, 3).join(", ") + (authors.length > 3 ? " et al." : "") }));
      if (r.journal) bits.push(h("span", { text: r.journal }));
      const pdf = safeUrl(r.pdf_url);
      if (pdf) bits.push(h("a", { href: pdf, rel: "noreferrer noopener", text: "PDF" }));
      if (r.doi && /^10\.\S+$/.test(r.doi)) bits.push(h("a", { href: "https://doi.org/" + r.doi, rel: "noreferrer noopener", text: "DOI " + r.doi }));
    } else if (r.template === "packages.html") {
      if (r.maintainer) bits.push(h("span", { text: r.maintainer }));
      if (r.popularity) bits.push(h("span", { text: r.popularity }));
      if (r.license_name) bits.push(h("span", { text: r.license_name }));
      const src = safeUrl(r.source_code_url);
      if (src && src !== safeUrl(r.url)) bits.push(h("a", { href: src, rel: "noreferrer noopener", text: "Source" }));
      const home = safeUrl(r.homepage);
      if (home && home !== safeUrl(r.url)) bits.push(h("a", { href: home, rel: "noreferrer noopener", text: "Homepage" }));
    }
    return bits.length ? h("div", { class: "r-extra" }, bits) : null;
  }

  function videoRow(r, url) {
    const thumb = safeImg(r.thumbnail);
    const meta = [r.author, when(r.publishedDate)].filter(Boolean).join(", ");
    return h("article", { class: "r r-video" },
      h("div", { class: "r-frame" },
        thumb && h("img", { src: thumb, alt: "", loading: "lazy", decoding: "async" }),
        r.length ? h("span", { class: "r-len", text: String(r.length) }) : null),
      h("div", { class: "r-body" },
        siteLine(r, meta),
        title(r, url),
        r.content && h("p", { class: "r-snip" }, marked(r.content))));
  }

  function renderTiles(rows) {
    let grid = $(".tiles", list);
    if (!grid) {
      grid = h("div", { class: "tiles" });
      list.append(grid);
    }
    const added = [];
    for (const r of rows) {
      const thumb = safeImg(r.thumbnail_src) || safeImg(r.img_src);
      if (!thumb) continue;
      const m = /(\d+)\s*[×x]\s*(\d+)/.exec(r.resolution || "");
      const ar = m && +m[2] ? Math.min(2.4, Math.max(0.5, +m[1] / +m[2])) : 1.3;
      const index = images.length;
      images.push(r);
      const tile = h("button", { type: "button", class: "tile", vars: { "--ar": ar.toFixed(3) }, "aria-label": plain(r.title) || "Image", onclick: () => openViewer(index) },
        h("img", { src: thumb, alt: "", loading: "lazy", decoding: "async" }),
        h("figcaption", { text: hostOf(safeUrl(r.url)) }));
      grid.append(tile);
      items.push(tile);
      added.push(tile);
    }
    return added;
  }

  function renderEmpty(data) {
    const sugg = (data.suggestions || []).filter((s) => typeof s === "string").slice(0, 6);
    list.append(h("div", { class: "empty" },
      h("h2", { text: `No results for “${state.q}”` }),
      sugg.length ? chips(sugg) : null));
  }

  function renderError() {
    clear();
    list.append(h("div", { class: "empty" },
      h("h2", { text: "The search didn't go through" }),
      h("p", { text: "Avogadro couldn't reach the search server. Try again in a moment." }),
      h("button", { type: "button", class: "btn", onclick: () => search({ push: null }), text: "Try again" })));
    status.textContent = "Search failed";
  }

  function chips(words) {
    return h("div", { class: "chips" }, words.map((s) =>
      h("a", { class: "chip", href: "/?q=" + encodeURIComponent(s), onclick: (e) => { e.preventDefault(); state.q = s; search(); } },
        icon("i-search"), s)));
  }

  function renderFoot(data, freshCount, append) {
    $(".more", foot)?.remove();
    if (freshCount >= 6 && state.page < 10) {
      const btn = h("button", { type: "button", class: "btn btn-quiet more", text: "More results" });
      btn.addEventListener("click", () => {
        btn.disabled = true;
        btn.textContent = "Loading…";
        state.page += 1;
        search({ push: null, append: true });
      });
      foot.prepend(btn);
    }
    if (append) return;
    const sugg = (data.suggestions || []).filter((s) => typeof s === "string" && s !== state.q).slice(0, 8);
    if (sugg.length && items.length) {
      foot.append(h("section", { class: "related", "aria-label": "Related searches" }, chips(sugg)));
    }
    const failed = (data.unresponsive_engines || []).map((e) => engineName(Array.isArray(e) ? e[0] : String(e)));
    if (failed.length) {
      const names = new Intl.ListFormat("en", { type: "conjunction" }).format([...new Set(failed)]);
      foot.append(h("p", { class: "note", text: `${names} didn't answer.` }));
    }
  }

  function moreFailed() {
    const btn = $(".more", foot);
    state.page -= 1;
    if (btn) {
      btn.disabled = false;
      btn.textContent = "Couldn't load more. Try again";
    }
  }

  // ---------- autocomplete ----------

  let sgCtrl = null, sgTimer = 0, sgItems = [], sgIndex = -1, typed = "";

  function closeSuggest() {
    suggestBox.hidden = true;
    suggestBox.replaceChildren();
    input.setAttribute("aria-expanded", "false");
    input.removeAttribute("aria-activedescendant");
    sgItems = [];
    sgIndex = -1;
    sgCtrl?.abort();
    clearTimeout(sgTimer);
  }

  function showSuggest(words) {
    sgItems = words;
    sgIndex = -1;
    if (!words.length || document.activeElement !== input) return closeSuggest();
    const t = typed.toLowerCase();
    suggestBox.replaceChildren(...words.map((w, i) => {
      const rest = w.toLowerCase().startsWith(t) ? [w.slice(0, t.length), h("b", { text: w.slice(t.length) })] : [w];
      return h("li", {
        id: "sg-" + i, role: "option", "aria-selected": "false",
        onpointerdown: (e) => e.preventDefault(),
        onclick: () => { state.q = w; search(); },
      }, icon("i-search"), h("span", null, rest));
    }));
    suggestBox.hidden = false;
    input.setAttribute("aria-expanded", "true");
  }

  function moveSuggest(step) {
    if (!sgItems.length) return;
    sgIndex = sgIndex + step;
    if (sgIndex < -1) sgIndex = sgItems.length - 1;
    if (sgIndex >= sgItems.length) sgIndex = -1;
    [...suggestBox.children].forEach((li, i) => li.setAttribute("aria-selected", String(i === sgIndex)));
    if (sgIndex >= 0) {
      input.value = sgItems[sgIndex];
      input.setAttribute("aria-activedescendant", "sg-" + sgIndex);
    } else {
      input.value = typed;
      input.removeAttribute("aria-activedescendant");
    }
  }

  function fetchSuggest() {
    const q = typed.trim();
    clearTimeout(sgTimer);
    if (q.length < 2) return closeSuggest();
    sgTimer = setTimeout(async () => {
      sgCtrl?.abort();
      const ctrl = (sgCtrl = new AbortController());
      try {
        const res = await fetch("/autocompleter?q=" + encodeURIComponent(q), { signal: ctrl.signal });
        if (!res.ok) return;
        const data = await res.json();
        const words = (Array.isArray(data) ? data[1] : []).filter((w) => typeof w === "string" && w.trim()).slice(0, 7);
        if (ctrl === sgCtrl && typed.trim() === q) showSuggest(words);
      } catch {
        // Suggestions are optional; a failure just means none show.
      }
    }, 120);
  }

  input.addEventListener("input", () => {
    typed = input.value;
    clearBtn.hidden = !input.value;
    fetchSuggest();
  });
  input.addEventListener("keydown", (e) => {
    if (e.key === "ArrowDown") { e.preventDefault(); moveSuggest(1); }
    else if (e.key === "ArrowUp") { e.preventDefault(); moveSuggest(-1); }
    else if (e.key === "Escape") {
      if (!suggestBox.hidden) { input.value = typed; closeSuggest(); }
      else input.blur();
    }
  });
  input.addEventListener("blur", () => setTimeout(closeSuggest, 0));
  input.addEventListener("focus", () => { typed = input.value; });

  form.addEventListener("submit", (e) => {
    e.preventDefault();
    state.q = input.value;
    input.blur();
    search();
  });

  clearBtn.addEventListener("click", () => {
    input.value = "";
    typed = "";
    clearBtn.hidden = true;
    closeSuggest();
    input.focus();
  });

  tabs.forEach((b) => b.addEventListener("click", () => {
    if (b.dataset.cat === state.cat) return;
    state.cat = b.dataset.cat;
    search();
  }));
  timeSel.addEventListener("change", () => { state.time = timeSel.value; search(); });

  $(".mark").addEventListener("click", (e) => {
    if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
    e.preventDefault();
    goHome(true);
  });

  window.addEventListener("popstate", () => {
    closeViewer(false);
    readURL();
    syncControls();
    if (state.q) search({ push: null });
    else goHome(null);
  });

  // ---------- keyboard ----------

  function select(i) {
    if (!items.length) return;
    sel = Math.max(0, Math.min(items.length - 1, i));
    items.forEach((el, k) => el.classList.toggle("is-sel", k === sel));
    items[sel].scrollIntoView({ block: "nearest", behavior: reduceMotion.matches ? "auto" : "smooth" });
  }

  document.addEventListener("keydown", (e) => {
    if (!viewer.hidden) return viewerKeys(e);
    const t = e.target;
    const typing = t instanceof HTMLInputElement || t instanceof HTMLSelectElement || t instanceof HTMLTextAreaElement;
    if (typing || e.metaKey || e.ctrlKey || e.altKey) return;
    if (e.key === "/") {
      e.preventDefault();
      input.focus();
      input.select();
    } else if (root.classList.contains("is-results") && items.length) {
      if (e.key === "j") { e.preventDefault(); select(sel + 1); }
      else if (e.key === "k") { e.preventDefault(); select(sel - 1); }
      else if ((e.key === "Enter" || e.key === "o") && sel >= 0 && (t === document.body || items[sel].contains(t))) {
        e.preventDefault();
        const el = items[sel];
        if (el.classList.contains("tile")) el.click();
        else $(".r-title", el)?.click();
      } else if (e.key === "Escape" && sel >= 0) {
        items[sel].classList.remove("is-sel");
        sel = -1;
      }
    }
  });

  // ---------- image viewer ----------

  let vIndex = -1, vReturn = null;
  const vImg = h("img", { class: "viewer-img", alt: "" });
  $(".viewer-stage", viewer).append(vImg);

  function openViewer(i) {
    const r = images[i];
    if (!r) return;
    if (viewer.hidden) vReturn = document.activeElement;
    vIndex = i;
    const thumb = safeImg(r.thumbnail_src) || safeImg(r.img_src);
    const full = safeImg(r.img_src) || thumb;
    vImg.src = thumb;
    if (full !== thumb) {
      const pre = new Image();
      pre.onload = () => { if (vIndex === i) vImg.src = full; };
      pre.src = full;
    }
    $(".viewer-title", viewer).textContent = plain(r.title) || hostOf(safeUrl(r.url));
    $(".viewer-meta", viewer).textContent = [hostOf(safeUrl(r.url)), r.resolution, r.img_format].filter(Boolean).join("   ");
    const page = $(".viewer-page", viewer), raw = $(".viewer-full", viewer);
    const pageUrl = safeUrl(r.url), rawUrl = originalOf(r.img_src);
    page.hidden = !pageUrl;
    if (pageUrl) page.href = pageUrl;
    raw.hidden = !rawUrl;
    if (rawUrl) raw.href = rawUrl;
    $(".viewer-prev", viewer).disabled = i === 0;
    $(".viewer-next", viewer).disabled = i === images.length - 1;
    if (viewer.hidden) {
      viewer.hidden = false;
      root.style.overflow = "hidden";
      $(".viewer-close", viewer).focus();
    }
    sel = items.indexOf(list.querySelectorAll(".tile")[i]);
    items.forEach((el, k) => el.classList.toggle("is-sel", k === sel));
  }

  function closeViewer(restore = true) {
    if (viewer.hidden) return;
    viewer.hidden = true;
    root.style.overflow = "";
    vImg.removeAttribute("src");
    if (restore && vReturn) vReturn.focus({ preventScroll: true });
  }

  function viewerKeys(e) {
    if (e.key === "Escape") { e.preventDefault(); closeViewer(); }
    else if (e.key === "ArrowLeft" && vIndex > 0) { e.preventDefault(); openViewer(vIndex - 1); }
    else if (e.key === "ArrowRight" && vIndex < images.length - 1) { e.preventDefault(); openViewer(vIndex + 1); }
    else if (e.key === "Tab") {
      const f = [...viewer.querySelectorAll("a:not([hidden]), button:not([disabled])")];
      const i = f.indexOf(document.activeElement);
      if (e.shiftKey && i <= 0) { e.preventDefault(); f[f.length - 1].focus(); }
      else if (!e.shiftKey && i === f.length - 1) { e.preventDefault(); f[0].focus(); }
    }
  }

  $(".viewer-close", viewer).addEventListener("click", () => closeViewer());
  $(".viewer-prev", viewer).addEventListener("click", () => openViewer(vIndex - 1));
  $(".viewer-next", viewer).addEventListener("click", () => openViewer(vIndex + 1));
  $(".viewer-stage", viewer).addEventListener("click", (e) => { if (e.target !== vImg) closeViewer(); });

  // The page fills the viewport under the header, so the felt behind the column never stops short.
  // On results the atoms sit below the header, in the margins beside the reading column.
  const bar = $(".bar");
  function layoutCloud() {
    if (!window.Cloud) return;
    if (root.classList.contains("is-results")) {
      const b = bar.getBoundingClientRect();
      const cols = [lead, list, side].map((el) => el.getBoundingClientRect()).filter((r) => r.width > 0);
      const left = Math.min(...cols.map((r) => r.left)), right = Math.max(...cols.map((r) => r.right));
      window.Cloud.light(b.bottom);
      window.Cloud.avoid(cols.length ? [left - 56, right + 56] : null);
    } else {
      window.Cloud.light(0);
      window.Cloud.avoid(null);
    }
  }
  new ResizeObserver(([entry]) => {
    root.style.setProperty("--bar-h", Math.round(entry.borderBoxSize[0].blockSize) + "px");
    layoutCloud();
  }).observe(bar);
  window.addEventListener("resize", layoutCloud);

  // Phones: all six tabs keep the header row; the time filter sits above the results instead.
  const timeFilter = $(".when");
  const narrow = matchMedia("(max-width: 860px)");
  function placeWhen() {
    (narrow.matches ? $("#filters") : $(".tabs")).append(timeFilter);
  }
  narrow.addEventListener("change", placeWhen);
  placeWhen();

  // ---------- start ----------

  readURL();
  syncControls();
  if (state.q) {
    setMode("results");
    search({ push: null });
  } else {
    setMode("home");
    if (matchMedia("(pointer: fine)").matches) input.focus({ preventScroll: true });
  }
})();
