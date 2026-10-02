# Analog Watch Face Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Wear OS watch face that looks like the owner's current Samsung analog face, with four hideable complication slots, that adapts to any Wear OS screen size and shape.

**Architecture:** A code-free Watch Face Format v3 app (`hasCode="false"`): a Gradle module that ships one XML face definition plus PNG dial and hand images. Two small Python generators produce those files from measurements of the reference screenshot (`tools/gen_assets.py` for images, `tools/build_face.py` for the XML), so geometry lives in one place. Python `unittest` tests pin the geometry, Google's `wff-validator` checks the XML, and a script matrix screenshots the face on several Wear emulator shapes.

**Tech Stack:** Watch Face Format v3, Android Gradle Plugin 8.7.2 / Gradle 8.10.2 (same as `prayertime-widgets`), Python 3 + Pillow, Android emulator + `adb`, Google `wff-validator.jar`.

**Spec:** `docs/superpowers/specs/2026-10-02-analog-watchface-design.md`

**Prototype note:** Every file in Tasks 1-3 was written and run in a scratch directory before this plan was written: 19 tests pass, `wff-validator` passes on v3 and v4, the APK builds, and the face renders correctly on the `GalaxyWatch7_API36` emulator. The code below is that exact code. The only things the prototype did not exercise are `create_avds.sh`, `avd_shot.sh`, the three new AVD shapes and the real watch, which is what Tasks 3 and 4 are for.

## Working method

- Per the owner's global rules, do the work in a git worktree of this repo (`git worktree add ../analog-watchface-impl`), merge back to `main` locally when all tasks pass, and delete the worktree. **Never push.**
- Commit messages carry no `Co-Authored-By` or `Claude-Session` trailer.
- Stage files by explicit path, never `git add -A`.
- Run all commands from the repo root unless a step says otherwise.
- The owner's wireless-debugging connection to the real watch had dropped by the time this plan was written. Tasks 1-3 only need the emulator. Task 4 needs the owner to reconnect the watch.

## Global Constraints

