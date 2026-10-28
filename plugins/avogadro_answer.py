# SPDX-License-Identifier: AGPL-3.0-or-later
"""Short answers to questions, written by a local model from a search's own results, at ``/answer``.

``POST /answer`` with ``{"q": "...", "results": [{"url", "title", "content"}, ...]}``
(the top results, best first, at most ``MAX_SOURCES``) streams back plain text: one to
three sentences, each citing the results it rests on as ``[1]``, ``[2][3]``, numbered in
the order the results were sent. An empty body means the results don't answer the
question. 503 when no model is configured or it can't be reached, 400 for a bad request.

How it stays quick and close to its sources:

- the result pages are fetched in parallel, until ``ENOUGH_PAGES`` have come after
  ``FETCH_SOON`` seconds or ``FETCH_WAIT`` seconds have passed; a page that is slower,
  blocked or not HTML is represented by its result snippet alone;
- meanwhile a one-word request clears the model's cache of the last long prompt (it
  costs about half a second when it comes first in a prompt that differs), and the batch
  job sharing the model is asked to step aside;
- each page is cut into passages and only those that share the question's terms (BM25),
  plus each page's lead, go to the model, about ``BUDGET`` characters in all;
- the model is told to answer only from those sources and to say NONE otherwise, to give one
  answer rather than each source's figure, and to name first how its sources relate (a line
  that is not passed on); each source carries its page's date, so "most recent" can be judged.

``POST /answer/warm`` loads the model and clears its cache ahead of a question (the page
calls it while one is being typed), at most once a minute. Answers are cached for an hour; an empty one is not.

With ``"local": true`` (and optionally ``"model"``, for the log) the client writes the answer with its own
model, such as the Android app with Turing on the phone: the pages are read the same way, but the reply is
JSON, ``{"system": ..., "user": ...}``, the prompt this server would have sent, or ``{"answer": ...}`` when
this server's model already answered. The model here isn't loaded, held or asked; answers off doesn't matter.

Settings come from the environment: ``OLLAMA_URL`` (unset turns answers off),
``OLLAMA_MODEL`` and ``ANSWER_HOLD_URL``, an optional URL that is sent
``{"seconds": n}`` when an answer starts (n > 0) and ends (n = 0), so a batch job sharing
the model can step aside.
"""

import datetime as dt
import ipaddress
import json
import logging
import math
import os
import re
import socket
import threading
import time
import typing as t
import urllib.error
import urllib.request
import zlib
from collections import Counter
from concurrent.futures import FIRST_COMPLETED, Future, ThreadPoolExecutor, wait
from urllib.parse import urlparse

import flask
import lxml.html

from .avogadro_stock import AGENT

log = logging.getLogger("searx.plugins.avogadro")

OLLAMA_URL = os.environ.get("OLLAMA_URL", "").rstrip("/")
OLLAMA_MODEL = os.environ.get("OLLAMA_MODEL", "gemma4:26b-a4b-it-qat")
HOLD_URL = os.environ.get("ANSWER_HOLD_URL", "")
KEEP_ALIVE = "30m"
HOLD_SECONDS = 15

MAX_SOURCES = 5
FETCH_SOON = 0.5           # seconds after which ENOUGH_PAGES pages will do
ENOUGH_PAGES = 3
FETCH_WAIT = 1.0           # seconds to wait for result pages before answering without the rest
FETCH_FIRST = 2.0          # ... unless none has come: then this long for the first (a slow host)
PAGE_TIMEOUT = 4
MAX_PAGE = 700_000         # bytes of HTML read from a page: the lead and first sections of even a long article
BUDGET = 5500              # characters of source text for the model (~1,400 tokens)
LEAD_CHARS = 450
PASSAGE_CHARS = 650
PER_SOURCE = 3             # ranked passages per page, beside its lead
ANSWER_TTL = 3600

