#!/usr/bin/env python3
"""Generate the dial and hand images for the analog watch face.

All geometry is measured from docs/reference/original-watchface.png (480x480 capture) and
expressed in that capture's pixel space ("ref px", centre at 240,240). Images are rendered
at PX_PER_REF output pixels per ref pixel, supersampled, so they stay sharp on larger screens.
"""
import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
FONT = ROOT / "assets" / "fonts" / "Roboto-Regular.ttf"
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"

REF = 480            # reference capture size in px
CENTER = REF / 2
PX_PER_REF = 4       # output px per reference px
SS = 4               # supersampling factor, applied on top of PX_PER_REF

# --- measured from the reference capture -------------------------------------------------
DOT_R, DOT_RADIUS = 233.5, 4.3          # hour dots: ring radius, dot radius
TICK_R, TICK_LEN, TICK_W = 233.0, 9.0, 1.3
TICK_COLOR = (200, 200, 200, 255)
DOT_COLOR = (230, 230, 230, 255)
NUMERAL_R = 198.0
NUMERAL_SIZE = 28.0                     # font size in ref px
NUMERAL_WEIGHT = 400
NUMERAL_COLOR = (250, 250, 250, 255)
# Numerals counter-rotate in the lower half and sit upright at 3/6/9/12.
NUMERAL_ROTATION = {12: 0, 1: 30, 2: 60, 3: 0, 4: -60, 5: -30,
                    6: 0, 7: 30, 8: 60, 9: 0, 10: -60, 11: -30}

HOUR_LEN, MINUTE_LEN, HAND_W, INLAY_W = 125.0, 197.0, 17.0, 8.0
HAND_BASE = 10.0                        # extends behind the pivot, hidden under the cap
INLAY_START, INLAY_END_GAP = 30.0, 9.0  # inlay runs from 30 px out to 9 px short of the tip
TIP_CHAMFER = 4.0
HAND_COLOR = (255, 255, 255, 255)
INLAY_COLOR = (224, 224, 224, 255)
SECOND_LEN, SECOND_TAIL, SECOND_W = 210.0, 49.0, 3.5
RED = (226, 43, 7, 255)
CAP_RING_R, CAP_RED_R, CAP_DOT_R = 18.0, 12.0, 3.5

# --- complication-free time used for previews and the reference comparison ---------------
PREVIEW_TIME = (9, 38, 13)


def _canvas(w_ref: float, h_ref: float):
    w, h = round(w_ref * PX_PER_REF * SS), round(h_ref * PX_PER_REF * SS)
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    return img, ImageDraw.Draw(img), PX_PER_REF * SS


