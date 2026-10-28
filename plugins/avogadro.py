# SPDX-License-Identifier: AGPL-3.0-or-later
"""Plugin for the Avogadro page: same-page merging, image and favicon proxying.

Same page, one result. SearXNG merges results only when their URLs match exactly,
so a mobile or AMP copy of a page, or one paper listed by two sites, shows up twice
and neither copy gets credit for the other's engines. Before results are merged
this plugin:

- rewrites URLs whose desktop form is certain: Wikimedia mobile hosts
  (``en.m.wikipedia.org``), Google's AMP cache and AMP viewer links, AMP query
  parameters;
- gives every result a set of keys (the page with ``www.``/``m.``/``mobile.``/
  ``amp.`` hosts and ``/amp`` paths folded away, plus a paper's DOI and PubMed
  id) and, when a key was already seen in this search, gives the result the URL
  of the first result with that key. SearXNG then merges the two and pools their
  engines and positions, so the page ranks as one.

Images. The JSON API hands out raw image URLs, so the page would fetch them
straight from image hosts. This signs each one, in results and infoboxes alike,
for SearXNG's own image proxy at ``/image``, and adds ``/favicon/<host>``, which
answers from SearXNG's favicon resolver cache.

Stocks. ``/stock`` serves a quote and price series for the page's stock card and
for other clients; see ``avogadro_stock``.

Answers. ``/answer`` writes a short, cited answer to a question from the top
results' pages with a local model; see ``avogadro_answer``.

Order. Results are ordered by one vote per independent index, the site a search
names and the pages you open (``POST /click``), not by SearXNG's score and
grouping; see ``avogadro_rank``.
"""

import logging
import re
import threading
import typing as t
from urllib.parse import parse_qsl, unquote, urlencode, urlparse, urlunparse

import flask

from searx import settings
from searx.favicons.proxy import search_favicon
from searx.webutils import new_hmac

from . import Plugin, PluginInfo, avogadro_answer, avogadro_rank, avogadro_stock

if t.TYPE_CHECKING:
    from searx.extended_types import SXNG_Request
    from searx.plugins import PluginCfg
    from searx.result_types import Result
    from searx.search import SearchWithPlugins

log = logging.getLogger("searx.plugins.avogadro")

IMAGE_FIELDS = ("img_src", "thumbnail", "thumbnail_src")
HOST = re.compile(r"^(?=.{1,253}$)[a-z0-9-]+(\.[a-z0-9-]+)+(:\d{1,5})?$")
RESOLVER = "duckduckgo"
MAX_AGE = 30 * 86400

# Results whose URL names a page; images merge on their image, answers have no page.
PAGE_TEMPLATES = ("default.html", "paper.html", "videos.html", "packages.html")

WIKIMEDIA_MOBILE = re.compile(
    r"^(?P<lang>[a-z0-9-]+)\.m\.(?P<site>wikipedia|wiktionary|wikibooks|wikinews|wikiquote|"
    r"wikisource|wikiversity|wikivoyage|wikimedia)\.org$"
)
WIKIMEDIA_MOBILE_ROOT = {"m.wikidata.org": "www.wikidata.org", "m.mediawiki.org": "www.mediawiki.org"}
AMP_CACHE = re.compile(r"^[a-z0-9-]+\.cdn\.ampproject\.org$")
AMP_CACHE_PATH = re.compile(r"^/[cv]/(?P<s>s/)?(?P<rest>.+)$")
GOOGLE_AMP_PATH = re.compile(r"^/amp/(?P<s>s/)?(?P<rest>.+)$")
AMP_PARAMS = {"amp", "_amp", "outputtype", "usqp"}
COPY_LABELS = ("www.", "m.", "mobile.", "amp.")
PUBMED_ID = (
    re.compile(r"^(?:www\.)?ncbi\.nlm\.nih\.gov$|^pubmed\.ncbi\.nlm\.nih\.gov$"),
    re.compile(r"^/(?:pubmed/)?(\d{4,9})/?$"),
)
EUROPEPMC_ID = (re.compile(r"^(?:www\.)?europepmc\.org$"), re.compile(r"^/(?:article|abstract)/MED/(\d{4,9})/?$", re.I))


def proxied(url: str) -> str:
    """Return ``url`` signed for SearXNG's image proxy."""
    if url.startswith("//"):
        url = "https:" + url
    if not url.startswith(("http://", "https://")):
        return url
    h = new_hmac(settings["server"]["secret_key"], url.encode())
    return "/image?" + urlencode({"url": url, "h": h})


