# SPDX-License-Identifier: AGPL-3.0-or-later
"""The order of results for the Avogadro page: one vote per index, the site a search
names, and the pages you open.

SearXNG scores a result by how many engines returned it and how high, multiplying
the two, and then pulls results that share a template and a thumbnail together.
Bing, DuckDuckGo and Yahoo all serve Bing's index, so whatever Bing ranks arrives
as three engines agreeing and buries Google's first result; and the grouping lifts
weak results past strong ones because they have a picture. This module orders the
results itself:

- **Votes.** Each independent index adds ``weight / (K + rank)`` for the result's
  best rank in it, where the engines that serve one index count as that one index.
- **The named site.** When the host name spells the search ("byu learning suite",
  ``learningsuite.byu.edu``), that site's home page goes first and its other pages
  rise.
- **What you open.** Pages opened for a similar search (sharing at least half its
  words) rise, more for recent and repeated opens, and their sites rise a little.
  On the first page of web results, such a page comes back even when no engine
  returned it this time.

The page and the app send ``POST /click`` (``{"q", "url", "title"}``) when a
result is opened. Each host appends to its own file in ``CLICKS_DIR`` (``<hostname>.jsonl``)
and reads every file there, so syncing the folder between hosts (a two-way
Syncthing folder, say) shares what each one has learned without conflicts.
"""

import json
import logging
import os
import re
import socket
import threading
import time
import typing as t
from collections import defaultdict
from urllib.parse import urlsplit

import flask

import searx.engines
from searx.result_types import LegacyResult, MainResult, Result

log = logging.getLogger("searx.plugins.avogadro")

# Engines that serve another engine's index vote as that index.
INDEXES = {
    "duckduckgo web": "bing",
    "yahoo": "bing",
    "duckduckgo images": "bing",
    "duckduckgo videos": "bing",
    "duckduckgo news": "bing",
}
# Indexes that answer with one or two results, often beside the point for a question.
INDEX_WEIGHTS = {"wikipedia": 0.6, "wikidata": 0.6}
K = 3.0
NAV_HOME = 1.0  # the home page of the site the search names: first
NAV_PAGE = 0.25
NAV_COVER = 0.75  # share of the site's name the search must spell
CLICK_PAGE = 0.6  # per recent open of this page for a similar search
CLICK_SITE = 0.12
CLICK_CAP = 2.0
CLICK_OVERLAP = 0.5
CLICK_RETURN = 0.5  # weight of opens that brings a page back when no engine returned it
HALF_LIFE = 120 * 86400
RELOAD_EVERY = 5.0
MAX_Q, MAX_URL, MAX_BODY = 300, 2048, 8192

CLICKS_DIR = os.environ.get("AVOGADRO_CLICKS", "/var/lib/avogadro/clicks")
HOST_FILE = "/etc/avogadro-host"  # the host's /etc/hostname, mounted by docker-compose.yml

WORD = re.compile(r"\w+")
# Words that may sit beside a site's name in a search without being part of it.
FILLER = frozenset("the a an of official site website web page home homepage login log in sign signin portal www".split())
# Second-level labels under a country code (example.co.uk).
SECOND_LEVEL = frozenset(("co", "com", "ac", "gov", "org", "net", "edu"))
HOME_PATHS = frozenset(("", "index.html", "index.htm", "index.php", "home", "en", "en-us"))


def words(text: str) -> frozenset[str]:
    return frozenset(WORD.findall(text.lower()))


def terms(text: str) -> frozenset[str]:
    """A search's words with plurals folded, for telling similar searches apart."""
    return frozenset(w[:-1] if len(w) > 3 and w.endswith("s") and not w.endswith("ss") else w for w in words(text))


def site_of(url: str) -> str:
    host = (urlsplit(url).hostname or "").lower()
    return host[4:] if host.startswith("www.") else host


# ---------- the site a search names ----------


def site_name(host: str) -> str:
    """A host's name without its suffix or copy labels: learningsuite.byu.edu -> learningsuitebyu."""
    labels = [lb for lb in host.lower().split(".") if lb]
    while len(labels) > 2 and labels[0] in ("www", "m", "mobile"):
        labels.pop(0)
    if len(labels) >= 3 and len(labels[-1]) == 2 and labels[-2] in SECOND_LEVEL:
        labels = labels[:-2]
    elif len(labels) >= 2:
        labels = labels[:-1]
    return re.sub(r"[^\w]", "", "".join(labels))


