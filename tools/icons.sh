#!/bin/sh
# App-menu tile pictures in the stock style, as HMG-API's tools/mkicon.sh makes them
# (99x99, the shape centred at 58 px, the stock gradient day and night, the flat grey
# "disabled" look), drawn with rsvg-convert instead of ImageMagick:
#   tools/icons.sh            -> mod/icons/*.png and app/res/drawable/ic_launcher.png
# The gradient: the launcher's corner colours (cyan top left to purple bottom right),
# blended across the shape's box (top edge over bottom edge through a vertical mask).
set -e
cd "$(dirname "$0")/.."
command -v rsvg-convert >/dev/null || { echo "icons: needs rsvg-convert (librsvg)"; exit 1; }
GLYPH=art/glyph.svg
OUT=mod/icons
mkdir -p "$OUT" app/res/drawable
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
# the glyph's inner markup (between <g ...> and </g>), placed in the 58 px box
python3 -I - "$GLYPH" > "$W/glyph.frag" <<'PY'
import re, sys
t = open(sys.argv[1]).read()
m = re.search(r"<g[^>]*>(.*?)</g>", t, re.S)
print(re.sub(r'fill="[^"]*"', "", m.group(1)))
PY
tile() {   # <out> <top left> <top right> <bottom left> <bottom right>
    cat > "$W/t.svg" <<EOF
<svg xmlns="http://www.w3.org/2000/svg" width="99" height="99" viewBox="0 0 99 99">
  <defs>
    <linearGradient id="top" x1="20.5" y1="0" x2="78.5" y2="0" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="$2"/><stop offset="1" stop-color="$3"/></linearGradient>
    <linearGradient id="bottom" x1="20.5" y1="0" x2="78.5" y2="0" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="$4"/><stop offset="1" stop-color="$5"/></linearGradient>
    <linearGradient id="fade" x1="0" y1="20.5" x2="0" y2="78.5" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#fff"/><stop offset="1" stop-color="#000"/></linearGradient>
    <mask id="v"><rect width="99" height="99" fill="url(#fade)"/></mask>
    <clipPath id="shape" transform="translate(20.5 20.5) scale(0.58)">$(cat "$W/glyph.frag")</clipPath>
  </defs>
  <g clip-path="url(#shape)">
    <rect x="20.5" y="20.5" width="58" height="58" fill="url(#bottom)"/>
    <rect x="20.5" y="20.5" width="58" height="58" fill="url(#top)" mask="url(#v)"/>
  </g>
</svg>
EOF
    rsvg-convert -w 99 -h 99 "$W/t.svg" -o "$OUT/$1"
}
flat() {   # <out> <opacity>
    cat > "$W/f.svg" <<EOF
<svg xmlns="http://www.w3.org/2000/svg" width="99" height="99" viewBox="0 0 99 99">
  <g transform="translate(20.5 20.5) scale(0.58)" fill="#62797f" fill-opacity="$2">$(cat "$W/glyph.frag")</g>
</svg>
EOF
    rsvg-convert -w 99 -h 99 "$W/f.svg" -o "$OUT/$1"
}
tile icon.png '#23e4c5' '#32b6ba' '#758cd7' '#8763cc'
tile icon_night.png '#2ab8aa' '#2d9aa3' '#4c61b4' '#4d3fae'
flat icon_disabled.png 0.2
flat icon_disabled_night.png 0.4
cp "$OUT/icon.png" app/res/drawable/ic_launcher.png
echo "$OUT: icon.png icon_night.png icon_disabled.png icon_disabled_night.png (+ app/res/drawable/ic_launcher.png)"
