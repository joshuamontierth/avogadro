# SPDX-License-Identifier: AGPL-3.0-or-later
"""Stock quotes and price series for the Avogadro page's stock card, served at ``/stock``.

``GET /stock?symbol=NVDA&range=1D`` (ranges 1D 5D 1M 6M YTD 1Y 5Y MAX; 1D by default)
answers with one JSON shape, shared by the page and other clients, so keep it stable::

    {"symbol", "name", "exchange", "currency",
     "price", "change", "change_pct", "prev_close",
     "state": "open" | "pre" | "post" | "closed", "as_of",
     "extended": {"state", "price", "change", "change_pct", "as_of"} | null,
     "range", "session": [open, close] | null,
     "points": [[unix seconds, close], ...],
     "stats": {"open", "high", "low", "volume", "mkt_cap", "pe", "div_yield", "wk52_high", "wk52_low"}}

Times are unix seconds and percentages plain numbers (0.46 means 0.46%). Stats a
security doesn't have are null. Points ascend, number at most ``MAX_POINTS``, and for
1D and 5D cover only the regular session; pre- and after-hours prices are in
``extended``. Before the open (``state`` "pre") the day hasn't started, so ``change``
and ``change_pct`` are 0 and ``prev_close`` is the last close, which ``price`` still
shows. ``session`` is the 1D chart's time domain (null for other ranges).
Errors: 400 for a bad symbol or range, 404 for an unknown symbol, 502 when the
source fails, each as ``{"error": ...}``.

The data comes from CNBC's quote service and chart API, which need no key. Quotes are
cached for a minute and series for a minute (1D) to an hour (long ranges).
"""

import calendar
import datetime as dt
import json
import logging
import re
import threading
import time
import typing as t
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import quote as urlquote

import flask

log = logging.getLogger("searx.plugins.avogadro")

QUOTE_URL = (
    "https://quote.cnbc.com/quote-html-webservice/restQuote/symbolType/symbol"
    "?symbols={symbol}&requestMethod=itv&noform=1&partnerId=2&fund=1&exthrs=1&output=json"
)
SERIES_URL = "https://ts-api.cnbc.com/harmony/app/charts/{span}.json?symbol={symbol}"
AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0 Safari/537.36"
TIMEOUT = 6

SYMBOL = re.compile(r"^[A-Z0-9][A-Z0-9.\-]{0,11}$")
# Range: (CNBC chart span, seconds to cache it, months of it to keep; None keeps all).
RANGES = {
    "1D": ("1D", 60, None),
    "5D": ("5D", 300, None),
    "1M": ("1M", 3600, 1),
    "6M": ("6M", 3600, 6),
    "YTD": ("YTD", 3600, "ytd"),
    "1Y": ("1Y", 3600, 12),
    "5Y": ("5Y", 3600, 60),
    "MAX": ("ALL", 3600, None),
}
QUOTE_TTL = 60
MAX_POINTS = 400
# US regular session, as CNBC's ET wall-clock bar times (HHMM).
OPEN_HHMM, CLOSE_HHMM = 930, 1600
US_ZONES = ("EST", "EDT")
STATES = {"REG_MKT": "open", "PRE_MKT": "pre", "POST_MKT": "post"}

pool = ThreadPoolExecutor(max_workers=4, thread_name_prefix="avogadro-stock")


class NoQuote(Exception):
    """CNBC knows no such symbol."""


