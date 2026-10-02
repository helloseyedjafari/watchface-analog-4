import math
import sys
import unittest
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageStat

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import gen_assets as g  # noqa: E402

REFERENCE = ROOT / "docs" / "reference" / "original-watchface.png"
# Areas the original fills with its two complications; excluded from the comparison.
COMPLICATION_BOXES = [(180, 115, 300, 190), (300, 265, 385, 340)]


def _mask(img: Image.Image) -> Image.Image:
    img = img.convert("RGB")
    draw = ImageDraw.Draw(img)
    for box in COMPLICATION_BOXES:
        draw.rectangle(box, fill=(0, 0, 0))
    return img


class FontTest(unittest.TestCase):
    def test_font_is_bundled(self):
        self.assertTrue(g.FONT.exists(), f"missing {g.FONT}")


class DialTest(unittest.TestCase):
    def test_dial_is_square_transparent_and_high_resolution(self):
        dial = g.render_dial()
        self.assertEqual(dial.size, (g.REF * g.PX_PER_REF,) * 2)
        self.assertEqual(dial.mode, "RGBA")
        self.assertEqual(dial.getpixel((0, 0))[3], 0, "corners must be transparent")
        self.assertEqual(dial.getpixel((dial.width // 2, dial.height // 2))[3], 0,
                         "centre must be transparent so the hands show")

    def test_dial_has_twelve_hour_dots(self):
        dial = g.render_dial()
        k = g.PX_PER_REF
        for hour in range(12):
            a = math.radians(hour * 30)
            x = (g.CENTER + math.sin(a) * g.DOT_R) * k
            y = (g.CENTER - math.cos(a) * g.DOT_R) * k
            self.assertGreater(dial.getpixel((round(x), round(y)))[3], 200, f"no dot at hour {hour}")


class HandTest(unittest.TestCase):
    def test_hand_pivot_matches_geometry(self):
        for name, render in (("hour", lambda: g.render_hand(g.HOUR_LEN)),
                             ("minute", lambda: g.render_hand(g.MINUTE_LEN)),
                             ("second", g.render_second)):
            pivot, (w, h) = g.hand_geometry(name)
            img = render()
            self.assertEqual(img.size, (round(w * g.PX_PER_REF), round(h * g.PX_PER_REF)), name)
            self.assertEqual(g.hand_geometry(name)[1][1] * g.PX_PER_REF, img.height, name)
            col = img.width // 2
            self.assertGreater(img.getpixel((col, round(pivot * g.PX_PER_REF) - 2))[3], 200, name)

    def test_minute_hand_is_longer_than_hour_hand(self):
        self.assertGreater(g.MINUTE_LEN, g.HOUR_LEN)


class ReferenceMatchTest(unittest.TestCase):
    def test_rendered_face_matches_the_original_capture(self):
        ref = Image.open(REFERENCE)
        me = g.compose_face(size=g.REF)
        diff = ImageChops.difference(_mask(ref), _mask(me)).convert("L")
        blank = ImageChops.difference(_mask(ref), Image.new("RGB", ref.size)).convert("L")
        score = ImageStat.Stat(diff).mean[0]
        self.assertLess(score, 4.0, f"mean abs diff {score:.2f} (blank face scores "
                                    f"{ImageStat.Stat(blank).mean[0]:.2f})")


if __name__ == "__main__":
    unittest.main()