def names_site(query: t.Iterable[str], host: str) -> bool:
    """Does the search spell this host's name: every word in it, covering most of it?"""
    name = site_name(host)
    terms = [w for w in query if len(w) >= 2]
    if len(name) < 2 or not terms or all(w in FILLER for w in terms):
        return False
    if any(w not in name for w in terms if w not in FILLER):
        return False
    covered = [False] * len(name)
    for w in terms:
        at = name.find(w)
        while at >= 0:
            covered[at:at + len(w)] = [True] * len(w)
            at = name.find(w, at + 1)
    return sum(covered) >= NAV_COVER * len(name)


def is_home(url: str) -> bool:
    p = urlsplit(url)
    return not p.query and p.path.strip("/").lower() in HOME_PATHS


# ---------- what you open ----------


class Click(t.NamedTuple):
    at: float
    words: frozenset[str]
    url: str
    title: str


class Learned(t.NamedTuple):
    weight: float
    url: str
    title: str


def host_name() -> str:
    try:
        with open(HOST_FILE, encoding="utf-8") as f:
            name = f.read().strip()
    except OSError:
        name = ""
    # Lowercase: positron's hostname prints "Positron".
    return re.sub(r"[^\w.-]", "", (name or socket.gethostname()).lower()) or "host"


class Clicks:
    """Opened results: this host's file, appended to, plus every other host's copy."""

    def __init__(self, folder: str) -> None:
        self.folder = folder
        self.own = os.path.join(folder, host_name() + ".jsonl")
        self.lock = threading.Lock()
        self.seen: dict[str, tuple[float, int]] = {}  # file -> (mtime, size) when read
        self.rows: dict[str, list[Click]] = {}
        self.checked = 0.0

    def add(self, query: str, url: str, title: str) -> None:
        row = {"t": int(time.time()), "q": query, "u": url} | ({"n": title} if title else {})
        line = json.dumps(row, ensure_ascii=False, separators=(",", ":"))
        with self.lock:
            with open(self.own, "a", encoding="utf-8") as f:
                f.write(line + "\n")
            self.checked = 0.0  # read it back on the next search

    def all(self) -> list[Click]:
        with self.lock:
            now = time.monotonic()
            if now - self.checked >= RELOAD_EVERY:
                self.checked = now
                self._reload()
            return [c for rows in self.rows.values() for c in rows]

    def _reload(self) -> None:
        try:
            names = [n for n in os.listdir(self.folder) if n.endswith(".jsonl")]
        except OSError:
            names = []
        for gone in set(self.rows) - set(names):
            del self.rows[gone]
            self.seen.pop(gone, None)
        for name in names:
            path = os.path.join(self.folder, name)
            try:
                st = os.stat(path)
            except OSError:
                continue
            if self.seen.get(name) == (st.st_mtime, st.st_size):
                continue
            self.seen[name] = (st.st_mtime, st.st_size)
            self.rows[name] = list(read_clicks(path))

    def learned(self, query: frozenset[str], key_of: t.Callable[[str], str]) -> tuple[dict[str, Learned], dict[str, float]]:
        """Weighted opens for a search, by page key (with the latest URL and title) and by site."""
        pages: dict[str, Learned] = {}
        sites: dict[str, float] = defaultdict(float)
        if not query:
            return pages, sites
        now = time.time()
        for c in self.all():
            shared = len(query & c.words)
            if not shared:
                continue
            overlap = shared / len(query | c.words)
            if overlap < CLICK_OVERLAP:
                continue
            w = overlap * 0.5 ** (max(0.0, now - c.at) / HALF_LIFE)
            key = key_of(c.url)
            seen = pages.get(key)
            pages[key] = Learned((seen.weight if seen else 0.0) + w, c.url, c.title or (seen.title if seen else ""))
            sites[site_of(c.url)] += w
        return pages, sites


def read_clicks(path: str) -> t.Iterator[Click]:
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            try:
                row = json.loads(line)
                yield Click(float(row["t"]), terms(str(row["q"])), str(row["u"]), str(row.get("n") or ""))
            except (ValueError, KeyError, TypeError):
                continue  # a line cut short by a copy in progress


CLICKS = Clicks(CLICKS_DIR)


