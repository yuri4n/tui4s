# The glyph outlines of the logo, read from a TrueType font.
# claude 2026-10-20: created (ADR 0022): it prints the constants FONT and GLYPHS of scripts/logo/build.mjs
#
# The logo (assets/logo/tui4s.txt) uses three characters: "/", "\" and "_".
# This script reads their outlines and the metrics of the font with fontTools,
# and prints the two constants of scripts/logo/build.mjs. Each outline must be
# a polygon (straight segments only). Run it only when the font changes, then
# paste its output into build.mjs and run the generator.
#
#     pip install --user fonttools
#     python3 scripts/logo/glyphs.py /usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf
#
# The default font is DejaVu Sans Mono (Bitstream Vera licence: the outlines
# can be embedded and used in derived documents).

import json
import sys

from fontTools.pens.recordingPen import RecordingPen
from fontTools.ttLib import TTFont

CHARS = ["/", "\\", "_"]


def polygon(glyph_set, name):
    pen = RecordingPen()
    glyph_set[name].draw(pen)
    points = []
    for op, args in pen.value:
        if op in ("moveTo", "lineTo"):
            points.append([round(args[0][0]), round(args[0][1])])
        elif op != "closePath":
            sys.exit(f"glyphs: {name} has a {op} segment; only polygons are supported")
    return points


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"
    font = TTFont(path)
    glyph_set = font.getGlyphSet()
    cmap = font.getBestCmap()
    advance = {glyph_set[cmap[ord(c)]].width for c in CHARS}
    if len(advance) != 1:
        sys.exit(f"glyphs: the font is not monospaced for {CHARS}")
    hhea = font["hhea"]
    meta = {
        "name": f"{font['name'].getDebugName(4)} {font['name'].getDebugName(5)}",
        "upem": font["head"].unitsPerEm,
        "advance": advance.pop(),
        "ascent": hhea.ascent,
        "descent": -hhea.descent,
    }
    print(f"const FONT = {json.dumps(meta)};")
    glyphs = {c: polygon(glyph_set, cmap[ord(c)]) for c in CHARS}
    print(f"const GLYPHS = {json.dumps(glyphs)};")


if __name__ == "__main__":
    main()
