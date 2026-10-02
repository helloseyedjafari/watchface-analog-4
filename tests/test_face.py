import math
import re
import sys
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import build_face as bf  # noqa: E402

FACE = ROOT / "app" / "src" / "main" / "res" / "raw" / "watchface.xml"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"
GRADLE = ROOT / "app" / "build.gradle.kts"
CENTER = bf.SPACE / 2
# Numerals sit at ~198 ref px = 185 units, and are ~14 ref px tall, so anything
# inside 170 units clears them on a round or square screen.
MAX_SLOT_RADIUS = 170


def _root():
    return ET.parse(FACE).getroot()


def _slots():
    return _root().findall(".//ComplicationSlot")


def _box(slot):
    x, y, w, h = (float(slot.get(k)) for k in ("x", "y", "width", "height"))
    return x, y, w, h


class GeneratedFileTest(unittest.TestCase):
    def test_committed_xml_is_what_the_generator_produces(self):
        self.assertEqual(FACE.read_text(encoding="utf-8"), bf.build(),
                         "run: python3 tools/build_face.py")


class SlotTest(unittest.TestCase):
    def test_there_are_exactly_four_uniquely_numbered_slots(self):
        ids = sorted(s.get("slotId") for s in _slots())
        self.assertEqual(ids, ["0", "1", "2", "3"])

    def test_every_slot_can_be_set_to_empty_and_empty_draws_nothing(self):
        for s in _slots():
            types = s.get("supportedTypes").split()
            self.assertIn("EMPTY", types, f"slot {s.get('slotId')} cannot be hidden")
            drawn = [c.get("type") for c in s.findall("Complication")]
            self.assertNotIn("EMPTY", drawn, "an EMPTY block would draw a placeholder")
            for t in types:
                if t != "EMPTY":
                    self.assertIn(t, drawn, f"slot {s.get('slotId')} lacks a block for {t}")

    def test_slots_stay_inside_the_circle_that_clears_the_numerals(self):
        for s in _slots():
            x, y, w, h = _box(s)
            for cx, cy in ((x, y), (x + w, y), (x, y + h), (x + w, y + h)):
                r = math.hypot(cx - CENTER, cy - CENTER)
                self.assertLessEqual(r, MAX_SLOT_RADIUS, f"slot {s.get('slotId')} corner at r={r:.0f}")

    def test_slots_do_not_overlap(self):
        boxes = [(s.get("slotId"), _box(s)) for s in _slots()]
        for i, (ida, (ax, ay, aw, ah)) in enumerate(boxes):
            for idb, (bx, by, bw, bh) in boxes[i + 1:]:
                apart = ax + aw <= bx or bx + bw <= ax or ay + ah <= by or by + bh <= ay
                self.assertTrue(apart, f"slots {ida} and {idb} overlap")

    def test_slots_are_hidden_in_ambient_mode(self):
        for s in _slots():
            variants = [(v.get("mode"), v.get("target"), v.get("value")) for v in s.findall("Variant")]
            self.assertIn(("AMBIENT", "alpha", "0"), variants, f"slot {s.get('slotId')}")

    def test_all_complication_text_shrinks_to_fit_and_ellipsises(self):
        texts = [t for s in _slots() for t in s.iter("Text")]
        self.assertGreater(len(texts), 0)
        for t in texts:
            self.assertEqual(t.get("isAutoSize"), "TRUE")
            self.assertEqual(t.get("ellipsis"), "TRUE")

    def test_prayer_slot_prefers_the_prayer_app_and_falls_back_to_empty(self):
        policy = _slots()[2].find("DefaultProviderPolicy")
        self.assertIn("NextPrayerComplicationService", policy.get("primaryProvider"))
        self.assertEqual(policy.get("defaultSystemProvider"), "EMPTY")

    def test_all_empty_variant_has_no_default_providers(self):
        root = ET.fromstring(bf.build(all_empty=True))
        for s in root.findall(".//ComplicationSlot"):
            policy = s.find("DefaultProviderPolicy")
            self.assertEqual(policy.get("defaultSystemProvider"), "EMPTY")
            self.assertIsNone(policy.get("primaryProvider"))


class HandTest(unittest.TestCase):
    def test_every_hand_rotates_about_the_dial_centre(self):
        hands = _root().findall(".//HourHand") + _root().findall(".//MinuteHand") \
            + _root().findall(".//SecondHand")
        self.assertEqual(len(hands), 3)
        for hand in hands:
            x, y, w, h = (float(hand.get(k)) for k in ("x", "y", "width", "height"))
            px, py = float(hand.get("pivotX")), float(hand.get("pivotY"))
            self.assertAlmostEqual(x + px * w, CENTER, delta=0.5, msg=hand.tag)
            self.assertAlmostEqual(y + py * h, CENTER, delta=0.5, msg=hand.tag)


class PackagingTest(unittest.TestCase):
    def test_manifest_declares_a_code_free_wff_v3_face(self):
        text = MANIFEST.read_text(encoding="utf-8")
        self.assertIn('android:hasCode="false"', text)
        self.assertRegex(text, r'com\.google\.wear\.watchface\.format\.version"\s+android:value="3"')

    def test_min_sdk_matches_the_format_version(self):
        self.assertRegex(GRADLE.read_text(encoding="utf-8"), r"minSdk\s*=\s*35")

    def test_resources_referenced_by_the_face_exist(self):
        res = ROOT / "app" / "src" / "main" / "res" / "drawable-nodpi"
        names = set(re.findall(r'resource="([a-z_]+)"', FACE.read_text(encoding="utf-8")))
        for name in names:
            self.assertTrue((res / f"{name}.png").exists(), f"missing drawable {name}.png")


if __name__ == "__main__":
    unittest.main()