# Tested on a fixed set of questions against gemma4:26b-a4b. Two things made the difference for a model this
# size: a short reminder at the end of the user turn (rules far up the prompt are followed loosely), and
# having the model first name how its sources relate, after Google Research's taxonomy of knowledge
# conflicts in RAG ("DRAGged into Conflicts", 2025). Together they cut answers that list every source's
# figure from a third to one in twenty, and shortened them. Worked examples made it worse: the model
# copied their "sources disagree" into questions of fact.
SYSTEM = """You answer a web search question from the numbered search results you are given, like the short answer at the top of a search page. Today is {today}.

How to answer:
- Lead with the answer itself. One to three plain sentences, at most 60 words in all. No preamble, no markdown, no lists.
- Use only what the sources say, never your own knowledge. Keep names, figures, units and dates as the sources write them.
- End every sentence with the numbers of the sources that support it, like [2] or [1][3], at most three per sentence.
- If the sources don't answer the question, reply with exactly NONE.

Give one answer, not a survey of the sources:
- Figures that differ only by rounding, precision, unit conversion or measuring method are the same answer. Give the most precise or most recent one, once, citing every source that agrees with it.
- When an older and a newer figure differ (a 2020 count and a 2025 estimate), give the newest with its year. Add the older one only if it is an official count, in a few words.
- Facts that fit together (an average and a range, a total and a part) belong in one answer: combine them.
- Ignore a source that is plainly wrong, outdated or off topic.
- Never write "other sources say", "other sources list" or "some sources state", and never list alternative figures one by one.
- Say that views differ only for opinions, predictions or unsettled research (is coffee healthy, which language is better), never for a fact that can be looked up or measured. Then give each side in a few words.

Before the answer, write one line that says how the sources relate on this question:
Kind: same (they agree, maybe at different precision), newer (older and newer figures), compatible (different facts that fit together), views (opinions or unsettled research), or none (they don't answer it).
Then write the answer on the next line. After "Kind: none", write nothing more."""

# Last in the user turn, where a model this size heeds it most.
REMINDER = (
    "Answer the question in one to three sentences. Give one answer: when the sources give slightly different "
    "figures for the same thing, state only the best one (most precise, or most recent) and cite every source "
    "that gives it. Don't mention the other figures or what \"other sources\" say."
)
KIND_LINE = re.compile(r"kind:([^\n]*)\n", re.I)

pool = ThreadPoolExecutor(max_workers=16, thread_name_prefix="avogadro-answer")
warmed = [0.0]
warm_lock = threading.Lock()


