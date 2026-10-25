"""Builds media/spotyy-icons.woff and the package.json `contributes.icons` entries.

Requires fontTools:  python3 -m pip install fonttools   (then: npm run icons)

Static icons (logo, prev, next, volume, mute) are monochrome glyphs from the JetBrains plugin's
SVGs (scripts/icons/), so VS Code tints them with the theme colour like any other icon.

The play/pause button reproduces the JetBrains CircularButtonUI as an OpenType *colour* glyph
(COLR/CPAL layers) — one glyph per 1/32 of track progress, per state and per theme:

    track ring (grey)  →  progress arc (green; translucent while paused)
    →  face backdrop   →  light/dark disc  →  play/pause symbol

Colours are sampled from the JetBrains widget as rendered (dark theme) and taken from
MyStatusBarWidget.kt for the light theme.
"""
import json
import math
import re
from pathlib import Path

from fontTools.colorLib.builder import buildCOLR, buildCPAL
from fontTools.fontBuilder import FontBuilder
from fontTools.misc.transform import Transform
from fontTools.pens.t2CharStringPen import T2CharStringPen
from fontTools.pens.transformPen import TransformPen
from fontTools.svgLib.path import parse_path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / 'scripts' / 'icons'
STEPS = 32
UPM, ASCENT, DESCENT = 1000, 850, -150   # glyph box: SVG 0..1000 → font -150..850

# ── Static monochrome icons ──────────────────────────────────────────────────
# transform: SVG source space → 1000×1000 glyph box (SVG y-down)
def around_centre(scale, t):
    """Scale an already-placed glyph about the box centre."""
    return Transform().translate(500, 500).scale(scale).translate(-500, -500).transform(t)


# Arrows and volume are enlarged to match their size in the JetBrains status bar.
STATIC = {
    'logo':   (0xE900, 'Spotyy logo',    Transform(1.4187, 0, 0, 1.4187, 135, 90)),                       # 514×578
    'prev':   (0xE901, 'Previous track', around_centre(1.45, Transform(1.0417, 0, 0, 1.0417, 0, 1000))),  # 0 -960 960 960
    'next':   (0xE902, 'Next track',     around_centre(1.45, Transform(1.0417, 0, 0, 1.0417, 0, 1000))),
    'volume': (0xE905, 'Volume',         around_centre(1.15, Transform(33.333, 0, 0, 33.333, 100, 100))),  # 24×24
    'mute':   (0xE906, 'Muted',          around_centre(1.15, Transform(33.333, 0, 0, 33.333, 100, 100))),
}

# ── Play/pause button geometry (SVG space, centre 500,500, radius 500) ───────
C = 500
# Proportions measured from the JetBrains button as rendered (~27 px across).
RING = (385, 500)        # track + progress band (~3 px: reads as bold as JetBrains' 2 px stroke at 1x)
BACKDROP_R = 385         # faceBg disc behind everything (shows as the thin dark gap)
DISC_R = 355             # the play/pause icon's circle
RING_SCALE = 1.22        # button diameter in ems: ~19.5 px at the status bar's 16 px icon size,
                         # inside its 22 px height so no side is clipped
RING_LIFT = 0            # SVG units to move the button up so it centres on the bar (set from measurement)

# CPAL palettes: index → RGBA
PALETTE = {
    'dark': {   # sampled from the JetBrains widget on a dark theme
        'track': (78, 78, 82, 255), 'arc': (30, 215, 96, 255), 'arcPaused': (30, 215, 96, 110),
        'backdrop': (58, 58, 58, 255), 'disc': (227, 227, 227, 255), 'symbol': (58, 58, 58, 255),
    },
    'light': {  # MyStatusBarWidget.kt light-theme colours
        'track': (185, 185, 192, 255), 'arc': (18, 168, 74, 255), 'arcPaused': (18, 168, 74, 130),
        'backdrop': (210, 210, 215, 255), 'disc': (40, 40, 44, 255), 'symbol': (210, 210, 215, 255),
    },
}
COLOR_KEYS = list(PALETTE['dark'])


def circle(r, cw=True):
    s = 1 if cw else 0
    return f'M{C - r} {C}A{r} {r} 0 1 {s} {C + r} {C}A{r} {r} 0 1 {s} {C - r} {C}Z'


def sector(ri, ro, deg):
    if deg >= 360:
        return circle(ro) + circle(ri, False)
    a0, a1 = -90, -90 + deg
    large = 1 if deg > 180 else 0
    p = lambda r, a: (C + r * math.cos(math.radians(a)), C + r * math.sin(math.radians(a)))
    (x0, y0), (x1, y1), (x2, y2), (x3, y3) = p(ro, a0), p(ro, a1), p(ri, a1), p(ri, a0)
    return (f'M{x0:.2f} {y0:.2f}A{ro} {ro} 0 {large} 1 {x1:.2f} {y1:.2f}'
            f'L{x2:.2f} {y2:.2f}A{ri} {ri} 0 {large} 0 {x3:.2f} {y3:.2f}Z')


def poly(pts, k):
    return 'M' + 'L'.join(f'{C + x * k:.2f} {C + y * k:.2f}' for x, y in pts) + 'Z'


