#!/usr/bin/env bash
# Renders the Avogadro mark (logo/orbital.html) into the web and Android icon files, using
# headless Google Chrome on the build host android/build.sh uses (AVOGADRO_BUILDER in ../.env).
#
#   logo/render.sh            # rewrites frontend/favicon.svg, frontend/apple-touch-icon.png
#                             # and the launcher, themed and widget icons under android/
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
builder="${AVOGADRO_BUILDER:-$(sed -n 's/^AVOGADRO_BUILDER=//p' "$here/../.env" 2>/dev/null | tail -n 1)}"
[ -n "$builder" ] || { echo "AVOGADRO_BUILDER is not set (environment or $here/../.env)" >&2; exit 1; }
ssh_opts=(-o BatchMode=yes -o StrictHostKeyChecking=yes)

# The page goes over stdin into a temporary directory; Chrome prints the finished DOM.
ssh "${ssh_opts[@]}" "$builder" \
  'd=$(mktemp -d) && cat > "$d/orbital.html" &&
   google-chrome --headless=new --disable-gpu --virtual-time-budget=20000 --dump-dom "file://$d/orbital.html" 2>/dev/null;
   rm -rf "$d"' < "$here/orbital.html" |
python3 -c '
import base64, html, json, re, sys
from pathlib import Path
root = Path(sys.argv[1])
dom = sys.stdin.read()
m = re.search(r"<pre id=\"out\">(.*?)</pre>", dom, re.S)
if not m or not m.group(1).strip():
    sys.exit("orbital.html produced no files")
for path, url in json.loads(html.unescape(m.group(1))).items():
    out = root / path
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(base64.b64decode(url.split(",", 1)[1]))
    print(f"{out.stat().st_size:>8}  {path}")
' "$here/.."
