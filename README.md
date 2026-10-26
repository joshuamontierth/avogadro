# Avogadro

A cool, modern front end for [SearXNG](https://github.com/searxng/searxng).

Avogadro replaces SearXNG's stock search page with a clean, quiet one. Behind it, a live hydrogen atom turns slowly. Its electron cloud is drawn from real orbitals and steps through 1s, 2p, 3d, 3s and 4f shapes. A search excites the atom into a new orbital, which gives off a photon as the results come in.

## Features

- **Results:** All, Images, News, Videos, IT and Science tabs, a time filter, Wikipedia infoboxes, instant answers, "did you mean" and related searches.
- **Images:** a justified grid with a viewer you can step through with the arrow keys.
- **Keyboard:** `/` focuses the search box; `j` and `k` move through results, and `Enter` opens one.
- **Autocomplete,** plus OpenSearch, so browsers can offer it as a search engine.
- **Light and dark** themes that follow your system, and a layout built for phones as well as desktops.
- **Reduced motion:** with that setting on, the atom becomes a still picture.

## Privacy

The page only ever talks to your own server:

- A strict Content Security Policy blocks every third-party request.
- Result images go through SearXNG's own image proxy.
- Favicons come from SearXNG's cache.
- The font is self-hosted.
- Links don't send your search to the sites you open (`Referrer-Policy: no-referrer`).

## How it works

Avogadro uses two containers:

- **`searxng`:** the stock SearXNG image, with no published port. It loads `config/settings.yml` and the small `plugins/avogadro.py` plugin. The plugin signs result images for SearXNG's `/image` proxy and adds a `/favicon/<host>` route.
- **`avogadro`:** Caddy. It serves `frontend/`, plain HTML, CSS and JavaScript with no build step. It passes only four routes through to SearXNG: `/search?format=json`, `/autocompleter`, `/image` and `/favicon/*`.

The atom is drawn in `frontend/cloud.js`. Each dot is a sample from the orbital's probability density |ψ|². The two colours are the two signs of the wavefunction.

## Setup

1. Create `.env` from the example and fill it in:

   ```sh
   cp .env.example .env
   openssl rand -hex 32   # paste as SEARXNG_SECRET
   ```

   Set `SEARXNG_BASE_URL` to the address you'll reach the page at, and optionally `AVOGADRO_PORT`.

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
plugins/avogadro.py    SearXNG plugin: signed image URLs, /favicon/<host>
frontend/              the page: index.html, app.css, app.js, cloud.js, fonts
android/               the Android app (Kotlin, Compose); build.sh builds and installs it
logo/                  the mark: orbital.html draws it, render.sh writes the web and Android icons
Caddyfile              static files, the SearXNG routes, security headers
docker-compose.yml     the two containers
LICENSE                AGPL-3.0
```

## License

Avogadro is free software under the GNU Affero General Public License, version 3 or (at your option) any later version; see [LICENSE](LICENSE). If you run a modified version as a service for others, the AGPL asks you to offer them its source.

The bundled Hanken Grotesk font keeps its own license, the SIL Open Font License 1.1; see `frontend/fonts/OFL.txt`.

## Credits

- [SearXNG](https://github.com/searxng/searxng) does the searching (AGPL-3.0).
- [Hanken Grotesk](https://github.com/marcologous/hanken-grotesk) is the typeface.