class Answers:
    """Finished answers by question and sources, for an hour."""

    def __init__(self, size: int = 256) -> None:
        self.size = size
        self.lock = threading.Lock()
        self.items: dict[str, tuple[float, str]] = {}

    def get(self, key: str) -> str | None:
        with self.lock:
            hit = self.items.get(key)
        return hit[1] if hit and hit[0] > time.monotonic() else None

    def put(self, key: str, text: str) -> None:
        with self.lock:
            if len(self.items) >= self.size:
                for k in sorted(self.items, key=lambda k: self.items[k][0])[: self.size // 4]:
                    del self.items[k]
            self.items[key] = (time.monotonic() + ANSWER_TTL, text)


answers = Answers()


# ---------- fetching pages ----------

def public_host(host: str) -> bool:
    """Only pages on the public internet: nothing on this host, the LAN or the tailnet."""
    try:
        infos = socket.getaddrinfo(host, None, proto=socket.IPPROTO_TCP)
    except OSError:
        return False
    return bool(infos) and all(ipaddress.ip_address(info[4][0].split("%")[0]).is_global for info in infos)


class PublicRedirects(urllib.request.HTTPRedirectHandler):
    max_redirections = 4

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: ANN001, D102
        u = urlparse(newurl)
        if u.scheme not in ("http", "https") or not u.hostname or not public_host(u.hostname):
            raise urllib.error.HTTPError(newurl, code, "redirect to a private address", headers, fp)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


opener = urllib.request.build_opener(PublicRedirects())


def fetch_page(url: str) -> bytes | None:
    u = urlparse(url)
    if u.scheme not in ("http", "https") or not u.hostname or not public_host(u.hostname):
        return None
    req = urllib.request.Request(url, headers={
        "User-Agent": AGENT,
        "Accept": "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5",
        "Accept-Language": "en-US,en;q=0.8",
        "Accept-Encoding": "gzip",
    })
    with opener.open(req, timeout=PAGE_TIMEOUT) as resp:
        kind = resp.headers.get_content_type()
        if kind not in ("text/html", "application/xhtml+xml"):
            return None
        if resp.headers.get("Content-Encoding", "").lower() != "gzip":
            return resp.read(MAX_PAGE)
        unzip, raw = zlib.decompressobj(47), b""
        while len(raw) < MAX_PAGE and (chunk := resp.read(65536)):
            raw += unzip.decompress(chunk, MAX_PAGE - len(raw))
        return raw


class Page(t.NamedTuple):
    date: str | None       # when it was last updated or published, as YYYY-MM-DD
    passages: list[str]


def read_page(url: str) -> Page | None:
    raw = fetch_page(url)
    page = parse(raw) if raw else None
    return page if page and page.passages else None


# ---------- passages ----------

DROP_TAGS = ("script", "style", "noscript", "template", "svg", "math", "iframe", "form", "button", "select",
             "nav", "header", "footer", "aside", "dialog", "link", "meta")
DROP_ROLES = {"navigation", "banner", "contentinfo", "complementary", "search", "dialog", "alert", "menu", "menubar"}
# Whole class or id tokens of page furniture (Wikipedia's included). Exact tokens only, so an
# article's own container is never dropped for a class like "comments-enabled".
DROP_TOKENS = {
    "nav", "navbar", "navigation", "menu", "site-header", "site-footer", "footer", "sidebar", "breadcrumb",
    "breadcrumbs", "cookie", "cookies", "cookie-banner", "consent", "share", "sharing", "social", "related",
    "related-posts", "comments", "newsletter", "subscribe", "promo", "advert", "ad", "ads", "advertisement",
    "navbox", "vertical-navbox", "reflist", "references", "reference", "mw-editsection", "toc", "hatnote",
    "metadata", "catlinks", "noprint", "ambox", "sistersitebox", "mw-jump-link", "shortdescription",
}
BLOCKS = {"p", "li", "tr", "dd", "dt", "blockquote", "pre", "figcaption", "caption", "h1", "h2", "h3", "h4"}
HEADINGS = {"h1", "h2", "h3", "h4"}
GROUPED = {"li", "tr", "dd", "dt"}
MAIN = ("//main", "//*[@role='main']", "//*[@id='mw-content-text']", "//article")
SENTENCE_END = re.compile(r"(?<=[.!?])\s+")
SPACE = re.compile(r"\s+")
# Where pages say when they were updated, then when published (Open Graph, schema.org, Dublin Core).
MODIFIED = {"article:modified_time", "og:updated_time", "datemodified", "last-modified"}
PUBLISHED = {"article:published_time", "datepublished", "date", "dc.date", "dc.date.issued", "pubdate", "publish-date"}
LD_DATE = re.compile(r'"date(Modified|Published)"\s*:\s*"(\d{4}-\d{2}-\d{2})')
DAY = re.compile(r"\b((?:19|20)\d{2})-(\d{2})-(\d{2})")


def furniture(el: t.Any) -> bool:
    if el.get("role") in DROP_ROLES or el.get("aria-hidden") == "true" or el.get("hidden") is not None:
        return True
    tokens = set((el.get("class") or "").split())
    if el.get("id"):
        tokens.add(el.get("id"))
    return bool(tokens & DROP_TOKENS)


def split_long(text: str) -> list[str]:
    if len(text) <= PASSAGE_CHARS:
        return [text]
    out, cur = [], ""
    for s in SENTENCE_END.split(text):
        if cur and len(cur) + len(s) + 1 > PASSAGE_CHARS:
            out.append(cur)
            cur = ""
        cur = f"{cur} {s}".strip()
    if cur:
        out.append(cur)
    return [p[:PASSAGE_CHARS] for p in out]


def page_date(doc: t.Any) -> str | None:
    """The day a page was last updated, else published, from its metadata."""
    found: dict[str, str] = {}
    for m in doc.iter("meta"):
        key = (m.get("property") or m.get("name") or m.get("itemprop") or "").lower()
        day = DAY.search(m.get("content") or "")
        if day and (key in MODIFIED or key in PUBLISHED):
            found.setdefault("modified" if key in MODIFIED else "published", day.group(0))
    for script in doc.xpath("//script[@type='application/ld+json']"):
        for kind, day in LD_DATE.findall(script.text or ""):
            found.setdefault(kind.lower(), day)
    if not found:
        stamp = doc.xpath("string(//time[@datetime][1]/@datetime)")
        if day := DAY.search(stamp):
            found["published"] = day.group(0)
    day = found.get("modified") or found.get("published")
    return day if day and day <= (dt.date.today() + dt.timedelta(days=1)).isoformat() else None


def parse(raw: bytes) -> Page:
    """The page's date and its readable text as passages, in reading order."""
    doc = lxml.html.document_fromstring(raw)
    date = page_date(doc)
    for el in doc.xpath("|".join("//" + tag for tag in DROP_TAGS)):
        if el.getparent() is not None:
            el.drop_tree()
    for el in [el for el in doc.iter() if isinstance(el.tag, str) and furniture(el)]:
        if el.getparent() is not None and el.tag not in ("html", "body"):
            el.drop_tree()
    root = doc
    for path in MAIN:
        found = doc.xpath(path)
        if found and len(found[0].text_content()) > 600:
            root = found[0]
            break

    out: list[str] = []
    group = None
    for el in root.iter(*BLOCKS):
        if any(a.tag in BLOCKS for a in el.iterancestors()):
            continue
        if el.tag in HEADINGS:
            group = None
            continue
        if el.tag == "tr":
            cells = [SPACE.sub(" ", c.text_content()).strip() for c in el if c.tag in ("th", "td")]
            text = ": ".join(c for c in cells if c)
        else:
            text = SPACE.sub(" ", el.text_content()).strip()
        if not re.search(r"\w", text) or len(text) < (40 if el.tag == "p" else 10):
            group = None
            continue
        parent = el.getparent()
        if el.tag in GROUPED and group is parent and out and len(out[-1]) + len(text) < PASSAGE_CHARS:
            out[-1] += "; " + text
            continue
        group = parent if el.tag in GROUPED else None
        out.extend(split_long(text))
    return Page(date, out)


# ---------- choosing what the model reads ----------

STOP = set("""a an the of in on at to for from by with about as into is are was were be been being am do does did
can could should would will shall may might must has have had what whats who whom whose when where why how which
that this these those it its i me my you your we our they their he she his her him them there here than then so if
or and not no vs versus between much many long far get got does doing done one ones""".split())
WORD = re.compile(r"[^\W_]+(?:'[^\W_]+)?")


def stem(w: str) -> str:
    w = w.removesuffix("'s")
    if len(w) > 4 and w.endswith("ies"):
        return w[:-3] + "y"
    if len(w) > 3 and w.endswith("s") and not w.endswith(("ss", "us", "is")):
        return w[:-1]
    return w


def terms(text: str) -> list[str]:
    return [stem(w) for w in WORD.findall(text.lower())]


def rank(q: str, texts: list[str]) -> list[float]:
    """BM25 of each text against the question's content words."""
    asked = {w for w in terms(q) if w not in STOP} or set(terms(q))
    docs = [Counter(terms(x)) for x in texts]
    if not docs:
        return []
    n = len(docs)
    lengths = [d.total() for d in docs]
    avg = sum(lengths) / n or 1
    idf = {w: math.log(1 + (n - df + 0.5) / (df + 0.5)) for w in asked for df in [sum(1 for d in docs if w in d)]}
    scores = []
    for d, size in zip(docs, lengths):
        norm = 1.2 * (0.25 + 0.75 * size / avg)
        scores.append(sum(idf[w] * d[w] * 2.2 / (d[w] + norm) for w in asked if d[w]))
    return scores


def sources_text(q: str, results: list[dict], pages: list[Page | None]) -> str:
    picked: list[list[tuple[int, str]]] = [[] for _ in results]
    ranked = [0] * len(results)
    used = 0
    for i, r in enumerate(results):
        if r["content"]:
            picked[i].append((-1, r["content"]))
            used += len(r["content"])
    # Each page's lead, then the best passages anywhere, until the budget is spent.
    candidates: list[tuple[int, int, str]] = []
    for i, page in enumerate(pages):
        if not page:
            continue
        ps = page.passages
        lead = next((k for k, p in enumerate(ps) if len(p) >= 80), None)
        if lead is not None and ps[lead][:60] not in results[i]["content"]:
            picked[i].append((lead, ps[lead][:LEAD_CHARS]))
            used += min(len(ps[lead]), LEAD_CHARS)
        candidates += [(i, k, p) for k, p in enumerate(ps) if k != lead]
    for (i, k, p), s in sorted(zip(candidates, rank(q, [p for *_, p in candidates])), key=lambda x: -x[1]):
        if s <= 0 or used >= BUDGET:
            break
        if ranked[i] >= PER_SOURCE or len(p) > BUDGET - used + 200:
            continue
        picked[i].append((k, p))
        ranked[i] += 1
        used += len(p)

    blocks = []
    for i, r in enumerate(results):
        host = (urlparse(r["url"]).hostname or "").removeprefix("www.")
        date = pages[i].date if pages[i] else None
        body = "\n".join(p for _, p in sorted(picked[i]))
        blocks.append(f"[{i + 1}] {r['title']} ({host}{f', dated {date}' if date else ''})\n{body}".strip())
    return "\n\n".join(blocks)


# ---------- the model ----------

def ollama(path: str, body: dict, timeout: float) -> t.Any:
    req = urllib.request.Request(OLLAMA_URL + path, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"})
    return urllib.request.urlopen(req, timeout=timeout)


def hold(seconds: int) -> None:
    if not HOLD_URL:
        return
    try:
        req = urllib.request.Request(HOLD_URL, data=json.dumps({"seconds": seconds}).encode(),
                                     headers={"Content-Type": "application/json"})
        urllib.request.urlopen(req, timeout=1).close()
    except (urllib.error.URLError, OSError) as e:
        log.debug("hold %s failed: %s", seconds, e)


def system() -> str:
    return SYSTEM.format(today=dt.date.today().isoformat())


def user_turn(q: str, prompt: str) -> str:
    return f"<sources>\n{prompt}\n</sources>\n\nQuestion: {q}\n\n{REMINDER}"


def flush() -> None:
    """Loads the model if needed and leaves only the system prompt in its cache."""
    try:
        ollama("/api/chat", {
            "model": OLLAMA_MODEL, "stream": False, "think": False, "keep_alive": KEEP_ALIVE,
            "options": {"num_predict": 1},
            "messages": [{"role": "system", "content": system()}, {"role": "user", "content": "."}],
        }, 60).close()
    except (urllib.error.URLError, OSError) as e:
        log.warning("warming %s failed: %s", OLLAMA_MODEL, e)


def gather(jobs: list[Future], start: float) -> list[Page | None]:
    """Waits for result pages: all of them, or enough after FETCH_SOON, or what came by FETCH_WAIT,
    or on a slow host the first one by FETCH_FIRST."""
    def good(job: Future) -> bool:
        return job.done() and not job.exception() and bool(job.result())

    done, pending = wait(jobs, timeout=FETCH_SOON)
    while pending:
        have = sum(map(good, jobs))
        left = start + (FETCH_WAIT if have else FETCH_FIRST) - time.monotonic()
        if have >= ENOUGH_PAGES or left <= 0:
            break
        done, pending = wait(pending, timeout=left, return_when=FIRST_COMPLETED)
    pages: list[Page | None] = []
    for job in jobs:
        if job.done() and job.exception():
            log.debug("page skipped: %s", job.exception())
        pages.append(job.result() if good(job) else None)
    return pages


def answer_of(raw: str, done: bool) -> str | None:
    """The answer in what the model has written so far, without its kind line: None while that line is
    unfinished, empty for "Kind: none"."""
    s = raw.lstrip()
    if "kind:".startswith(s[:5].lower()):
        m = KIND_LINE.match(s)
        if not m:
            return "" if done else None
        if m.group(1).strip().lower().startswith("none"):
            return ""
        s = s[m.end():].lstrip()
    return s


def stream(resp: t.Any, key: str) -> t.Iterator[str]:
    """Relays the model's words as they come, holding back its kind line and the first few words to
    catch NONE."""
    text, answer, sent, done = "", "", 0, False
    try:
        for line in resp:
            msg = json.loads(line)
            text += (msg.get("message") or {}).get("content") or ""
            done = bool(msg.get("done"))
            got = answer_of(text, done)
            if got is None:
                continue
            answer = got
            if not sent:
                if len(answer) < 5 and not done:
                    continue
                if not answer or answer.upper().startswith("NONE"):
                    answer, done = "", True
                    break
            if len(answer) > sent:
                yield answer[sent:]
                sent = len(answer)
            if done:
                break
        # An empty answer may only mean the pages were slow this time; ask again next time.
        if done and answer.strip():
            answers.put(key, answer.strip())
    finally:
        resp.close()
        pool.submit(hold, 0)


# ---------- routes ----------

def reply(text: str, status: int = 200) -> flask.Response:
    resp = flask.Response(text, status=status, mimetype="text/plain")
    resp.headers["Cache-Control"] = "no-store"
    return resp


def clean(r: t.Any) -> dict | None:
    if not isinstance(r, dict) or not isinstance(r.get("url"), str):
        return None
    u = urlparse(r["url"])
    if u.scheme not in ("http", "https") or not u.hostname or len(r["url"]) > 2048:
        return None
    text = lambda v, n: SPACE.sub(" ", v).strip()[:n] if isinstance(v, str) else ""  # noqa: E731
    return {"url": r["url"], "title": text(r.get("title"), 200), "content": text(r.get("content"), 600)}


def view() -> flask.Response:
    if not flask.request.is_json or (flask.request.content_length or 0) > 65536:
        return reply("send JSON", 400)
    body = flask.request.get_json(silent=True) or {}
    # A client with its own model on the device ("local": true, the Android app with Turing) gets the
    # prompt instead of an answer: the pages are read here as usual, and the model here is left alone.
    local = body.get("local") is True
    if not OLLAMA_URL and not local:
        return reply("answers are off", 503)
    q = SPACE.sub(" ", str(body.get("q") or "")).strip()
    results = [r for r in map(clean, body.get("results") or []) if r][:MAX_SOURCES]
    if not q or len(q) > 300 or not results:
        return reply("needs q and results", 400)

    key = json.dumps([q.lower(), [r["url"] for r in results]])
    hit = answers.get(key)
    if local:
        return local_prompt(q, results, hit, str(body.get("model") or "a local model")[:60])
    if hit is not None:
        return reply(hit)

    start = time.monotonic()
    pool.submit(hold, HOLD_SECONDS)
    pool.submit(flush)
    pages = gather([pool.submit(read_page, r["url"]) for r in results], start)

    prompt = sources_text(q, results, pages)
    fetched = time.monotonic()
    try:
        resp = ollama("/api/chat", {
            "model": OLLAMA_MODEL,
            "stream": True,
            "think": False,
            "keep_alive": KEEP_ALIVE,
            "options": {"temperature": 0.1, "num_predict": 200},
            "messages": [
                {"role": "system", "content": system()},
                {"role": "user", "content": user_turn(q, prompt)},
            ],
        }, 30)
    except (urllib.error.URLError, OSError) as e:
        log.warning("answer model unavailable: %s", e)
        pool.submit(hold, 0)
        return reply("model unavailable", 503)
    out = flask.Response(stream(resp, key), mimetype="text/plain")
    out.headers["Cache-Control"] = "no-store"
    out.headers["Server-Timing"] = (f'pages;dur={(fetched - start) * 1000:.0f};desc="{sum(map(bool, pages))}/{len(pages)} pages, '
                                    f'{len(prompt)} chars", model;dur={(time.monotonic() - fetched) * 1000:.0f}')
    out.headers["X-Accel-Buffering"] = "no"
    return out


def local_prompt(q: str, results: list[dict], hit: str | None, model: str) -> flask.Response:
    """The system prompt and user turn for an answer the client's own model writes. The client relays it
    the way stream() does: without the "Kind:" line, and nothing for NONE. An answer this server's model
    already gave comes back instead, as {"answer": ...}."""
    if hit is not None:
        out = flask.jsonify({"answer": hit})
    else:
        start = time.monotonic()
        pages = gather([pool.submit(read_page, r["url"]) for r in results], start)
        prompt = sources_text(q, results, pages)
        log.info("answer written on the device by %s: %d chars from %d/%d pages", model, len(prompt),
                 sum(map(bool, pages)), len(pages))
        out = flask.jsonify({"system": system(), "user": user_turn(q, prompt)})
    out.headers["Cache-Control"] = "no-store"
    return out


def warm() -> flask.Response:
    if OLLAMA_URL:
        with warm_lock:
            due = time.monotonic() - warmed[0] > 60
            if due:
                warmed[0] = time.monotonic()
        if due:
            pool.submit(flush)
    return flask.Response(status=204)
