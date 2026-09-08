#!/usr/bin/env python3
"""Draw the Play Store icon, the feature graphic, and the launcher mipmaps.

One script, one artwork. The store icon and the launcher icon are the *same*
drawing at different sizes rather than two files that happen to look alike, so
they cannot drift apart the way a hand-exported pair does — a listing whose icon
does not match the icon on the device reads as the wrong app.

Everything is painted from `PromoPalette`
(`engine/src/main/java/com/simoscal/android/ui/Theme.kt`), transcribed at the top
of this file. Those colours are also in `res/values/colors.xml` and in the promo
video's own config; if the palette moves, all of them move together.

Run:

    python3 store/make_store_graphics.py            # write store/graphics/*
    python3 store/make_store_graphics.py --mipmaps  # also rewrite res/mipmap-*/

`--mipmaps` overwrites the launcher PNGs in the source tree. It is a separate
flag because it edits committed resources, not just generated store assets.

Requires Pillow and a monospace TrueType face. It fails loudly rather than
substituting a default bitmap font: the wordmark IS the brand, and silently
drawing it in something else would ship a wrong logo.
"""

from __future__ import annotations

import argparse
import math
import pathlib
import sys

from PIL import Image, ImageDraw, ImageFont

# ---------------------------------------------------------------- palette

BG = (0x0B, 0x0E, 0x14)
BG_ALT = (0x12, 0x17, 0x20)
RULE = (0x2C, 0x36, 0x46)
RULE_FAINT = (0x1E, 0x26, 0x34)
TEXT = (0xEC, 0xF0, 0xF6)
TEXT_DIM = (0x96, 0xA2, 0xB4)
TEXT_FAINT = (0x5E, 0x69, 0x7A)
ACCENT = (0xFF, 0x8A, 0x2E)
ACCENT2 = (0x56, 0xBE, 0xFF)
GOOD = (0x62, 0xD6, 0x8C)

# Fonts are looked up by path because Pillow has no font-by-name lookup. Menlo is
# the macOS face closest to the Compose `FontFamily.Monospace` the app draws the
# wordmark in; the others are fallbacks for a Linux runner.
MONO_CANDIDATES = [
    ("/System/Library/Fonts/Menlo.ttc", 0),
    ("/System/Library/Fonts/Menlo.ttc", 1),
    ("/System/Library/Fonts/Supplemental/Andale Mono.ttf", 0),
    ("/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf", 0),
]
MONO_BOLD_CANDIDATES = [
    ("/System/Library/Fonts/Menlo.ttc", 1),
    ("/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf", 0),
] + MONO_CANDIDATES

REPO = pathlib.Path(__file__).resolve().parent.parent
OUT = REPO / "store" / "graphics"
RES = REPO / "engine" / "src" / "main" / "res"

# Legacy launcher densities, in px. minSdk is 26, so the adaptive icon below is
# what nearly every launcher actually draws; these stay for the few surfaces
# (some notification and settings paths) that still read the square.
MIPMAPS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

# An adaptive icon is 108dp with only the centre 72dp guaranteed visible and the
# centre 66dp guaranteed unmasked. The artwork is drawn into that 66/108 square,
# on the same ground as the background layer, so a circular mask crops padding
# rather than the mark.
ADAPTIVE_DP = 108
ADAPTIVE_SAFE_DP = 66
ADAPTIVE_DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def _font(candidates: list[tuple[str, int]], size: int) -> ImageFont.FreeTypeFont:
    for path, index in candidates:
        try:
            return ImageFont.truetype(path, size, index=index)
        except OSError:
            continue
    sys.exit(
        "No monospace TrueType face found. Tried:\n  "
        + "\n  ".join(f"{p} (index {i})" for p, i in candidates)
        + "\nInstall one, or add its path to MONO_CANDIDATES."
    )


def mono(size: int) -> ImageFont.FreeTypeFont:
    return _font(MONO_CANDIDATES, size)


def mono_bold(size: int) -> ImageFont.FreeTypeFont:
    return _font(MONO_BOLD_CANDIDATES, size)


def _text_width(draw: ImageDraw.ImageDraw, s: str, font) -> int:
    box = draw.textbbox((0, 0), s, font=font)
    return box[2] - box[0]


# ------------------------------------------------------------------ wordmark


def draw_wordmark(draw: ImageDraw.ImageDraw, cx: float, cy: float, size: int) -> None:
    """`simos` in text, `cal` in accent — split exactly where the app splits it.

    Drawn as two runs measured against each other rather than one centred string,
    because the halves carry different colours and must still sit as one word.
    """
    font = mono(size)
    left, right = "simos", "cal"
    w_left = _text_width(draw, left, font)
    w_right = _text_width(draw, right, font)
    x = cx - (w_left + w_right) / 2
    draw.text((x, cy), left, font=font, fill=TEXT, anchor="lm")
    draw.text((x + w_left, cy), right, font=font, fill=ACCENT, anchor="lm")


