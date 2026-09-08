#!/usr/bin/env python3
"""Compose the Play Store screenshots from raw device captures.

Play wants screenshots at a 16:9 or 9:16 aspect ratio; an Android device is
neither (1080x2400 is 9:20, and the Galaxy Tab A9+ is 16:10). Rather than crop a
screen down to fit a ratio and lose the part of the UI the shot exists to show,
each capture is placed whole on a canvas of the required ratio, on the app's own
ground, under a caption saying what is being looked at.

The captures in `store/captures/` come off a running build — the *minified
release* variant — not from a mockup. The tablet frames are from the real
Galaxy Tab A9+ this app is built for, over wireless adb, on Android 16; the
phone frames are from an arm64 emulator, there being no phone to hand.
Recapture with:

    adb exec-out screencap -p > store/captures/<name>.png

then re-run this script. Device status bars are cropped off: they carry an
emulator's clock and VPN glyphs, which say nothing about the app.

    python3 store/make_screenshots.py     # write store/graphics/screenshots/*
"""

from __future__ import annotations

import pathlib
import sys

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from make_store_graphics import (  # noqa: E402  (same directory, one palette)
    ACCENT, BG, BG_ALT, RULE, TEXT, TEXT_DIM, mono,
)

REPO = pathlib.Path(__file__).resolve().parent.parent
CAPTURES = REPO / "store" / "captures"
OUT = REPO / "store" / "graphics" / "screenshots"

# Play's phone slot is 9:16, the tablet slots 16:9. Both sides stay inside the
# 320..3840 px the console accepts.
PHONE_CANVAS = (1080, 1920)
TABLET_CANVAS = (1920, 1080)

# System chrome to cut off each capture, in source pixels: the status bar at the
# top, the navigation chrome at the bottom. The two differ because the captures
# come from different places — the phone frames from an emulator with a gesture
# pill, the tablet frames from a real Galaxy Tab A9+ whose One UI taskbar is
# taller and floats over the app. The tablet's bottom crop clears that taskbar
# while leaving the app's own navigation bar, which sits above it, intact.
PHONE_CHROME = (96, 60)
TABLET_CHROME = (56, 78)

# (capture file, caption). The order is the order Play shows them, so the first
# one has to carry the product on its own.
PHONE_SHOTS = [
    ("phone-4-boost.png", "Drag the boost curve, per map slot"),
    ("phone-2-preflight.png", "It checks the bin before it lets you edit"),
    ("phone-3-tables.png", "Every table, in physical units"),
    ("phone-1-import.png", "Your bin and XDF stay on the device"),
]
TABLET_SHOTS = [
    ("tablet-4-boost.png", "Drag the boost curve, per map slot"),
    ("tablet-6-build.png", "Verified build: checksums, readback, byte audit"),
    ("tablet-5-changes.png", "Every edit journaled, with the reason you gave"),
    ("tablet-3-tables.png", "Every table, in physical units"),
    ("tablet-2-preflight.png", "It checks the bin before it lets you edit"),
    ("tablet-1-import.png", "Your bin and XDF stay on the device"),
]


def compose(capture: pathlib.Path, caption: str, canvas: tuple[int, int],
            chrome: tuple[int, int]) -> Image.Image:
    src = Image.open(capture).convert("RGB")
    top, bottom = chrome
    src = src.crop((0, top, src.width, src.height - bottom))

    cw, ch = canvas
    portrait = ch > cw
    cap_h = int(ch * (0.135 if portrait else 0.16))
    margin = int(cw * (0.055 if portrait else 0.035))

    img = Image.new("RGB", canvas, BG)
    draw = ImageDraw.Draw(img)

    # Caption band, separated from the shot by the same hairline the app uses.
    draw.rectangle([0, 0, cw, cap_h], fill=BG_ALT)
    draw.line([(0, cap_h), (cw, cap_h)], fill=RULE, width=2)

    mark = mono(int(cap_h * 0.24))
    draw.text((margin, cap_h * 0.30), "simos", font=mark, fill=TEXT, anchor="lm")
    w = draw.textbbox((0, 0), "simos", font=mark)[2]
    draw.text((margin + w, cap_h * 0.30), "cal", font=mark, fill=ACCENT, anchor="lm")

    cap_font = mono(int(cap_h * 0.21))
    line = caption
    while draw.textbbox((0, 0), line, font=cap_font)[2] > cw - 2 * margin:
        cap_font = mono(cap_font.size - 2)
    draw.text((margin, cap_h * 0.70), line, font=cap_font, fill=TEXT_DIM, anchor="lm")

    # The shot: scaled to fit what is left, never upscaled past 1:1 blur.
    avail_w = cw - 2 * margin
    avail_h = ch - cap_h - 2 * margin
    scale = min(avail_w / src.width, avail_h / src.height)
    shot = src.resize((int(src.width * scale), int(src.height * scale)), Image.LANCZOS)
    x = (cw - shot.width) // 2
    y = cap_h + margin + (avail_h - shot.height) // 2
    draw.rectangle([x - 2, y - 2, x + shot.width + 1, y + shot.height + 1], outline=RULE)
    img.paste(shot, (x, y))
    return img


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    missing = []
    for kind, shots, canvas, chrome in (
        ("phone", PHONE_SHOTS, PHONE_CANVAS, PHONE_CHROME),
        ("tablet", TABLET_SHOTS, TABLET_CANVAS, TABLET_CHROME),
    ):
        for index, (name, caption) in enumerate(shots, start=1):
            source = CAPTURES / name
            if not source.exists():
                missing.append(str(source.relative_to(REPO)))
                continue
            out = OUT / f"{kind}-{index:02d}.png"
            compose(source, caption, canvas, chrome).save(out)
            print(f"wrote {out.relative_to(REPO)}  {canvas[0]}x{canvas[1]}  <- {name}")
    if missing:
        sys.exit("Missing captures:\n  " + "\n  ".join(missing))


if __name__ == "__main__":
    main()