def swap_images(item: t.Any) -> None:
    for field in IMAGE_FIELDS:
        try:
            value = item[field]
        except (KeyError, AttributeError):
            continue
        if isinstance(value, str) and value:
            try:
                item[field] = proxied(value)
            except (KeyError, AttributeError, TypeError):
                pass


# ---------- same page, one result ----------


def desktop_url(url: str) -> str:
    """The certain desktop form of a mobile or AMP URL (or ``url`` unchanged)."""
    p = urlparse(url)
    host = p.hostname or ""
    if AMP_CACHE.match(host) and (m := AMP_CACHE_PATH.match(p.path)):
        scheme = "https" if m["s"] else "http"
        return desktop_url(f"{scheme}://{m['rest']}" + (f"?{p.query}" if p.query else ""))
    if host in ("www.google.com", "google.com") and (m := GOOGLE_AMP_PATH.match(p.path)):
        scheme = "https" if m["s"] else "http"
        return desktop_url(f"{scheme}://{unquote(m['rest'])}" + (f"?{p.query}" if p.query else ""))
    changed = False
    if m := WIKIMEDIA_MOBILE.match(host):
        p = p._replace(netloc=p.netloc.replace(host, f"{m['lang']}.{m['site']}.org", 1))
        changed = True
    elif host in WIKIMEDIA_MOBILE_ROOT:
        p = p._replace(netloc=p.netloc.replace(host, WIKIMEDIA_MOBILE_ROOT[host], 1))
        changed = True
    if p.query:
        params = parse_qsl(p.query, keep_blank_values=True)
        kept = [(k, v) for k, v in params if k.lower() not in AMP_PARAMS]
        if len(kept) != len(params):
            # Only re-encode a query that actually loses an AMP parameter.
            p = p._replace(query=urlencode(kept))
            changed = True
    return urlunparse(p) if changed else url


def page_key(url: str) -> str:
    """The page behind a URL: scheme, copy hosts, AMP paths and trailing slashes folded away."""
    p = urlparse(url)
    host = (p.hostname or "").lower()
    while True:
        label = next((lb for lb in COPY_LABELS if host.startswith(lb) and host.count(".") > 1), None)
        if not label:
            break
        host = host[len(label):]
    path = re.sub(r"(?:/amp|\.amp)(?=/?$)", "", p.path)
    path = re.sub(r"^/amp(?=/)", "", path)
    path = path.rstrip("/") or "/"
    query = urlencode(sorted(parse_qsl(p.query, keep_blank_values=True)))
    return f"page:{host}{path}?{query}"


def paper_keys(result: t.Any, url: str) -> list[str]:
    keys = []
    try:
        doi = result["doi"]
    except (KeyError, AttributeError):
        doi = ""
    if isinstance(doi, str) and doi.strip():
        doi = re.sub(r"^(?:https?://(?:dx\.)?doi\.org/|doi:)", "", doi.strip(), flags=re.I).lower()
        keys.append("doi:" + doi)
    p = urlparse(url)
    for hosts, path in (PUBMED_ID, EUROPEPMC_ID):
        if hosts.match((p.hostname or "").lower()) and (m := path.match(p.path)):
            keys.append("pmid:" + m[1])
    return keys


# Fields SearXNG merges itself (or that identify the copy) are left alone.
OWN_FIELDS = {"url", "parsed_url", "engine", "engines", "positions", "score", "template", "title", "content"}


def blank(value: t.Any) -> bool:
    return value is None or value == "" or (isinstance(value, (list, set, tuple, dict)) and not value)


_MISSING = object()


def field_names(item: t.Any) -> list[str]:
    """The fields of a typed result, or the keys of a legacy (dict) result."""
    fields = getattr(type(item), "__struct_fields__", None)
    if fields is not None:
        return list(fields)
    return list(item.keys()) if isinstance(item, dict) else []


def get_field(item: t.Any, name: str) -> t.Any:
    if isinstance(item, dict):
        return item.get(name)  # a legacy result simply lacks fields it never set
    try:
        return item[name]
    except (KeyError, AttributeError):
        return _MISSING  # not a field of this result type


