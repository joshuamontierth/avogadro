# Avogadro

A cool, modern front end for [SearXNG](https://github.com/searxng/searxng).

Avogadro replaces SearXNG's stock search page with a clean, quiet one. Behind it, a live hydrogen atom turns slowly. Its electron cloud is drawn from real orbitals and steps through 1s, 2p, 3d, 3s and 4f shapes. A search excites the atom into a new orbital, which gives off a photon as the results come in.

## Features

- **Results:** All, Images, News, Videos, IT and Science tabs, a time filter, Wikipedia infoboxes, instant answers, "did you mean" and related searches.
- **Images:** a justified grid with a viewer you can step through with the arrow keys.
- **Answers:** a question ("how tall is everest", "boiling point of water", "rust vs go") gets a short answer above the results, written by a local model from the top results' pages as you watch, each sentence citing its sources. Needs an [Ollama](https://ollama.com) server, which docker-compose.yml can run for you; without one, questions get plain results.
- **Stocks:** a ticker search ("NVDA", "$spy", "nvidia stock") shows a quote card: price, change and after-hours price, a chart from one day to all time that you can scrub, and the day's figures.
- **Keyboard:** `/` focuses the search box; `j` and `k` move through results, and `Enter` opens one.
- **Autocomplete,** plus OpenSearch, so browsers can offer it as a search engine.
- **Light and dark** themes that follow your system, and a layout built for phones as well as desktops.
- **Reduced motion:** with that setting on, the atom becomes a still picture.

## Privacy

The page only ever talks to your own server:

- A strict Content Security Policy blocks every third-party request.
- Result images go through SearXNG's own image proxy.
- Favicons come from SearXNG's cache.
- Stock quotes are fetched by your server, from CNBC, never by the browser.
- For an answer, your server fetches the top results' pages itself and sends them, with the question, to the Ollama server you configure. Only pages on the public internet are fetched.
- The font is self-hosted.
- Links don't send your search to the sites you open (`Referrer-Policy: no-referrer`).

## How it works

Avogadro uses two containers, plus a third for answers if you want the model on the same machine:

- **`searxng`:** the stock SearXNG image, with no published port. It loads `config/settings.yml` and the small `plugins/avogadro.py` plugin. The plugin signs result images for SearXNG's `/image` proxy, adds a `/favicon/<host>` route, serves quotes at `/stock` (`plugins/avogadro_stock.py`) and answers at `/answer` (`plugins/avogadro_answer.py`).
- **`ollama`** (optional, the `answers` profile): the model that writes [answers](#answers), plus a one-off `ollama-pull` that downloads it.
- **`avogadro`:** Caddy. It serves `frontend/`, plain HTML, CSS and JavaScript with no build step. It passes only these routes through to SearXNG: `/search?format=json`, `/autocompleter`, `/image`, `/favicon/*`, `/stock`, `/answer` and `/answer/warm`.

The atom is drawn in `frontend/cloud.js`. Each dot is a sample from the orbital's probability density |ψ|². The two colours are the two signs of the wavefunction.

### The stock API

`/stock` is plain JSON, so other tools can use it too. Its shape is kept stable:

```
GET /stock?symbol=NVDA&range=1D        range: 1D 5D 1M 6M YTD 1Y 5Y MAX (default 1D)

{
  "symbol": "NVDA", "name": "NVIDIA Corporation", "exchange": "NASDAQ", "currency": "USD",
  "price": 216.0, "change": 5.04, "change_pct": 2.39, "prev_close": 210.96,
  "state": "post", "as_of": 1793045700,
  "extended": {"state": "post", "price": 216.5, "change": 0.5, "change_pct": 0.23, "as_of": 1793059197},
  "range": "1D", "session": [1793021400, 1793044800],
  "points": [[1793021400, 213.4], ...],
  "stats": {"open": 213.11, "high": 217.75, "low": 212.61, "volume": 83200291, "mkt_cap": "5.206T",
            "pe": 27.3, "div_yield": 0.46, "wk52_high": 243.37, "wk52_low": 164.27}
}
```

- **Times** are unix seconds. Percentages are plain numbers: `0.46` means 0.46%.
- **`state`** is `open`, `pre`, `post` or `closed`. `extended` holds the pre-market or after-hours price, or is `null`.
- **`points`** are `[time, close]` pairs in ascending order, at most 400 of them. 1D and 5D cover only the regular session.
- **`session`** is the 1D chart's time span, so a day in progress draws only partway. It is `null` for other ranges.
- **Stats** a security doesn't have are `null`.
- **Errors:** an unknown symbol returns 404, a bad symbol or range 400, and a failed source 502, each as `{"error": "..."}`.

Quotes are cached for a minute. Series are cached for a minute (1D) up to an hour (long ranges).

### Answers

The page asks for an answer only when the search reads as a question: it starts with who, what, how, is, can and the like, ends in "?", compares ("vs", "difference between"), or asks for a fact ("population", "boiling point", "half-life"). It sends the question and its top five results:

```
POST /answer   {"q": "how tall is everest", "results": [{"url": "...", "title": "...", "content": "..."}, ...]}
```

The reply is plain text, streamed as it is written: one to three sentences ending in citations like `[1]` or `[2][3]`, numbered in the order the results were sent. An empty reply means the results don't answer the question. 503 means no model is set up or it can't be reached.

To be quick, the server:

- fetches the result pages in parallel, reading at most 700 KB of each, and gives up on slow ones after a second, when their snippets stand in for them (a slow host waits up to two seconds for the first page);
- sends the model only the passages that share the question's words, plus each page's opening, about 1,400 tokens in all;
- clears the model's cache of the last long prompt while the pages download;
- loads the model while a question is still being typed (`POST /answer/warm`).

To stay accurate, the model answers only from those passages, each labelled with its page's date, and gives one answer instead of a tour of every source's figure. It first names how its sources relate: the same fact at different precision, older and newer figures, facts that fit together, or differing views. The server drops that line. The labels follow Google Research's study of conflicting sources in retrieval ([DRAGged into Conflicts](https://arxiv.org/abs/2506.08500), 2025). On a fixed set of 18 questions, that label plus a one-line reminder at the end of the prompt cut answers that list each source's figure from a third to one in twenty.

#### Setting up the model

Answers need an [Ollama](https://ollama.com) server with an instruction model. `gemma4:26b-a4b-it-qat`, the default, is the one these numbers come from. It needs about 15 GB of GPU memory, and on a 20 GB RTX A4500 the first words show 1 to 1.5 seconds after the results. A smaller model fits a smaller GPU and answers sooner, but less carefully.

To run it on the same machine (an NVIDIA GPU and the [NVIDIA Container Toolkit](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/latest/install-guide.html)):

1. In `.env`, set `OLLAMA_URL=http://ollama:11434` and, if you like, another `OLLAMA_MODEL`.
2. Start everything with the `answers` profile:

   ```sh
   docker compose --profile answers up -d
   ```

   The first start downloads the model, which takes a while; answers begin once it is in. The `ollama` service keeps prompts within 4K tokens of context, runs one request at a time so each answer gets the whole GPU, and keeps the model loaded for 30 minutes after the last question.

To use an Ollama server elsewhere, set `OLLAMA_URL` to it instead and leave the profile off. Its context length should be at least 4K, and Avogadro sends no load options, so it never makes the server reload a model another program is using.

If a batch job shares the model, set `ANSWER_HOLD_URL` to an endpoint of that job. It is sent `{"seconds": 15}` when an answer starts and `{"seconds": 0}` when it ends, so the job can step aside.

## Setup

1. Create `.env` from the example and fill it in:

   ```sh
   cp .env.example .env
   openssl rand -hex 32   # paste as SEARXNG_SECRET
   ```

   Set `SEARXNG_BASE_URL` to the address you'll reach the page at, and optionally `AVOGADRO_PORT` and the [answer](#answers) settings.

2. Start it:

   ```sh
   docker compose up -d
   ```

3. The page is published on `127.0.0.1:${AVOGADRO_PORT}` (default 8888). Put a reverse proxy in front of it, or serve it on a Tailscale tailnet:

   ```sh
   tailscale serve --bg http://127.0.0.1:8888
   ```

4. To make it your browser's search engine, use `https://your-address/?q=%s`.

To serve the same instance from several hosts, give every host the same `.env`; result image links are signed with `SEARXNG_SECRET`.

## Configuration

`config/settings.yml` holds only overrides on top of SearXNG's defaults. Most notably, it sets the engine list. Some engines block SearXNG from some networks, so check which ones answer from yours and turn the rest off. Restart after editing:

```sh
docker compose restart
```

## Project layout

```
config/settings.yml    SearXNG overrides (engines, plugin, favicon resolver)
plugins/avogadro.py    SearXNG plugin: signed image URLs, /favicon/<host>; loads the two below
plugins/avogadro_stock.py   /stock: quotes and price series
plugins/avogadro_answer.py  /answer: cited answers to questions from a local model
frontend/              the page: index.html, app.css, app.js, cloud.js, fonts
android/               the Android app (Kotlin, Compose); build.sh builds and installs it
logo/                  the mark: orbital.html draws it, render.sh writes the web and Android icons
Caddyfile              static files, the SearXNG routes, security headers
docker-compose.yml     the two containers, plus an optional Ollama for answers
LICENSE                AGPL-3.0
```

## License

Avogadro is free software under the GNU Affero General Public License, version 3 or (at your option) any later version; see [LICENSE](LICENSE). If you run a modified version as a service for others, the AGPL asks you to offer them its source.

The bundled Hanken Grotesk font keeps its own license, the SIL Open Font License 1.1; see `frontend/fonts/OFL.txt`.

## Credits

- [SearXNG](https://github.com/searxng/searxng) does the searching (AGPL-3.0).
- [Hanken Grotesk](https://github.com/marcologous/hanken-grotesk) is the typeface.