# --------------------------------------------------------------------- gauge


def draw_gauge(draw: ImageDraw.ImageDraw, cx: float, cy: float, r: float,
               reading_psi: float = 22.5, psi_max: float = 35.0) -> None:
    """A boost gauge, swept 135°..405° — bottom-left, over the top, bottom-right.

    Pillow measures angles from 3 o'clock and increases them clockwise on a
    y-down canvas, so 135° is the lower left and 405° (= 45°) the lower right.
    The 90° the sweep leaves open sits at the bottom, which is where the unit
    label goes.

    The needle sits at `reading_psi` — around the top of the pull, which is the
    part of the map this app exists to edit. It is deliberately between two
    labelled ticks: a needle parked on a number hides it.
    """
    start, end = 135.0, 405.0
    width = max(2, int(r * 0.055))

    draw.arc([cx - r, cy - r, cx + r, cy + r], start, end, fill=TEXT, width=width)

    tick_font = mono(max(7, int(r * 0.20)))
    for psi in range(0, int(psi_max) + 1, 5):
        frac = psi / psi_max
        ang = math.radians(start + frac * (end - start))
        ca, sa = math.cos(ang), math.sin(ang)
        outer = r - width * 0.6
        inner = outer - r * 0.14
        draw.line([(cx + ca * inner, cy + sa * inner), (cx + ca * outer, cy + sa * outer)],
                  fill=TEXT, width=max(1, int(r * 0.035)))
        label_r = inner - r * 0.19
        draw.text((cx + ca * label_r, cy + sa * label_r), str(psi),
                  font=tick_font, fill=TEXT, anchor="mm")

    unit_font = mono(max(6, int(r * 0.17)))
    draw.text((cx, cy + r * 0.74), "PSI", font=unit_font, fill=TEXT_DIM, anchor="mm")

    ang = math.radians(start + (reading_psi / psi_max) * (end - start))
    ca, sa = math.cos(ang), math.sin(ang)
    tip = r * 0.72
    draw.line([(cx, cy), (cx + ca * tip, cy + sa * tip)],
              fill=ACCENT, width=max(3, int(r * 0.11)))
    hub = max(3, int(r * 0.13))
    draw.ellipse([cx - hub, cy - hub, cx + hub, cy + hub], fill=ACCENT)


# ---------------------------------------------------------------- the artwork


def draw_artwork(draw: ImageDraw.ImageDraw, x: float, y: float, size: float) -> None:
    """The mark itself, drawn into a `size`-square box at (x, y).

    Wordmark over gauge, the composition the app has shipped since the first
    launcher icon. Taking a box rather than an image is what lets the store icon
    and the adaptive foreground be the same drawing at two scales.
    """
    draw_wordmark(draw, x + size / 2, y + size * 0.155, int(size * 0.170))
    draw_gauge(draw, x + size / 2, y + size * 0.615, size * 0.345)


def render_icon(px: int) -> Image.Image:
    """Full-bleed square icon: the store's 512 and the legacy mipmaps."""
    img = Image.new("RGB", (px, px), BG)
    draw = ImageDraw.Draw(img)
    pad = px * 0.06
    draw_artwork(draw, pad, pad, px - 2 * pad)
    return img


def render_adaptive_foreground(px: int) -> Image.Image:
    """The same artwork, inset to the adaptive icon's guaranteed-visible square."""
    img = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    safe = px * ADAPTIVE_SAFE_DP / ADAPTIVE_DP
    draw_artwork(draw, (px - safe) / 2, (px - safe) / 2, safe)
    return img


# ---------------------------------------------------------- feature graphic