def _down(img: Image.Image) -> Image.Image:
    return img.resize((img.width // SS, img.height // SS), Image.LANCZOS)


def render_dial() -> Image.Image:
    img, d, k = _canvas(REF, REF)
    c = CENTER * k
    for i in range(60):
        a = math.radians(i * 6)
        sx, sy = math.sin(a), -math.cos(a)
        if i % 5 == 0:
            r = DOT_R * k
            x, y = c + sx * r, c + sy * r
            rad = DOT_RADIUS * k
            d.ellipse([x - rad, y - rad, x + rad, y + rad], fill=DOT_COLOR)
        else:
            r0, r1 = (TICK_R - TICK_LEN / 2) * k, (TICK_R + TICK_LEN / 2) * k
            d.line([(c + sx * r0, c + sy * r0), (c + sx * r1, c + sy * r1)],
                   fill=TICK_COLOR, width=round(TICK_W * k))
    font = ImageFont.truetype(str(FONT), round(NUMERAL_SIZE * k))
    font.set_variation_by_axes([NUMERAL_WEIGHT, 100, 0])
    for n in range(1, 13):
        a = math.radians(n * 30)
        x, y = c + math.sin(a) * NUMERAL_R * k, c - math.cos(a) * NUMERAL_R * k
        text = str(n)
        l, t, r, b = font.getbbox(text, anchor="ls")
        tile = Image.new("RGBA", (int(r - l) + 8, int(b - t) + 8), (0, 0, 0, 0))
        ImageDraw.Draw(tile).text((4 - l, 4 - t), text, font=font, fill=NUMERAL_COLOR, anchor="ls")
        tile = tile.rotate(-NUMERAL_ROTATION[n], resample=Image.BICUBIC, expand=True)
        img.alpha_composite(tile, (round(x - tile.width / 2), round(y - tile.height / 2)))
    return _down(img)


def render_hand(length: float) -> Image.Image:
    """Hand pointing up; the pivot sits `length` ref px below the image top."""
    img, d, k = _canvas(HAND_W + 2, length + HAND_BASE)
    cx = (HAND_W + 2) / 2 * k
    half = HAND_W / 2 * k
    ch = TIP_CHAMFER * k
    top = 0.5 * k
    bottom = (length + HAND_BASE) * k
    d.polygon([(cx - half, bottom), (cx - half, top + ch), (cx - half + ch, top),
               (cx + half - ch, top), (cx + half, top + ch), (cx + half, bottom)], fill=HAND_COLOR)
    ih = INLAY_W / 2 * k
    d.rectangle([cx - ih, top + INLAY_END_GAP * k, cx + ih, (length - INLAY_START) * k],
                fill=INLAY_COLOR)
    return _down(img)


def render_second() -> Image.Image:
    img, d, k = _canvas(SECOND_W + 2, SECOND_LEN + SECOND_TAIL)
    cx = (SECOND_W + 2) / 2 * k
    d.rectangle([cx - SECOND_W / 2 * k, 0, cx + SECOND_W / 2 * k, img.height], fill=RED)
    return _down(img)


def hand_geometry(name: str):
    """(pivot offset from image top, image size) in ref px, for the XML and the tests."""
    if name == "second":
        return SECOND_LEN, (SECOND_W + 2, SECOND_LEN + SECOND_TAIL)
    length = HOUR_LEN if name == "hour" else MINUTE_LEN
    return length, (HAND_W + 2, length + HAND_BASE)


def compose_face(hms=PREVIEW_TIME, size=REF) -> Image.Image:
    """Dial + hands + cap on black at `size` px, no complications."""
    k = PX_PER_REF
    base = Image.new("RGBA", (REF * k, REF * k), (0, 0, 0, 255))
    base.alpha_composite(render_dial())
    h, m, s = hms
    angles = {"hour": (h % 12 + m / 60 + s / 3600) * 30, "minute": (m + s / 60) * 6, "second": s * 6}
    for name, img in (("hour", render_hand(HOUR_LEN)), ("minute", render_hand(MINUTE_LEN)),
                      ("second", render_second())):
        pivot, _ = hand_geometry(name)
        px, py = img.width / 2, pivot * k
        pad = int(max(img.size))
        tile = Image.new("RGBA", (img.width + 2 * pad, img.height + 2 * pad), (0, 0, 0, 0))
        tile.alpha_composite(img, (pad, pad))
        tile = tile.rotate(-angles[name], resample=Image.BICUBIC, center=(px + pad, py + pad))
        base.alpha_composite(tile, (round(CENTER * k - px - pad), round(CENTER * k - py - pad)))
    d = ImageDraw.Draw(base)
    c = CENTER * k
    for r, col in ((CAP_RING_R, (255, 255, 255, 255)), (CAP_RED_R, RED), (CAP_DOT_R, (0, 0, 0, 255))):
        d.ellipse([c - r * k, c - r * k, c + r * k, c + r * k], fill=col)
    return base.resize((size, size), Image.LANCZOS)


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    render_dial().save(OUT / "dial.png")
    render_hand(HOUR_LEN).save(OUT / "hour.png")
    render_hand(MINUTE_LEN).save(OUT / "minute.png")
    render_second().save(OUT / "second.png")
    compose_face(size=REF).convert("RGB").save(OUT / "preview.png")


if __name__ == "__main__":
    main()