class Cache:
    """A small expiring map, shared by this worker's threads."""

    def __init__(self, size: int = 512) -> None:
        self.size = size
        self.lock = threading.Lock()
        self.items: dict[str, tuple[float, t.Any]] = {}

    def get(self, key: str, ttl: int, load: t.Callable[[], t.Any]) -> t.Any:
        now = time.monotonic()
        with self.lock:
            hit = self.items.get(key)
            if hit and hit[0] > now:
                return hit[1]
        value = load()
        with self.lock:
            if len(self.items) >= self.size:
                for k in sorted(self.items, key=lambda k: self.items[k][0])[: self.size // 4]:
                    del self.items[k]
            self.items[key] = (now + ttl, value)
        return value


cache = Cache()


def fetch(url: str) -> t.Any:
    req = urllib.request.Request(url, headers={"User-Agent": AGENT, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as resp:
        return json.load(resp)


def number(text: t.Any) -> float | None:
    """CNBC's numbers are strings like "+5.04", "83,200,291" or "0.46%"."""
    if isinstance(text, (int, float)):
        return float(text)
    if not isinstance(text, str):
        return None
    try:
        return float(text.replace(",", "").replace("%", "").strip())
    except ValueError:
        return None


def stamp(text: t.Any) -> int | None:
    """"2026-10-26T16:15:00.000-0400" -> unix seconds."""
    if not isinstance(text, str):
        return None
    try:
        return int(dt.datetime.strptime(text, "%Y-%m-%dT%H:%M:%S.%f%z").timestamp())
    except ValueError:
        return None


def load_quote(symbol: str) -> dict | None:
    rows = fetch(QUOTE_URL.format(symbol=urlquote(symbol)))["FormattedQuoteResult"]["FormattedQuote"]
    row = rows[0] if rows else None
    if not row or row.get("code") != 0 or number(row.get("last")) is None:
        return None  # cached like a quote, so a word that is no ticker costs one lookup
    return row


def load_series(symbol: str, span: str) -> list[dict]:
    return fetch(SERIES_URL.format(span=span, symbol=urlquote(symbol)))["barData"]["priceBars"] or []


def quote_of(symbol: str) -> dict:
    row = cache.get("q:" + symbol, QUOTE_TTL, lambda: load_quote(symbol))
    if row is None:
        raise NoQuote(symbol)
    return row


def series_of(symbol: str, span: str, ttl: int) -> list[dict]:
    return cache.get(f"s:{span}:{symbol}", ttl, lambda: load_series(symbol, span))


# ---------- shaping ----------


def bars(raw: list[dict]) -> list[tuple[int, str, float]]:
    """(unix seconds, ET wall time "YYYYMMDDHHMMSS", close) for each usable bar, ascending."""
    out = []
    for b in raw:
        close, ms, wall = number(b.get("close")), b.get("tradeTimeinMills"), b.get("tradeTime")
        if close is None or not isinstance(ms, (int, float)) or not isinstance(wall, str) or len(wall) < 12:
            continue
        out.append((int(ms // 1000), wall, close))
    out.sort()
    return out


def regular(rows: list[tuple[int, str, float]]) -> list[tuple[int, str, float]]:
    return [r for r in rows if OPEN_HHMM <= int(r[1][8:12]) < CLOSE_HHMM]


def day_session(row: tuple[int, str, float]) -> list[int]:
    """The regular session around a bar, from its unix time and ET wall time."""
    wall = row[1]
    midnight = row[0] - (int(wall[8:10]) * 3600 + int(wall[10:12]) * 60 + int(wall[12:14] or 0))
    return [midnight + 9 * 3600 + 30 * 60, midnight + 16 * 3600]


def months_back(day: dt.date, months: int) -> dt.date:
    y, m = divmod(day.year * 12 + day.month - 1 - months, 12)
    return dt.date(y, m + 1, min(day.day, calendar.monthrange(y, m + 1)[1]))


def thin(rows: list[tuple[int, str, float]]) -> list[tuple[int, str, float]]:
    if len(rows) <= MAX_POINTS:
        return rows
    step = (len(rows) - 1) / (MAX_POINTS - 1)
    return [rows[round(i * step)] for i in range(MAX_POINTS)]


def shape_series(symbol: str, rng: str, quote: dict) -> tuple[list, list | None]:
    span, ttl, keep = RANGES[rng]
    state, price, as_of = quote["state"], quote["price"], quote["as_of"]
    rows = bars(series_of(symbol, span, ttl))
    session = None
    if rng in ("1D", "5D"):
        if quote["us"]:
            rows = regular(rows)
            if rng == "1D" and not rows:
                # Before the open, today has only pre-market bars: show the last full session.
                rows = regular(bars(series_of(symbol, "5D", RANGES["5D"][1])))
            days = sorted({r[1][:8] for r in rows})
            rows = [r for r in rows if r[1][:8] in days[-(1 if rng == "1D" else 5):]]
            if rng == "1D" and rows:
                session = day_session(rows[0])
                # A finished day ends on its closing price, as the quote shows it.
                if state in ("pre", "post", "closed") and rows[-1][0] < session[1] and price is not None:
                    rows.append((session[1], rows[-1][1][:8] + "160000", price))
        elif rng == "1D" and rows:
            session = [rows[0][0], rows[-1][0]]
    else:
        if keep and rows:
            last = dt.date(int(rows[-1][1][:4]), int(rows[-1][1][4:6]), int(rows[-1][1][6:8]))
            start = dt.date(last.year, 1, 1) if keep == "ytd" else months_back(last, keep)
            rows = [r for r in rows if r[1][:8] >= start.strftime("%Y%m%d")]
        # Daily, weekly and monthly bars can lag the quote by days: end on the quote.
        day = quote["day"]
        if rows and day and rows[-1][1][:8] < day and price is not None and as_of:
            rows.append((as_of, day + "000000", price))
    return [[r[0], round(r[2], 4)] for r in thin(rows)], session


def shape(symbol: str, rng: str) -> dict:
    q = pool.submit(quote_of, symbol)
    # Fetch the series alongside; it is needed whenever the symbol exists.
    span, ttl, _ = RANGES[rng]
    s = pool.submit(series_of, symbol, span, ttl)
    row = q.result()
    state = STATES.get(row.get("curmktstatus") or "", "closed")
    price = number(row.get("last"))
    ext = row.get("ExtendedMktQuote") or {}
    extended = None
    if state != "open" and number(ext.get("last")) is not None:
        extended = {
            "state": "pre" if ext.get("type") == "PRE_MKT" else "post",
            "price": number(ext.get("last")),
            "change": number(ext.get("change")),
            "change_pct": number(ext.get("change_pct")),
            "as_of": stamp(ext.get("last_time")),
        }
    try:
        s.result()
        points, session = shape_series(symbol, rng, {
            "us": row.get("timeZone") in US_ZONES, "state": state, "price": price,
            "as_of": stamp(row.get("last_time")), "day": (row.get("last_time") or "")[:10].replace("-", ""),
        })
    except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError) as e:
        log.warning("no %s series for %s: %s", rng, symbol, e)
        points, session = [], None
    change, change_pct = number(row.get("change")), number(row.get("change_pct"))
    prev_close = number(row.get("previous_day_closing"))
    if state == "pre" and price is not None:
        # Before the open the day hasn't moved: price is still the last close.
        change, change_pct, prev_close = 0.0, 0.0, price
    volume = number(row.get("volume"))
    return {
        "symbol": row.get("symbol") or symbol,
        "name": row.get("name") or row.get("shortName") or symbol,
        "exchange": row.get("exchange"),
        "currency": row.get("currencyCode"),
        "price": price,
        "change": change,
        "change_pct": change_pct,
        "prev_close": prev_close,
        "state": state,
        "as_of": stamp(row.get("last_time")),
        "extended": extended,
        "range": rng,
        "session": session,
        "points": points,
        "stats": {
            "open": number(row.get("open")),
            "high": number(row.get("high")),
            "low": number(row.get("low")),
            "volume": int(volume) if volume is not None else None,
            "mkt_cap": row.get("mktcapView") or None,
            "pe": number(row.get("pe")),
            "div_yield": number(row.get("dividendyield")),
            "wk52_high": number(row.get("yrhiprice")),
            "wk52_low": number(row.get("yrloprice")),
        },
    }


def reply(body: dict, status: int = 200, max_age: int = 0) -> flask.Response:
    resp = flask.Response(json.dumps(body, separators=(",", ":")), status=status, mimetype="application/json")
    resp.headers["Cache-Control"] = f"max-age={max_age}" if max_age else "no-store"
    return resp


def view() -> flask.Response:
    symbol = (flask.request.args.get("symbol") or "").strip().upper()
    rng = (flask.request.args.get("range") or "1D").strip().upper()
    if not SYMBOL.match(symbol):
        return reply({"error": "symbol must be a ticker like NVDA or BRK.B"}, 400)
    if rng not in RANGES:
        return reply({"error": "range must be one of " + " ".join(RANGES)}, 400)
    try:
        return reply(shape(symbol, rng), max_age=RANGES[rng][1] // 2)
    except NoQuote:
        return reply({"error": f"no quote for {symbol}"}, 404)
    except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError) as e:
        log.warning("quote source failed for %s: %s", symbol, e)
        return reply({"error": "quote source unavailable"}, 502)