# Play/pause symbols from the JetBrains SVGs (Material, circle radius 400), scaled to DISC_R
K = DISC_R / 400
PLAY = poly([(-90, -180), (-90, 180), (190, 0)], K)
PAUSE = poly([(-120, -160), (-120, 160), (-40, 160), (-40, -160)], K) + \
        poly([(40, -160), (40, 160), (120, 160), (120, -160)], K)

RING_T = Transform().translate(C, C - RING_LIFT).scale(RING_SCALE).translate(-C, -C)


def to_font(t):
    """SVG y-down 0..1000 box → font units (y-up, baseline at DESCENT)."""
    return Transform(1, 0, 0, -1, 0, ASCENT).transform(t)


def charstring(paths, t):
    pen = T2CharStringPen(UPM, None)
    tp = TransformPen(pen, to_font(t))
    for d in paths:
        parse_path(d, tp)
    return pen.getCharString()


def main():
    glyphs, cmap, icons, layers_by_base = {'.notdef': None}, {}, {}, {}

    for name, (cp, label, t) in STATIC.items():
        svg = (SRC / f'{name}.svg').read_text()
        glyphs[name] = charstring(re.findall(r'<path[^>]*\sd="([^"]+)"', svg), t)
        cmap[cp] = name
        icons[f'spotyy-{name}'] = (cp, label)

    # Shared layer glyphs (not in cmap)
    glyphs['L_track'] = charstring([circle(RING[1]) + circle(RING[0], False)], RING_T)
    glyphs['L_backdrop'] = charstring([circle(BACKDROP_R)], RING_T)
    glyphs['L_disc'] = charstring([circle(DISC_R)], RING_T)
    glyphs['L_play'] = charstring([PLAY], RING_T)
    glyphs['L_pause'] = charstring([PAUSE], RING_T)
    for i in range(1, STEPS + 1):
        glyphs[f'L_arc{i:02d}'] = charstring([sector(RING[0], RING[1], 360 * i / STEPS)], RING_T)

    # Colour base glyphs: <state>-<theme>-NN.  'pause' shows while playing (solid arc),
    # 'play' while paused (translucent arc) — as in CircularButtonUI.
    for ti, theme in enumerate(PALETTE):
        for si, state in enumerate(('play', 'pause')):
            for i in range(STEPS + 1):
                name = f'{state}-{theme}-{i:02d}'
                cp = 0xE910 + (ti * 2 + si) * 0x30 + i
                glyphs[name] = charstring([], Transform())  # empty outline; COLR draws it
                cmap[cp] = name
                pi = lambda key: COLOR_KEYS.index(key)
                stack = [('L_track', pi('track'))]
                if i > 0:
                    stack.append((f'L_arc{i:02d}', pi('arc' if state == 'pause' else 'arcPaused')))
                stack += [('L_backdrop', pi('backdrop')), ('L_disc', pi('disc')),
                          (f'L_{state}', pi('symbol'))]
                layers_by_base[name] = stack
                icons[f'spotyy-{name}'] = (cp, f"{'Play' if state == 'play' else 'Pause'} button, "
                                               f'{theme} theme (progress {i}/{STEPS})')

    order = list(glyphs)
    fb = FontBuilder(UPM, isTTF=False)
    fb.setupGlyphOrder(order)
    fb.setupCharacterMap(cmap)
    notdef = T2CharStringPen(UPM, None).getCharString()
    fb.setupCFF('SpotyyIcons', {'FullName': 'Spotyy Icons'},
                {n: (g if g is not None else notdef) for n, g in glyphs.items()}, {})
    fb.setupHorizontalMetrics({n: (UPM, 0) for n in order})
    fb.setupHorizontalHeader(ascent=ASCENT, descent=DESCENT)
    fb.setupNameTable({'familyName': 'Spotyy Icons', 'styleName': 'Regular'})
    fb.setupOS2(sTypoAscender=ASCENT, sTypoDescender=DESCENT, usWinAscent=ASCENT, usWinDescent=-DESCENT)
    fb.setupPost()
    fb.font['CPAL'] = buildCPAL([[tuple(c / 255 for c in PALETTE[t][k]) for t in PALETTE for k in COLOR_KEYS]])
    # Single palette: dark colours at 0..5, light at 6..11 — remap light layers accordingly
    for name, stack in layers_by_base.items():
        if '-light-' in name:
            layers_by_base[name] = [(g, idx + len(COLOR_KEYS)) for g, idx in stack]
    fb.font['COLR'] = buildCOLR(layers_by_base, version=0)
    fb.font.flavor = 'woff'
    fb.save(ROOT / 'media' / 'spotyy-icons.woff')

    pkg_path = ROOT / 'package.json'
    pkg = json.loads(pkg_path.read_text())
    pkg['contributes']['icons'] = {
        name: {'description': label,
               'default': {'fontPath': './media/spotyy-icons.woff', 'fontCharacter': f'\\{cp:X}'}}
        for name, (cp, label) in icons.items()
    }
    pkg_path.write_text(json.dumps(pkg, indent=2, ensure_ascii=False) + '\n')
    print(f'{len(order)} glyphs, {len(icons)} icons → media/spotyy-icons.woff')


if __name__ == '__main__':
    main()
