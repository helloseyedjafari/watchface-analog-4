"""The onboarding Lottie files: structure the Android parser relies on, size cap, palette groups, and no drift
between tools/gen_onboarding_lottie.py and the committed JSON."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
import gen_onboarding_lottie as lot  # noqa: E402

RAW = ROOT / "phone" / "src" / "main" / "res" / "raw"
NAMES = ["onboarding_welcome", "onboarding_searching", "onboarding_found", "onboarding_permission"]
MAX_BYTES = 100_000
SHAPE_TYPES = {"gr", "el", "rc", "sh", "fl", "st", "tr", "tm"}
PALETTE = {"accent", "ink", "track", "onaccent"}


def walk(node, visit, group=None):
    """Calls visit(node, nearest enclosing palette group name or None) for every dict below `node`."""
    if isinstance(node, dict):
        if node.get("ty") == "gr" and node.get("nm") in PALETTE:
            group = node["nm"]
        visit(node, group)
        for v in node.values():
            walk(v, visit, group)
    elif isinstance(node, list):
        for v in node:
            walk(v, visit, group)


class GeneratedAnimations(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.docs = lot.build_all()

    def test_there_are_exactly_the_four_files_the_app_references(self):
        self.assertEqual(sorted(self.docs), sorted(NAMES))

    def test_composition_header(self):
        for name, d in self.docs.items():
            with self.subTest(name):
                self.assertEqual((d["w"], d["h"]), (480, 480))
                self.assertEqual(d["fr"], 30)
                self.assertEqual(d["ip"], 0)
                self.assertGreater(d["op"], d["ip"])
                self.assertEqual(d["assets"], [], "no embedded images")
                self.assertTrue(d["layers"])

    def test_layers_are_shape_layers_with_unique_indices_inside_the_composition(self):
        for name, d in self.docs.items():
            with self.subTest(name):
                inds = [layer["ind"] for layer in d["layers"]]
                self.assertEqual(len(inds), len(set(inds)))
                for layer in d["layers"]:
                    self.assertEqual(layer["ty"], 4)
                    self.assertGreaterEqual(layer["ip"], 0)
                    self.assertGreater(layer["op"], d["op"], "a layer is hidden from its op frame on; the last frame must show")
                    self.assertTrue(layer["shapes"])

    def test_only_supported_shape_types(self):
        def visit(n, _g):
            if isinstance(n.get("ty"), str):          # shape items have string types; layers have the number 4
                self.assertIn(n["ty"], SHAPE_TYPES, n)
        for name, d in self.docs.items():
            with self.subTest(name):
                walk(d["layers"], visit)

    def test_keyframes_are_ordered_complete_and_inside_the_composition(self):
        def visit(n, _g):
            if n.get("a") == 1:
                ks = n["k"]
                self.assertGreaterEqual(len(ks), 2)
                times = [k["t"] for k in ks]
                self.assertEqual(times, sorted(set(times)), "frames must strictly increase")
                self.assertGreaterEqual(times[0], 0)
                for k in ks:
                    self.assertTrue(k["s"], "every keyframe has a start value")
                for k in ks[:-1]:
                    self.assertIn("i", k)
                    self.assertIn("o", k)
                self.assertNotIn("i", ks[-1])
        for name, d in self.docs.items():
            with self.subTest(name):
                walk(d["layers"], visit)
                last = max(k["t"] for k in _all_keyframes(d))
                self.assertLessEqual(last, d["op"])

    def test_every_fill_and_stroke_is_in_a_palette_group_with_the_placeholder_colour(self):
        def visit(n, group):
            if n.get("ty") in ("fl", "st"):
                self.assertIsNotNone(group, "a fill or stroke outside accent/ink/track/onaccent would not be recoloured")
                self.assertEqual(n["c"]["k"], lot.PLACEHOLDER[group])
        for name, d in self.docs.items():
            with self.subTest(name):
                walk(d["layers"], visit)

    def test_every_animation_has_an_accent_group(self):
        for name, d in self.docs.items():
            with self.subTest(name):
                found = []
                walk(d["layers"], lambda n, _g: found.append(n["nm"]) if n.get("ty") == "gr" else None)
                self.assertIn("accent", found)

    def test_one_shot_animations_end_in_their_finished_state(self):
        found_doc = self.docs["onboarding_found"]
        ends = []
        walk(found_doc["layers"], lambda n, _g: ends.append(n["e"]["k"][-1]["s"][0]) if n.get("ty") == "tm" and n["e"]["a"] == 1 else None)
        self.assertEqual(sorted(ends), [100, 100], "ring and tick are fully drawn on the last frame")

    def test_size_cap(self):
        for name, d in self.docs.items():
            with self.subTest(name):
                self.assertLess(len(lot.render(d).encode()), MAX_BYTES)


def _all_keyframes(doc):
    out = []
    walk(doc["layers"], lambda n, _g: out.extend(n["k"]) if n.get("a") == 1 else None)
    return out


def value_at(prop, frame):
    """The value of an animated or static Lottie property at `frame` for frames at or after its last keyframe
    (all this checks); earlier frames are not evaluated."""
    if prop["a"] == 0:
        return prop["k"]
    last = prop["k"][-1]
    assert last["t"] <= frame, f"keyframe at {last['t']} is after frame {frame}"
    return last["s"]


def layer_named(doc, name):
    return next(layer for layer in doc["layers"] if layer["nm"] == name)


def trims_in(layer):
    found = []
    walk(layer["shapes"], lambda n, _g: found.append(n) if n.get("ty") == "tm" else None)
    return found


class FinalFrame(unittest.TestCase):
    """What progress 1.0 (frame == comp.op) shows: reduced motion renders it as the static image, and a
    non-repeating animation stays on it."""

    @classmethod
    def setUpClass(cls):
        cls.docs = lot.build_all()

    def test_every_layer_is_visible_on_the_last_frame(self):
        for name, d in self.docs.items():
            with self.subTest(name):
                for layer in d["layers"]:
                    self.assertLessEqual(layer["ip"], d["op"])
                    self.assertGreater(layer["op"], d["op"], layer["nm"])

    def test_found_ends_with_ring_closed_disc_full_and_tick_drawn(self):
        d = self.docs["onboarding_found"]
        op = d["op"]
        full = lot.SCALE * 100
        self.assertEqual([value_at(t["e"], op) for t in trims_in(layer_named(d, "ring"))], [[100]])
        self.assertEqual(value_at(layer_named(d, "disc")["ks"]["s"], op), [full, full, 100])
        tick = layer_named(d, "tick")
        self.assertEqual(value_at(tick["ks"]["o"], op), [100])
        self.assertEqual([value_at(t["e"], op) for t in trims_in(tick)], [[100]])

    def test_permission_ends_with_prompt_up_and_label_replaced_by_the_tick(self):
        d = self.docs["onboarding_permission"]
        op = d["op"]
        self.assertEqual(value_at(layer_named(d, "prompt")["ks"]["p"], op), [lot.C, lot.C - 10, 0])
        tick = layer_named(d, "tick")
        self.assertEqual(value_at(tick["ks"]["o"], op), [100])
        self.assertEqual([value_at(t["e"], op) for t in trims_in(tick)], [[100]])
        self.assertEqual(value_at(layer_named(d, "label")["ks"]["o"], op), [0])
        self.assertEqual(value_at(layer_named(d, "ripple")["ks"]["o"], op), [0])

    def test_looping_animations_end_where_they_start(self):
        """welcome and searching repeat: the last frame equals the first (rotations modulo a full turn). A pulse
        that has faded to opacity 0 on the last frame is invisible there, so restarting it is no visible jump."""
        for name in ("onboarding_welcome", "onboarding_searching"):
            d = self.docs[name]
            with self.subTest(name):
                def seam(n, _g):
                    if n.get("a") == 1:
                        self.assertEqual(n["k"][0]["s"], n["k"][-1]["s"])
                walk([layer["shapes"] for layer in d["layers"]], seam)
                for layer in d["layers"]:
                    if value_at(layer["ks"]["o"], d["op"]) == [0]:
                        continue
                    for key, prop in layer["ks"].items():
                        if prop["a"] != 1:
                            continue
                        first, last = prop["k"][0]["s"], prop["k"][-1]["s"]
                        if key == "r":
                            self.assertEqual((last[0] - first[0]) % 360, 0, layer["nm"])
                        else:
                            self.assertEqual(first, last, f"{layer['nm']}.{key}")


class CommittedFiles(unittest.TestCase):
    def test_files_exist_parse_and_stay_under_the_size_cap(self):
        for name in NAMES:
            with self.subTest(name):
                path = RAW / f"{name}.json"
                self.assertTrue(path.exists(), f"missing {path}; run tools/gen_onboarding_lottie.py")
                self.assertLess(path.stat().st_size, MAX_BYTES)
                json.loads(path.read_text(encoding="utf-8"))

    def test_committed_files_are_exactly_what_the_generator_writes(self):
        with tempfile.TemporaryDirectory() as tmp:
            for name, doc in lot.build_all().items():
                (Path(tmp) / f"{name}.json").write_text(lot.render(doc), encoding="utf-8")
            for name in NAMES:
                with self.subTest(name):
                    self.assertEqual((RAW / f"{name}.json").read_text(encoding="utf-8"),
                                     (Path(tmp) / f"{name}.json").read_text(encoding="utf-8"),
                                     "stale: run python3 tools/gen_onboarding_lottie.py")


if __name__ == "__main__":
    unittest.main()