def click() -> flask.Response:
    """``POST /click`` ``{"q": search, "url": opened result, "title": its title}``: remember what was opened."""
    if (flask.request.content_length or 0) > MAX_BODY:
        return flask.Response(status=413)
    try:
        body = json.loads(flask.request.get_data(as_text=True))
        query, url = str(body["q"]).strip(), str(body["url"]).strip()
        title = re.sub(r"\s+", " ", str(body.get("title") or "")).strip()[:MAX_Q]
    except (ValueError, KeyError, TypeError):
        return flask.Response(status=400)
    if not query or len(query) > MAX_Q or len(url) > MAX_URL or not url.startswith(("http://", "https://")):
        return flask.Response(status=400)
    try:
        CLICKS.add(query, url, title)
    except OSError as e:
        log.warning("cannot remember a click in %s: %s", CLICKS_DIR, e)
    return flask.Response(status=204)


# ---------- votes ----------


class Plan:
    """One search's votes: per page key, the best vote from each index."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.count: dict[str, int] = defaultdict(int)
        self.votes: dict[str, dict[str, float]] = {}
        self.first_web_page = False


def is_main(result: t.Any) -> bool:
    if isinstance(result, MainResult):
        return True
    if isinstance(result, Result) or not isinstance(result, dict):
        return False  # answers and other typed results
    return "url" in result and not any(k in result for k in ("suggestion", "answer", "correction", "infobox", "engine_data"))


def note(search: t.Any, result: t.Any, key: str) -> None:
    """Count a result toward its engine's ranking (after same-page merging set its URL)."""
    plan: Plan | None = getattr(search, "avogadro_plan", None)
    if plan is None or not is_main(result):
        return
    engine = getattr(result, "engine", "") or ""
    index = INDEXES.get(engine, engine)
    weight = float(getattr(searx.engines.engines.get(engine), "weight", 1.0) or 1.0) * INDEX_WEIGHTS.get(index, 1.0)
    with plan.lock:
        plan.count[engine] += 1
        vote = weight / (K + plan.count[engine])
        votes = plan.votes.setdefault(key, {})
        votes[index] = max(votes.get(index, 0.0), vote)


def install(search: t.Any, key_of: t.Callable[[str], str]) -> None:
    """Order this search's results here rather than with SearXNG's score and grouping."""
    plan: Plan | None = getattr(search, "avogadro_plan", None)
    rc = getattr(search, "result_container", None)
    if plan is None or rc is None:
        return
    sq = search.search_query
    query = getattr(sq, "query", "") or ""
    plan.first_web_page = getattr(sq, "pageno", 1) == 1 and "general" in (getattr(sq, "categories", None) or [])
    rc.get_ordered_results = lambda: ordered(rc, plan, query, key_of)


def ordered(rc: t.Any, plan: Plan, query: str, key_of: t.Callable[[str], str]) -> list:
    if not rc._closed:  # pylint: disable=protected-access
        rc.close()  # SearXNG's own score stays as the tie-break
    if rc._main_results_sorted:  # pylint: disable=protected-access
        return rc._main_results_sorted  # pylint: disable=protected-access
    pages, sites = CLICKS.learned(terms(query), key_of)
    named = words(query)
    results = list(rc.main_results_map.values())
    if plan.first_web_page:
        results += returned(pages, {key_of(getattr(r, "url", "") or "") for r in results})
    scored = []
    for r in results:
        url = getattr(r, "url", "") or ""
        key = key_of(url)
        votes = plan.votes.get(key)
        score = sum(votes.values()) if votes else sum(1 / (K + p) for p in (getattr(r, "positions", None) or []))
        priority = getattr(r, "priority", "")
        if priority == "low":
            score *= 0.2
        elif priority == "high":
            score += 0.5
        host = site_of(url)
        if names_site(named, host):
            score += NAV_HOME if is_home(url) else NAV_PAGE
        page = pages.get(key)
        score += CLICK_PAGE * min(page.weight if page else 0.0, CLICK_CAP) + CLICK_SITE * min(sites.get(host, 0.0), CLICK_CAP)
        scored.append((score, getattr(r, "score", 0.0) or 0.0, r))
    scored.sort(key=lambda s: (s[0], s[1]), reverse=True)
    ranked = []
    for score, _, r in scored:
        r.score = round(score, 4)
        ranked.append(r)
    rc._main_results_sorted = ranked  # pylint: disable=protected-access
    return ranked


def returned(pages: dict[str, Learned], present: set[str]) -> list[LegacyResult]:
    """Pages opened for this search that no engine returned this time."""
    back = []
    for key, page in pages.items():
        if key in present or page.weight < CLICK_RETURN:
            continue
        r = LegacyResult({"url": page.url, "title": page.title or site_of(page.url)})
        r.normalize_result_fields()
        r.positions = []
        back.append(r)
    return back