- Watch Face Format version **3** in the manifest, so the floor is **Wear OS 5.1 (API 35)** (`minSdk = 35`). Auto-sizing text needs v3.
- Package / applicationId: `com.seyedjafari.analogwatchface`. It must **not** reuse `com.seyedjafari.prayertimes`.
- Code-free: `android:hasCode="false"`. No Kotlin or Java in this project.
- **Four** complication slots: top, right, left, bottom. Slot ids 0, 1, 2, 3.
- Every slot lists `EMPTY` in `supportedTypes`, and no slot has a `<Complication type="EMPTY">` block. An empty slot must draw nothing.
- All geometry is in the face's 450 x 450 reference space (centre 225, 225). Hand `x`, `y`, `width`, `height` must be integers (the validator rejects decimals).
- Slot corners stay within 170 units of the centre, so they clear the numerals on round and square screens.
- Complication text uses `isAutoSize="TRUE"` and `ellipsis="TRUE"`.
- Slots are hidden in ambient mode. The red cap disc and the second hand are hidden in ambient mode. The dial is dimmed (alpha 120).
- Default providers: top = weather (see Task 4), right = `WATCH_BATTERY`, left = next-prayer from `com.seyedjafari.prayertimes`, bottom = `STEP_COUNT`. A missing provider falls back to Empty.
- Reference capture of the original: `docs/reference/original-watchface.png` (480 x 480, from the owner's SM-L315F, Wear OS 6).
- Colours: red `#e22b07`, grey text `#8c8c8c`, inlay `#e0e0e0`.

## Review Focus

Failure modes the spec implies that are most likely to bite a person using the face. Each has a test or a named verification step in the task that owns it.

1. **A long complication value** (for example a long prayer name or "Wednesday 30 September") must shrink or ellipsise inside its slot and never spill over a neighbour. Pinned in Task 2 by `test_all_complication_text_shrinks_to_fit_and_ellipsises`, and on screen in Task 3.
2. **A watch that does not have the prayer-times app installed**: the left slot must be blank, not an error or a stale placeholder. Pinned in Task 2 by `test_prayer_slot_prefers_the_prayer_app_and_falls_back_to_empty`, and on screen in Task 3, because the three new AVDs do not have that app.
3. **Square and rectangular screens**: nothing clipped and no slot overlapping another. Pinned in Task 2 by `test_slots_stay_inside_the_circle_that_clears_the_numerals` and `test_slots_do_not_overlap` (that second test already caught a real overlap in the prototype), and on screen in Task 3 on the square and rect AVDs.
4. **The user hides every complication**: the face must look like a clean analog dial with nothing else drawn. Pinned in Task 2 by `test_all_empty_variant_has_no_default_providers` and `test_every_slot_can_be_set_to_empty_and_empty_draws_nothing`, and on screen in Task 3 with the all-empty build.
5. **Always-on (ambient) mode**: slots must disappear and the bright centre pixels must not stay lit. Pinned in Task 2 by `test_slots_are_hidden_in_ambient_mode`, and checked on the emulator in Task 3.
6. **Hand rotation centre**: if the pivot is off, hands visibly wobble around the cap. Pinned in Task 2 by `test_every_hand_rotates_about_the_dial_centre`.

---

## File map

```
analog-watchface/
  .gitignore
  requirements.txt                         # Pillow
  settings.gradle.kts, build.gradle.kts, gradle.properties
  gradlew, gradlew.bat, gradle/wrapper/    # copied from prayertime-widgets/android
  app/
    build.gradle.kts
    src/main/AndroidManifest.xml
    src/main/res/
      raw/watchface.xml                    # GENERATED by tools/build_face.py
      drawable-nodpi/{dial,hour,minute,second,preview}.png   # GENERATED by tools/gen_assets.py
      xml/watch_face_info.xml
      values/strings.xml
  assets/fonts/Roboto-Regular.ttf          # Apache-2.0, pulled from the Wear emulator
  tools/gen_assets.py                      # dial + hands, one source of geometry
  tools/build_face.py                      # face XML from the same geometry
  tests/test_assets.py, tests/test_face.py
  scripts/                                 # validate, install, AVD matrix
  docs/reference/original-watchface.png    # already committed
  docs/screenshots/                        # per-emulator and real-watch captures
```

---

### Task 1: Dial and hand images

**Files:**
- Create: `requirements.txt`, `.gitignore`, `assets/fonts/Roboto-Regular.ttf`, `tools/gen_assets.py`, `tests/test_assets.py`
- Generated (committed): `app/src/main/res/drawable-nodpi/{dial,hour,minute,second,preview}.png`

**Interfaces:**
- Produces (used by Task 2): in `tools/gen_assets.py`: constants `REF` (480), `CENTER`, `PX_PER_REF` (4), `HOUR_LEN`, `MINUTE_LEN`, `CAP_RING_R`, `CAP_RED_R`, `CAP_DOT_R`; functions `render_dial()`, `render_hand(length)`, `render_second()`, `hand_geometry(name) -> (pivot_offset_ref_px, (width_ref_px, height_ref_px))` for `"hour" | "minute" | "second"`, `compose_face(hms=(9, 38, 13), size=480) -> RGBA image`. The reference measurements are in ref px: the 480 x 480 capture, centre (240, 240).

- [ ] **Step 1: Set up the worktree and the project files this task needs**

```bash
cd ~/Documents/projects/analog-watchface
git worktree add ../analog-watchface-impl -b impl-analog-watchface
cd ../analog-watchface-impl
mkdir -p tools tests scripts assets/fonts app/src/main/res/drawable-nodpi
```

Write `requirements.txt`:

```text
Pillow>=10
```

Write `.gitignore`:

```text
.gradle/
build/
local.properties
.idea/
*.iml
tools/bin/
tools/*.log
__pycache__/
```

```bash
pip3 install -r requirements.txt
```

Install the dependency, then take the font from the Wear emulator (the system Roboto, which is Apache-2.0). Start any Wear emulator first, for example the existing `GalaxyWatch7_API36`, then:

```bash
~/Library/Android/sdk/platform-tools/adb -s emulator-5554 pull /system/fonts/Roboto-Regular.ttf assets/fonts/Roboto-Regular.ttf
ls -la assets/fonts/Roboto-Regular.ttf
```

Expected: a file of about 2.3 MB. If the emulator is not on `emulator-5554`, use the serial from `adb devices`.

- [ ] **Step 2: Write the failing tests**

Create `tests/test_assets.py`:

```python
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
```

- [ ] **Step 3: Run the tests to confirm they fail**

Run: `python3 -m unittest discover -s tests -v`
Expected: an `ImportError`/`ModuleNotFoundError` for `gen_assets` (the generator does not exist yet).

- [ ] **Step 4: Write the generator**

Create `tools/gen_assets.py`:

```python
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
```

- [ ] **Step 5: Generate the images and run the tests**

```bash
python3 tools/gen_assets.py
python3 -m unittest discover -s tests -v
```

Expected: five PNGs in `app/src/main/res/drawable-nodpi/`, and all `test_assets` tests pass. The reference-match test prints a mean difference near 3.1 against a blank-face baseline near 8.7. The threshold is 4.0.

- [ ] **Step 6: Look at the result against the reference**

```bash
python3 - <<'PY'
import sys; sys.path.insert(0, "tools")
from PIL import Image
import gen_assets as g
ref = Image.open("docs/reference/original-watchface.png").convert("RGB")
me = g.compose_face().convert("RGB")
side = Image.new("RGB", (960, 480)); side.paste(ref, (0, 0)); side.paste(me, (480, 0))
side.save("/tmp/compare.png")
PY
```

Open `/tmp/compare.png`. Expected: the right half matches the left half except for the two complications, which the original has and the generated image does not. If a numeral is tilted the wrong way, adjust `NUMERAL_ROTATION` in `tools/gen_assets.py` and re-run Step 5.

- [ ] **Step 7: Commit**

```bash
git add requirements.txt .gitignore assets/fonts/Roboto-Regular.ttf tools/gen_assets.py tests/test_assets.py app/src/main/res/drawable-nodpi
git commit -m "feat: dial and hand image generator, measured from the original face"
```

---

### Task 2: Face XML, manifest and Gradle project

**Files:**
- Create: `tools/build_face.py`, `tests/test_face.py`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/watch_face_info.xml`, `app/src/main/res/values/strings.xml`, `scripts/_env.sh`, `scripts/fetch-validator.sh`, `scripts/validate.sh`, `scripts/install.sh`
- Copy: `gradlew`, `gradlew.bat`, `gradle/wrapper/` from `~/Documents/projects/prayertime-widgets/android/`
- Generated (committed): `app/src/main/res/raw/watchface.xml`

**Interfaces:**
- Consumes (from Task 1): `gen_assets.hand_geometry`, `REF`, `CAP_RING_R`, `CAP_RED_R`, `CAP_DOT_R`.
- Produces (used by Tasks 3 and 4): `tools/build_face.py` with `SLOTS` (list of `Slot`), `build(all_empty: bool = False) -> str`, and a CLI `python3 tools/build_face.py [--all-empty]` that writes `app/src/main/res/raw/watchface.xml`. `scripts/install.sh <adb-serial>` builds, installs and activates the face. `scripts/validate.sh` exits non-zero if the validator does not print `PASSED`.

- [ ] **Step 1: Write the failing tests**

Create `tests/test_face.py`:

```python
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
```

- [ ] **Step 2: Run to confirm they fail**

Run: `python3 -m unittest discover -s tests -p "test_face.py" 2>&1 | tail -5`
Expected: `ModuleNotFoundError: No module named 'build_face'`.

- [ ] **Step 3: Write the face generator**

Create `tools/build_face.py`:

```python
#!/usr/bin/env python3
"""Generate app/src/main/res/raw/watchface.xml.

The four complication slots share one layout, so the XML is generated from a single template
instead of being copied four times. Geometry lives here; hand geometry comes from gen_assets so
the images and the XML cannot drift apart. Coordinates are in the format's 450x450 reference
space, which the system scales to the real screen.
"""
import sys
from pathlib import Path

import gen_assets as g

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app" / "src" / "main" / "res" / "raw" / "watchface.xml"

SPACE = 450
F = SPACE / g.REF          # reference-capture px -> format units
CX = SPACE // 2

RED = "#ffe22b07"
WHITE = "#ffffffff"
GREY = "#ff8c8c8c"


def n(v: float) -> str:
    """Format a coordinate: at most 2 decimals, no trailing zeros."""
    return f"{v:.2f}".rstrip("0").rstrip(".")


class Slot:
    def __init__(self, slot_id, name, x, y, w, h, icon_color, text_color, policy):
        self.slot_id, self.name = slot_id, name
        self.x, self.y, self.w, self.h = x, y, w, h
        self.icon_color, self.text_color, self.policy = icon_color, text_color, policy


# Fallback for every slot when its preferred provider is missing: show nothing.
EMPTY_POLICY = '<DefaultProviderPolicy defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY"/>'
PRAYER_COMPONENT = ("com.seyedjafari.prayertimes/"
                    "com.seyedjafari.prayertimes.wear.complication.NextPrayerComplicationService")

SLOTS = [
    Slot(0, "top", 150, 106, 150, 76, WHITE, WHITE, EMPTY_POLICY),
    Slot(1, "right", 277, 236, 90, 76, RED, GREY,
         '<DefaultProviderPolicy defaultSystemProvider="WATCH_BATTERY" '
         'defaultSystemProviderType="SHORT_TEXT"/>'),
    Slot(2, "left", 83, 236, 90, 76, WHITE, WHITE,
         f'<DefaultProviderPolicy primaryProvider="{PRAYER_COMPONENT}" primaryProviderType="SHORT_TEXT" '
         'defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY"/>'),
    Slot(3, "bottom", 175, 300, 100, 76, WHITE, WHITE,
         '<DefaultProviderPolicy defaultSystemProvider="STEP_COUNT" '
         'defaultSystemProviderType="SHORT_TEXT"/>'),
]


def _text(slot: Slot, expr: str, y: float, h: float, size: int, color: str) -> str:
    return f"""        <PartText x="0" y="{n(y)}" width="{n(slot.w)}" height="{n(h)}">
          <Text align="CENTER" ellipsis="TRUE" isAutoSize="TRUE">
            <Font family="SYNC_TO_DEVICE" size="{size}" weight="NORMAL" color="{color}">
              <Template>%s<Parameter expression="{expr}"/></Template>
            </Font>
          </Text>
        </PartText>"""


def _icon(slot: Slot, expr: str, size: float) -> str:
    return f"""        <PartImage x="{n((slot.w - size) / 2)}" y="2" width="{n(size)}" height="{n(size)}" tintColor="{slot.icon_color}">
          <Image resource="{expr}"/>
        </PartImage>"""


def _slot_xml(slot: Slot) -> str:
    icon_h = 26
    text_y, text_h = icon_h + 2, 28
    title_y, title_h = text_y + text_h, slot.h - (text_y + text_h)
    short_text = "\n".join([
        _icon(slot, "[COMPLICATION.MONOCHROMATIC_IMAGE]", icon_h),
        _text(slot, "[COMPLICATION.TEXT]", text_y, text_h, 22, slot.text_color),
        _text(slot, "[COMPLICATION.TITLE]", title_y, title_h, 13, GREY),
    ])
    mono = _icon(slot, "[COMPLICATION.MONOCHROMATIC_IMAGE]", 40).replace('y="2"', f'y="{n((slot.h - 40) / 2)}"')
    small = f"""        <PartImage x="{n((slot.w - 44) / 2)}" y="{n((slot.h - 44) / 2)}" width="44" height="44">
          <Image resource="[COMPLICATION.SMALL_IMAGE]"/>
        </PartImage>"""
    return f"""    <ComplicationSlot slotId="{slot.slot_id}" name="{slot.name}" isCustomizable="TRUE"
        supportedTypes="SHORT_TEXT RANGED_VALUE MONOCHROMATIC_IMAGE SMALL_IMAGE EMPTY"
        x="{n(slot.x)}" y="{n(slot.y)}" width="{n(slot.w)}" height="{n(slot.h)}">
      <Variant mode="AMBIENT" target="alpha" value="0"/>
      {slot.policy}
      <BoundingBox x="0" y="0" width="{n(slot.w)}" height="{n(slot.h)}"/>
      <Complication type="SHORT_TEXT">
{short_text}
      </Complication>
      <Complication type="RANGED_VALUE">
{short_text}
      </Complication>
      <Complication type="MONOCHROMATIC_IMAGE">
{mono}
      </Complication>
      <Complication type="SMALL_IMAGE">
{small}
      </Complication>
    </ComplicationSlot>"""


def _hand(tag: str, name: str, extra: str = "") -> str:
    """The format wants integer geometry, so round it and derive the pivot from the rounded box.

    The width is forced even so the box stays centred on x = CX, and pivotY is computed so the
    rotation centre lands exactly on the dial centre despite the rounding.
    """
    pivot, (w, h) = g.hand_geometry(name)
    wu = 2 * round(w * F / 2)
    hu = round(h * F)
    x = CX - wu // 2
    y = round(CX - pivot * F)
    pivot_y = (CX - y) / hu
    return f"""      <{tag} resource="{name}" x="{x}" y="{y}" width="{wu}" height="{hu}"
          pivotX="0.5" pivotY="{pivot_y:.4f}">{extra}
      </{tag}>"""


def _ellipse(r_ref: float, color: str) -> str:
    r = r_ref * F
    return f"""      <Ellipse x="{n(CX - r)}" y="{n(CX - r)}" width="{n(2 * r)}" height="{n(2 * r)}">
        <Fill color="{color}"/>
      </Ellipse>
"""


def build(all_empty: bool = False) -> str:
    """Return the face XML. `all_empty` swaps every slot's default for Empty (hidden-slot check)."""
    slots_in = [Slot(s.slot_id, s.name, s.x, s.y, s.w, s.h, s.icon_color, s.text_color, EMPTY_POLICY)
                for s in SLOTS] if all_empty else SLOTS
    ring = _ellipse(g.CAP_RING_R, "#ffffffff")
    core = _ellipse(g.CAP_RED_R, RED) + _ellipse(g.CAP_DOT_R, "#ff000000")
    slots = "\n".join(_slot_xml(s) for s in slots_in)
    ambient_hide = '\n        <Variant mode="AMBIENT" target="alpha" value="0"/>'
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- GENERATED by tools/build_face.py - edit the generator, not this file. -->
<WatchFace width="{SPACE}" height="{SPACE}">
  <Scene backgroundColor="#ff000000">
    <PartImage name="dial" x="0" y="0" width="{SPACE}" height="{SPACE}" alpha="255">
      <Variant mode="AMBIENT" target="alpha" value="120"/>
      <Image resource="dial"/>
    </PartImage>

{slots}

    <AnalogClock x="0" y="0" width="{SPACE}" height="{SPACE}">
{_hand("HourHand", "hour")}
{_hand("MinuteHand", "minute")}
{_hand("SecondHand", "second", ambient_hide + chr(10) + '        <Sweep frequency="15"/>')}
    </AnalogClock>

    <PartDraw name="cap_ring" x="0" y="0" width="{SPACE}" height="{SPACE}">
{ring}    </PartDraw>
    <PartDraw name="cap_core" x="0" y="0" width="{SPACE}" height="{SPACE}">
      <Variant mode="AMBIENT" target="alpha" value="0"/>
{core}    </PartDraw>
  </Scene>
</WatchFace>
"""


def main() -> None:
    all_empty = "--all-empty" in sys.argv[1:]
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(build(all_empty), encoding="utf-8")


if __name__ == "__main__":
    main()
```

- [ ] **Step 4: Write the Gradle project and manifest**

Copy the wrapper from the existing project (same Gradle 8.10.2):

```bash
SRC=~/Documents/projects/prayertime-widgets/android
mkdir -p gradle
cp -R "$SRC/gradle/wrapper" gradle/
cp "$SRC/gradlew" "$SRC/gradlew.bat" .
printf 'sdk.dir=%s\n' "$HOME/Library/Android/sdk" > local.properties   # gitignored
```

Create `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "AnalogWatchFace"
include(":app")
```

Create `build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application") version "8.7.2" apply false
}
```

Create `gradle.properties`:

```text
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
org.gradle.caching=true
```

Create `app/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.application")
}

android {
    namespace = "com.seyedjafari.analogwatchface"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.seyedjafari.analogwatchface"
        // WFF v3 (auto-sizing text) needs Wear OS 5.1 = API 35.
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
```

Create `app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature android:name="android.hardware.type.watch" />

    <application
        android:label="@string/app_name"
        android:hasCode="false">

        <!-- WFF v3 = Wear OS 5.1. Needed for isAutoSize text. -->
        <property
            android:name="com.google.wear.watchface.format.version"
            android:value="3" />

        <service
            android:name="androidx.wear.watchface.WatchFaceService"
            android:directBootAware="true"
            android:exported="true"
            android:permission="android.permission.BIND_WALLPAPER">
            <intent-filter>
                <action android:name="android.service.wallpaper.WallpaperService" />
                <category android:name="com.google.android.wearable.watchface.category.WATCH_FACE" />
            </intent-filter>
            <meta-data
                android:name="android.service.wallpaper"
                android:resource="@xml/watch_face_info" />
            <meta-data
                android:name="com.google.android.wearable.watchface.preview"
                android:resource="@drawable/preview" />
            <meta-data
                android:name="com.google.android.wearable.watchface.preview_circular"
                android:resource="@drawable/preview" />
        </service>
    </application>
</manifest>
```

Create `app/src/main/res/xml/watch_face_info.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<WatchFaceInfo>
    <Preview value="@drawable/preview" />
    <Category value="CATEGORY_EMPTY" />
    <AvailableInRetail value="false" />
    <MultipleInstancesAllowed value="true" />
    <Editable value="true" />
</WatchFaceInfo>
```

Create `app/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Analog Plus</string>
</resources>
```

- [ ] **Step 5: Write the scripts**

Create `scripts/_env.sh`:

```bash
# Shared by the other scripts. Sourced, not executed.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$SDK/platform-tools/adb"
EMULATOR="$SDK/emulator/emulator"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"
APP_ID="com.seyedjafari.analogwatchface"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
WFF_VERSION=3
```

Create `scripts/fetch-validator.sh`:

```bash
#!/usr/bin/env bash
# Download Google's Watch Face Format validator into tools/bin (gitignored).
set -euo pipefail
source "$(dirname "$0")/_env.sh"
mkdir -p "$ROOT/tools/bin"
gh release download latest -R google/watchface -p wff-validator.jar -D "$ROOT/tools/bin" --clobber
```

Create `scripts/validate.sh`:

```bash
#!/usr/bin/env bash
# Validate the generated face against the WFF version the manifest declares.
set -euo pipefail
source "$(dirname "$0")/_env.sh"
JAR="$ROOT/tools/bin/wff-validator.jar"
[ -f "$JAR" ] || "$ROOT/scripts/fetch-validator.sh"
out="$(java -jar "$JAR" "$WFF_VERSION" "$ROOT/app/src/main/res/raw/watchface.xml" 2>&1)"
echo "$out"
echo "$out" | grep -q "PASSED"
```

Create `scripts/install.sh`:

```bash
#!/usr/bin/env bash
# Build, install and (on Google Wear emulators) activate the face.  Usage: install.sh <adb-serial>
# On a real Samsung watch the activation broadcast is ignored: long-press the face to pick it.
set -euo pipefail
source "$(dirname "$0")/_env.sh"
serial="${1:?usage: install.sh <adb-serial>}"
"$ROOT/gradlew" -p "$ROOT" -q assembleDebug
"$ADB" -s "$serial" install -r "$APK"
"$ADB" -s "$serial" shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE \
  --es operation set-watchface --es watchFaceId "$APP_ID" >/dev/null
```

```bash
chmod +x scripts/*.sh
bash -n scripts/*.sh && echo syntax-ok
```

- [ ] **Step 6: Generate the face and run all tests**

```bash
python3 tools/build_face.py
python3 -m unittest discover -s tests -v
```

Expected: `Ran 19 tests ... OK`.

- [ ] **Step 7: Validate the face with Google's validator**

```bash
scripts/validate.sh
```

Expected last line: `PASSED : .../watchface.xml is valid against watch face format version #3`. This is the check that catches element-order and integer-geometry mistakes. If it fails, read the `SEVERE` line: it names the line and the element expected.

- [ ] **Step 8: Build the APK**

```bash
./gradlew --no-daemon assembleDebug 2>&1 | tail -5
ls -la app/build/outputs/apk/debug/app-debug.apk
```

Expected: `BUILD SUCCESSFUL` and an APK of about 190 KB.

- [ ] **Step 9: Install on the emulator and look at it**

```bash
scripts/install.sh emulator-5554
sleep 4
~/Library/Android/sdk/platform-tools/adb -s emulator-5554 exec-out screencap -p > /tmp/first-run.png
```

Open `/tmp/first-run.png`. Expected: the dial, three hands with the red cap, battery on the right (red icon), steps at the bottom, and, because this emulator has the prayer-times app installed, the next prayer on the left. The top slot is empty because no weather provider is set yet (Task 4). The hands must rotate about the cap centre.

- [ ] **Step 10: Commit**

```bash
git add tools/build_face.py tests/test_face.py settings.gradle.kts build.gradle.kts gradle.properties gradlew gradlew.bat gradle app scripts/_env.sh scripts/fetch-validator.sh scripts/validate.sh scripts/install.sh
git commit -m "feat: four-slot WFF v3 face, Gradle project, validator and install scripts"
```

---

### Task 3: Emulator matrix, hidden-slot and ambient checks

**Files:**
- Create: `scripts/create_avds.sh`, `scripts/avd_shot.sh`
- Create (committed captures): `docs/screenshots/GalaxyWatch7_API36.png`, `docs/screenshots/AnalogWF_small_round.png`, `docs/screenshots/AnalogWF_square.png`, `docs/screenshots/AnalogWF_rect.png`, `docs/screenshots/all-slots-hidden.png`, `docs/screenshots/ambient.png`
- Modify (only if a shape shows a problem): `tools/build_face.py` (`SLOTS` geometry)

**Interfaces:**
- Consumes (from Task 2): `scripts/install.sh <serial>`, `python3 tools/build_face.py [--all-empty]`, `scripts/_env.sh` variables (`ADB`, `EMULATOR`, `AVDMANAGER`, `APP_ID`).
- Produces: `scripts/create_avds.sh` (creates `AnalogWF_small_round`, `AnalogWF_square`, `AnalogWF_rect`) and `scripts/avd_shot.sh <avd-name> <even-port>` (boots headless, installs, screenshots into `docs/screenshots/<avd-name>.png`, shuts the AVD down).

- [ ] **Step 1: Write the two scripts**

Create `scripts/create_avds.sh`:

```bash
#!/usr/bin/env bash
# Create one Wear OS 6 AVD per screen shape/size that the face must support.
# wearos_large_round already exists as GalaxyWatch7_API36 and is not recreated.
set -euo pipefail
source "$(dirname "$0")/_env.sh"
IMAGE="system-images;android-36;android-wear-signed;arm64-v8a"
existing="$("$EMULATOR" -list-avds)"
for profile in wearos_small_round wearos_square wearos_rect; do
  name="AnalogWF_${profile#wearos_}"
  if grep -qx "$name" <<<"$existing"; then echo "$name exists"; continue; fi
  echo no | "$AVDMANAGER" create avd -n "$name" -k "$IMAGE" -d "$profile"
done
```

Create `scripts/avd_shot.sh`:

```bash
#!/usr/bin/env bash
# Boot one AVD headless, install the face, screenshot it, shut the AVD down.
# Usage: avd_shot.sh <avd-name> <even-port>   ->   docs/screenshots/<avd-name>.png
set -euo pipefail
source "$(dirname "$0")/_env.sh"
name="${1:?avd name}"; port="${2:?even port, e.g. 5560}"
serial="emulator-$port"
mkdir -p "$ROOT/docs/screenshots"
"$EMULATOR" -avd "$name" -port "$port" -no-window -no-audio -no-snapshot -no-boot-anim \
  >"$ROOT/tools/emulator-$name.log" 2>&1 &
trap '"$ADB" -s "$serial" emu kill >/dev/null 2>&1 || true' EXIT
"$ADB" -s "$serial" wait-for-device
until [ "$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 3; done
sleep 10   # let sysui settle on the default face
"$ROOT/scripts/install.sh" "$serial"
sleep 6    # let complication providers deliver their first data
"$ADB" -s "$serial" exec-out screencap -p >"$ROOT/docs/screenshots/$name.png"
echo "saved docs/screenshots/$name.png"
```

```bash
chmod +x scripts/create_avds.sh scripts/avd_shot.sh
bash -n scripts/create_avds.sh scripts/avd_shot.sh && echo syntax-ok
```

- [ ] **Step 2: Create the three new AVDs**

```bash
scripts/create_avds.sh
~/Library/Android/sdk/emulator/emulator -list-avds
```

Expected: the list contains `GalaxyWatch7_API36`, `AnalogWF_small_round`, `AnalogWF_square`, `AnalogWF_rect` (plus any others you already had). The existing `GalaxyWatch7_API36` is the `wearos_large_round` profile at 480 x 480, which matches the owner's watch.

- [ ] **Step 3: Screenshot the face on every shape**

An AVD cannot be started twice, and `GalaxyWatch7_API36` is probably already running, so stop it first. Then:

```bash
~/Library/Android/sdk/platform-tools/adb -s emulator-5554 emu kill 2>/dev/null || true
sleep 5
```

Then shoot each shape:

```bash
scripts/avd_shot.sh GalaxyWatch7_API36 5560
scripts/avd_shot.sh AnalogWF_small_round 5562
scripts/avd_shot.sh AnalogWF_square 5564
scripts/avd_shot.sh AnalogWF_rect 5566
ls -la docs/screenshots
```

Expected: four PNGs. If a boot hangs, check `tools/emulator-<name>.log`. Headless first boots can take a couple of minutes.

- [ ] **Step 4: Inspect each capture**

Open all four. For each one, check:
1. Nothing is clipped at the screen edge, and no slot overlaps a numeral, a tick or another slot.
2. The hands pass over the slots and rotate around the cap.
3. Battery (right) and steps (bottom) show data.
4. **Left slot on the three new AVDs is blank** (they have no prayer-times app). That is the Review Focus case "provider not installed". On `GalaxyWatch7_API36` the left slot shows the next prayer.
5. The top slot is empty everywhere (no weather yet).
6. On square and rect, the dial is a centred circle on black with nothing cut off.

If anything is wrong, adjust the `SLOTS` geometry in `tools/build_face.py`, then:

```bash
python3 tools/build_face.py
python3 -m unittest discover -s tests
scripts/validate.sh
```

All three must still pass (the overlap and radius tests are the guard rails), then re-shoot the affected AVD. Do not weaken a test to make a layout pass.

- [ ] **Step 5: Check the "user hides every slot" case**

```bash
python3 tools/build_face.py --all-empty
scripts/validate.sh
scripts/install.sh emulator-5554
sleep 4
~/Library/Android/sdk/platform-tools/adb -s emulator-5554 exec-out screencap -p > docs/screenshots/all-slots-hidden.png
python3 tools/build_face.py            # restore the real face
git diff --stat app/src/main/res/raw/watchface.xml
```

Expected: the screenshot shows only the dial, hands and cap, with nothing in the slot areas. The final `git diff --stat` prints nothing, which proves the real face was restored. Re-install the real face: `scripts/install.sh emulator-5554`.

- [ ] **Step 6: Check ambient (always-on) mode**

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB -s emulator-5554 shell input keyevent KEYCODE_SLEEP
sleep 3
$ADB -s emulator-5554 exec-out screencap -p > docs/screenshots/ambient.png
$ADB -s emulator-5554 shell input keyevent KEYCODE_WAKEUP
```

Expected: either a dimmed face with the complications gone, no second hand and no red cap, or a fully black screen if the emulator turns the display off instead of entering ambient mode. If it is fully black, delete `docs/screenshots/ambient.png`, note "ambient not observable on emulator, covered by `test_slots_are_hidden_in_ambient_mode` and verified on the real watch in Task 4" in the commit message, and move on.

- [ ] **Step 7: Commit**

```bash
git add scripts/create_avds.sh scripts/avd_shot.sh docs/screenshots tools/build_face.py app/src/main/res/raw/watchface.xml
git commit -m "test: screenshot the face on four Wear emulator shapes, hidden-slot and ambient checks"
```

(If you changed nothing in `tools/build_face.py` and the generated XML, git will simply not stage those paths.)

---

### Task 4: Weather default, real-watch install, README

**Files:**
- Modify: `tools/build_face.py` (top slot's `DefaultProviderPolicy`, only if a weather component is found)
- Modify: `tests/test_face.py` (a test for the weather default, only if found)
- Create: `README.md`, `docs/screenshots/galaxy-watch-real.png`

**Interfaces:**
- Consumes: everything from Tasks 1-3, plus the real watch connected over wireless debugging.

This task needs the owner. Ask them to reconnect the watch (Settings > Developer options > Wireless debugging, then `adb pair` / `adb connect` as they did for the Wear OS app), and wait.

- [ ] **Step 1: Confirm the watch is connected**

```bash
~/Library/Android/sdk/platform-tools/adb devices -l
```

Expected: a line with `model:SM_L315F`. Use that serial as `$W` below. If it is missing, stop and ask the owner to reconnect.

- [ ] **Step 2: Find the weather complication data source on the real watch**

```bash
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB -s "$W" shell cmd package query-services --brief -a android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST | grep -iE "weather|samsung" 
```

This lists every complication data source on the watch. Look for a weather entry. The component prints as `package/class` on the line after `Service #N:`. Also try `$ADB -s "$W" shell pm list packages | grep -i weather`.

- [ ] **Step 3a: If a weather data source exists, make it the top slot's default**

Add a constant next to `PRAYER_COMPONENT` in `tools/build_face.py`, using the exact `package/class` printed in Step 2, and use it in the top slot:

```python
WEATHER_COMPONENT = "<package>/<class printed by Step 2>"
```

```python
    Slot(0, "top", 150, 106, 150, 76, WHITE, WHITE,
         f'<DefaultProviderPolicy primaryProvider="{WEATHER_COMPONENT}" primaryProviderType="SHORT_TEXT" '
         'defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY"/>'),
```

If Samsung's weather source offers a different type than `SHORT_TEXT` (the Step 2 output or the editor picker shows it), use that type and add its `Complication` block to `_slot_xml` first; the validator and `test_every_slot_can_be_set_to_empty_and_empty_draws_nothing` will tell you if a supported type has no block.

Add a test to `tests/test_face.py` inside `SlotTest`:

```python
    def test_top_slot_prefers_weather_and_falls_back_to_empty(self):
        policy = _slots()[0].find("DefaultProviderPolicy")
        self.assertEqual(policy.get("primaryProvider"), bf.WEATHER_COMPONENT)
        self.assertEqual(policy.get("defaultSystemProvider"), "EMPTY")
```

Run:

```bash
python3 tools/build_face.py
python3 -m unittest discover -s tests
scripts/validate.sh
```

- [ ] **Step 3b: If no weather data source exists**

Leave the top slot defaulting to Empty. Record this in the README (Step 7) as: "Weather: pick it once in the editor." Skip the test in Step 3a.

- [ ] **Step 4: Install on the real watch**

```bash
scripts/install.sh "$W"
```

Expected: `Success`. Samsung watches ignore the activation broadcast, so activate by hand. Tell the owner: on the watch, long-press the current face, swipe to the end, tap `+` / "Add watch face" if needed, and pick **Analog Plus**. Wait for them to confirm.

- [ ] **Step 5: Verify on the real watch**

Take a capture and ask the owner to check the two things the emulator cannot show:

```bash
$ADB -s "$W" exec-out screencap -p > docs/screenshots/galaxy-watch-real.png
```

Then ask the owner to long-press the face, tap **Customize**, and for each of the four slots confirm:
1. Samsung's picker offers **Empty** (this is the hide-a-complication requirement).
2. Choosing Empty makes the slot disappear and nothing else moves.
3. The choice is still there after they leave and re-enter the editor.
4. The face enters always-on mode with complications gone.

Open `docs/screenshots/galaxy-watch-real.png` and compare with `docs/reference/original-watchface.png`. If Samsung's picker does **not** offer Empty, stop and tell the owner: that is a requirements problem for the owner to decide, and the fix would be a per-slot configuration option, which changes the spec.

- [ ] **Step 6: Write the README**

Create `README.md` covering: what the face is, the screenshot (`docs/screenshots/galaxy-watch-real.png` and the reference), the four slots and their defaults, how to hide a slot (Customize, pick the slot, choose Empty), requirements (Wear OS 5.1 or later), how to build and install (`./gradlew assembleDebug`, `scripts/install.sh <serial>`, wireless-debugging pointer to the prayertime-widgets README), how to change the geometry (edit `tools/gen_assets.py` / `tools/build_face.py`, run both, run tests and `scripts/validate.sh`), and the emulator matrix (`scripts/create_avds.sh`, `scripts/avd_shot.sh`). Keep it factual and short. State the weather result from Step 3a or 3b.

- [ ] **Step 7: Final verification and commit**

```bash
python3 tools/gen_assets.py && python3 tools/build_face.py
python3 -m unittest discover -s tests
scripts/validate.sh
./gradlew --no-daemon assembleDebug 2>&1 | tail -3
git status --short
```

Expected: tests pass, `PASSED`, `BUILD SUCCESSFUL`, and `git status` shows only the files you changed in this task (regenerating must not change committed images or XML). Then:

```bash
git add README.md docs/screenshots/galaxy-watch-real.png tools/build_face.py tests/test_face.py app/src/main/res/raw/watchface.xml
git commit -m "feat: weather default, real-watch verification, README"
```

- [ ] **Step 8: Merge back to main and clean up**

```bash
cd ~/Documents/projects/analog-watchface
git merge --no-ff impl-analog-watchface -m "Merge: analog watch face with four hideable complications"
git worktree remove ../analog-watchface-impl
git branch -d impl-analog-watchface
git log --oneline | head -8
```

Do not push.

---

## Self-review

**Spec coverage**

| Spec requirement | Where |
|---|---|
| Looks like the original face | Task 1 (measured dial/hands, reference-match test), Task 3 step 4, Task 4 step 5 |
| Four complication slots with defaults | Task 2 (`SLOTS`), Task 4 (weather) |
| Any slot can be hidden, draws nothing | Task 2 tests, Task 3 step 5 (all-empty build), Task 4 step 5 (real picker) |
| Adapts to sizes and shapes | 450-unit space, radius test, Task 3 AVD matrix (small round, large round, square, rect) |
| Always-on mode | Task 2 test, Task 3 step 6, Task 4 step 5 |
| Fallback when a provider is missing | Task 2 test, Task 3 step 4 (three AVDs without the prayer app) |
| Reference screenshot in the project | already committed at `docs/reference/original-watchface.png`; real-watch capture added in Task 4 |
| Emulator for testing and iteration | Task 3 (`create_avds.sh`, `avd_shot.sh`) and every task's emulator step |
| Real-watch sideload | Task 4 |

**Placeholder scan:** the only value not known when the plan was written is the Samsung weather component name in Task 4 Step 3a. It is printed by the command in Step 2 and has a defined fallback (Step 3b). Everything else is concrete code that was run.

**Type consistency:** `hand_geometry`, `SLOTS`, `build(all_empty)`, `WEATHER_COMPONENT`, `PRAYER_COMPONENT` and the script names match across tasks.