def fill_gaps(into: t.Any, other: t.Any) -> None:
    """Copy fields ``into`` lacks from ``other`` (SearXNG's own merge skips fields set to "").

    The two copies can be different kinds of result (a typed result and a legacy dict).
    """
    for name in dict.fromkeys(field_names(into) + field_names(other)):
        if name in OWN_FIELDS:
            continue
        mine, theirs = get_field(into, name), get_field(other, name)
        if mine is _MISSING or theirs is _MISSING:
            continue
        if blank(mine) and not blank(theirs):
            try:
                into[name] = theirs
            except (KeyError, AttributeError, TypeError):
                pass


class Aliases:
    """Per-search map from a page's keys to the first result seen with them."""

    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.first: dict[str, tuple[str, t.Any]] = {}

    def resolve(self, keys: list[str], url: str, result: t.Any) -> tuple[str, t.Any]:
        with self.lock:
            target, first = next((self.first[k] for k in keys if k in self.first), (url, result))
            for k in keys:
                self.first.setdefault(k, (target, first))
            return target, first


def merge_copies(search: t.Any, result: t.Any) -> None:
    try:
        template, url = result["template"], result["url"]
    except (KeyError, AttributeError):
        return
    if template not in PAGE_TEMPLATES or not isinstance(url, str) or not url.startswith(("http://", "https://")):
        return
    aliases = getattr(search, "avogadro_aliases", None)
    clean = desktop_url(url)
    keys = [page_key(clean)] + (paper_keys(result, clean) if template == "paper.html" else [])
    target, first = aliases.resolve(keys, clean, result) if aliases else (clean, result)
    if first is not result:
        # Whichever copy SearXNG keeps should carry both copies' links and metadata.
        # This is a nicety: it must never stop the merge below.
        try:
            fill_gaps(first, result)
            fill_gaps(result, first)
        except Exception:  # pylint: disable=broad-except
            log.exception("could not fill gaps between copies of %s", target)
    if target != url:
        result["url"] = target
        result["parsed_url"] = urlparse(target)


def process(search: t.Any, result: t.Any) -> None:
    """Everything this plugin does to one result, in order."""
    # Proxy images first: merging copies fields between copies, and only proxied
    # image URLs may travel that way.
    swap_images(result)
    merge_copies(search, result)
    # Ranks count once copies share a URL, so the page gets every copy's votes.
    url = get_field(result, "url")
    if isinstance(url, str) and url.startswith(("http://", "https://")):
        avogadro_rank.note(search, result, rank_key(url))


def rank_key(url: str) -> str:
    return page_key(desktop_url(url))


def favicon(host: str):
    host = host.lower()
    if not HOST.match(host):
        return "", 400
    data, mime = search_favicon(RESOLVER, host)
    if data is None or mime is None:
        resp = flask.Response(status=404)
    else:
        resp = flask.Response(data, mimetype=mime)
    resp.headers["Cache-Control"] = f"max-age={MAX_AGE}"
    return resp


@t.final
class SXNGPlugin(Plugin):
    """Same-page merging plus image and favicon proxying for the Avogadro page."""

    id = "avogadro"

    def __init__(self, plg_cfg: "PluginCfg") -> None:
        super().__init__(plg_cfg)
        self.info = PluginInfo(
            id=self.id,
            name="Avogadro",
            description="Merge copies of the same page, order results by index and by what you open, serve result images and favicons through this instance, serve stock quotes, and answer questions from the results",
            preference_section="privacy",
        )

    def init(self, app: "flask.Flask") -> bool:
        app.add_url_rule("/favicon/<host>", endpoint="avogadro_favicon", view_func=favicon)
        app.add_url_rule("/stock", endpoint="avogadro_stock", view_func=avogadro_stock.view)
        app.add_url_rule("/answer", endpoint="avogadro_answer", view_func=avogadro_answer.view, methods=["POST"])
        app.add_url_rule("/answer/warm", endpoint="avogadro_answer_warm", view_func=avogadro_answer.warm, methods=["POST"])
        app.add_url_rule("/click", endpoint="avogadro_click", view_func=avogadro_rank.click, methods=["POST"])
        return True

    def pre_search(self, request: "SXNG_Request", search: "SearchWithPlugins") -> bool:
        search.avogadro_aliases = Aliases()  # type: ignore[attr-defined]
        search.avogadro_plan = avogadro_rank.Plan()  # type: ignore[attr-defined]
        return True

    def on_result(self, request: "SXNG_Request", search: "SearchWithPlugins", result: "Result") -> bool:
        process(search, result)
        return True

    def post_search(self, request: "SXNG_Request", search: "SearchWithPlugins") -> None:
        for box in search.result_container.infoboxes:
            swap_images(box)
        avogadro_rank.install(search, rank_key)