def render_feature_graphic(w: int = 1024, h: int = 500) -> Image.Image:
    """1024x500: wordmark and promise on the left, a boost curve on the right.

    The curve is the app's hero screen in miniature — a per-slot target line over
    a hairline grid — so the graphic shows the product rather than decorating it.
    """
    img = Image.new("RGB", (w, h), BG)
    draw = ImageDraw.Draw(img)

    plot = (int(w * 0.52), int(h * 0.17), int(w * 0.95), int(h * 0.80))
    x0, y0, x1, y1 = plot
    draw.rectangle(plot, fill=BG_ALT, outline=RULE)
    for i in range(1, 6):
        gx = x0 + (x1 - x0) * i / 6
        draw.line([(gx, y0), (gx, y1)], fill=RULE_FAINT)
    for i in range(1, 4):
        gy = y0 + (y1 - y0) * i / 4
        draw.line([(x0, gy), (x1, gy)], fill=RULE_FAINT)

    def curve(peak: float, tail: float, colour, width: int):
        pts = []
        n = 60
        for i in range(n + 1):
            t = i / n
            # Spool to `peak` by a quarter of the sweep, then bleed to `tail`.
            if t < 0.25:
                v = peak * (t / 0.25) ** 0.55
            else:
                v = peak + (tail - peak) * ((t - 0.25) / 0.75) ** 1.25
            pts.append((x0 + (x1 - x0) * t, y1 - (y1 - y0) * (v / 32.0)))
        draw.line(pts, fill=colour, width=width, joint="curve")

    curve(23.0, 17.0, TEXT_FAINT, 2)       # a quieter slot, for context
    curve(26.5, 19.5, ACCENT, 4)           # the slot being edited
    for t in (0.10, 0.25, 0.45, 0.70, 1.0):
        v = 26.5 + (19.5 - 26.5) * (max(t - 0.25, 0) / 0.75) ** 1.25 if t >= 0.25 \
            else 26.5 * (t / 0.25) ** 0.55
        px_, py_ = x0 + (x1 - x0) * t, y1 - (y1 - y0) * (v / 32.0)
        draw.ellipse([px_ - 5, py_ - 5, px_ + 5, py_ + 5], fill=BG, outline=ACCENT, width=3)

    draw.text((x0 + 10, y0 + 8), "BOOST TARGET  psi", font=mono(15), fill=TEXT_FAINT)
    draw.text((x1 - 10, y1 - 20), "rpm", font=mono(15), fill=TEXT_FAINT, anchor="rm")

    draw_wordmark(draw, w * 0.26, h * 0.36, 60)
    draw.text((w * 0.26, h * 0.54), "Edit your Simos18 calibration",
              font=mono(21), fill=TEXT_DIM, anchor="mm")
    draw.text((w * 0.26, h * 0.645), "in physical units, on the tablet.",
              font=mono(21), fill=TEXT_DIM, anchor="mm")
    draw.text((w * 0.26, h * 0.79), "CHECKSUM-VERIFIED  ·  OFFLINE  ·  NO PERMISSIONS",
              font=mono(13), fill=ACCENT2, anchor="mm")
    return img


# ------------------------------------------------------------------- driver


def write_mipmaps() -> list[pathlib.Path]:
    written = []
    for density, px in MIPMAPS.items():
        d = RES / f"mipmap-{density}"
        d.mkdir(parents=True, exist_ok=True)
        icon = render_icon(px)
        for name in ("ic_launcher.png", "ic_launcher_round.png"):
            icon.save(d / name)
            written.append(d / name)

        fg_px = int(ADAPTIVE_DP * ADAPTIVE_DENSITIES[density])
        fg = render_adaptive_foreground(fg_px)
        fg.save(d / "ic_launcher_foreground.png")
        written.append(d / "ic_launcher_foreground.png")

    anydpi = RES / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    xml = (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!--\n"
        # XML comments cannot contain a double hyphen, so the flag is named
        # without its dashes here rather than quoted verbatim.
        "  Generated by store/make_store_graphics.py, mipmaps mode. The foreground is\n"
        "  the same artwork as the 512x512 Play icon, inset to the adaptive icon's\n"
        "  guaranteed-visible square; the background is the app's own ground, so a\n"
        "  round or squircle mask crops padding rather than the mark.\n"
        "\n"
        "  No monochrome layer, deliberately. A themed-icon launcher tints that\n"
        "  layer flat and drops it on a wallpaper-derived ground, which turns this\n"
        "  artwork into an unreadable disc; a real monochrome layer needs a\n"
        "  purpose-drawn silhouette, and the wordmark does not reduce to one.\n"
        "  Without the element, those launchers fall back to the adaptive icon.\n"
        "-->\n"
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/promo_bg" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        "</adaptive-icon>\n"
    )
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        (anydpi / name).write_text(xml)
        written.append(anydpi / name)
    return written


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--mipmaps", action="store_true",
                    help="also rewrite the launcher icons in engine/src/main/res")
    args = ap.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    icon = OUT / "play-icon-512.png"
    render_icon(512).save(icon)
    feature = OUT / "play-feature-graphic-1024x500.png"
    render_feature_graphic().save(feature)
    print(f"wrote {icon.relative_to(REPO)}  512x512")
    print(f"wrote {feature.relative_to(REPO)}  1024x500")

    if args.mipmaps:
        for path in write_mipmaps():
            print(f"wrote {path.relative_to(REPO)}")


if __name__ == "__main__":
    main()
