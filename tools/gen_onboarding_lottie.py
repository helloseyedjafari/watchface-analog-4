#!/usr/bin/env python3
"""Generate the four onboarding Lottie animations (phone/src/main/res/raw/onboarding_*.json).

The animations are drawn from the watch-face geometry: hand lengths and widths and the hour-dot ring come from
gen_assets, the four complication slot boxes come from build_face. The composition is the 480 x 480 reference
capture, so reference pixels are composition pixels; everything is drawn around the centre and shrunk by SCALE so the
outer dots keep a margin.

Colours: every fill and stroke sits in a group named accent / ink / track / onaccent. The app recolours those groups
at run time with Lottie dynamic properties (brand colours, light and dark). The colours written here are deliberately
loud placeholders (magenta, yellow, cyan, orange): if a group is ever not matched by the app, it shows.

    python3 tools/gen_onboarding_lottie.py            # writes the four files
    python3 tools/gen_onboarding_lottie.py --out DIR  # writes them somewhere else
"""
import argparse
import json
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_face as bf  # noqa: E402
import gen_assets as g  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "phone" / "src" / "main" / "res" / "raw"

FPS = 30
SIZE = g.REF
C = SIZE / 2
SCALE = 0.86
RING_R, RING_W = 200.0, 16.0

PLACEHOLDER = {
    "accent": [1, 0, 1, 1],
    "ink": [1, 0.9, 0, 1],
    "track": [0, 0.9, 0.9, 1],
    "onaccent": [1, 0.5, 0, 1],
}

EASE_I = {"x": [0.3], "y": [1]}
EASE_O = {"x": [0.2], "y": [0]}
LIN_I = {"x": [1], "y": [1]}
LIN_O = {"x": [0], "y": [0]}


def r(v):
    return round(float(v), 3)


# ---- properties -----------------------------------------------------------------------------------------------

def num(v):
    return {"a": 0, "k": r(v)}


def vec(*v):
    return {"a": 0, "k": [r(x) for x in v]}


def anim(points, linear=False):
    """points: [(frame, value)], value a number or a list of numbers. Frames strictly increase."""
    keys = []
    for n, (t, v) in enumerate(points):
        s = [r(x) for x in v] if isinstance(v, (list, tuple)) else [r(v)]
        k = {"t": t, "s": s}
        if n < len(points) - 1:
            k["i"], k["o"] = (LIN_I, LIN_O) if linear else (EASE_I, EASE_O)
        keys.append(k)
    return {"a": 1, "k": keys}


# ---- shapes ---------------------------------------------------------------------------------------------------

def ellipse(radius, cx=0.0, cy=0.0):
    return {"ty": "el", "d": 1, "s": vec(2 * radius, 2 * radius), "p": vec(cx, cy), "nm": "Ellipse"}


def rrect(w, h, radius, cx=0.0, cy=0.0):
    return {"ty": "rc", "d": 1, "s": vec(w, h), "p": vec(cx, cy), "r": num(radius), "nm": "Rect"}


def path(points, closed=False):
    zero = [[0, 0] for _ in points]
    return {"ty": "sh", "ks": {"a": 0, "k": {"i": zero, "o": zero, "v": [[r(x), r(y)] for x, y in points], "c": closed}}, "nm": "Path"}


def fill(name, opacity=100):
    return {"ty": "fl", "c": {"a": 0, "k": PLACEHOLDER[name]}, "o": num(opacity), "r": 1, "nm": "Fill"}


def stroke(name, width, cap=2, opacity=100):
    """cap: 1 butt, 2 round."""
    return {"ty": "st", "c": {"a": 0, "k": PLACEHOLDER[name]}, "o": num(opacity), "w": num(width), "lc": cap, "lj": 2, "nm": "Stroke"}


def trim(start, end):
    """start and end are percentages as Lottie properties."""
    return {"ty": "tm", "s": start, "e": end, "o": num(0), "m": 1, "nm": "Trim"}


def group(name, items):
    tr = {"ty": "tr", "p": vec(0, 0), "a": vec(0, 0), "s": vec(100, 100), "r": num(0), "o": num(100),
          "sk": num(0), "sa": num(0), "nm": "Transform"}
    return {"ty": "gr", "nm": name, "it": items + [tr], "np": len(items), "cix": 2, "bm": 0, "ix": 1,
            "mn": "ADBE Vector Group", "hd": False}


def layer(ind, name, shapes, op, pos=(C, C), scale_anim=None, pos_anim=None, rot=None, opacity=None):
    ks = {
        "o": opacity or num(100),
        "r": rot or num(0),
        "p": pos_anim or vec(pos[0], pos[1], 0),
        "a": vec(0, 0, 0),
        "s": scale_anim or vec(SCALE * 100, SCALE * 100, 100),
    }
    return {"ddd": 0, "ind": ind, "ty": 4, "nm": name, "sr": 1, "ks": ks, "ao": 0, "shapes": shapes,
            "ip": 0, "op": op + 1, "st": 0, "bm": 0}     # +1: Lottie hides a layer from its op frame on, and progress 1.0 is the composition op


def composition(name, op, layers):
    return {"v": "5.7.0", "fr": FPS, "ip": 0, "op": op, "w": SIZE, "h": SIZE, "nm": name, "ddd": 0,
            "assets": [], "layers": layers}


def to_comp(x, y):
    """A point in reference pixels (centre 240,240) -> composition pixels after the SCALE shrink."""
    return C + (x - C) * SCALE, C + (y - C) * SCALE


# ---- pieces of the face ----------------------------------------------------------------------------------------

def hour_dots(name="track"):
    items = []
    for h in range(12):
        a = math.radians(h * 30)
        items.append(ellipse(g.DOT_RADIUS, g.DOT_R * math.sin(a), -g.DOT_R * math.cos(a)))
    return group(name, items + [fill(name)])


def hand(length, tail, width, name):
    return group(name, [path([(0, tail), (0, -length)]), stroke(name, width)])


def cap():
    return [group("onaccent", [ellipse(3.5), fill("onaccent")]), group("accent", [ellipse(g.CAP_RED_R), fill("accent")])]


def ring_track():
    return group("track", [ellipse(RING_R), stroke("track", RING_W, cap=1, opacity=30)])


def slot_geometry(slot):
    cx, cy = (slot.x + slot.w / 2) / bf.F, (slot.y + slot.h / 2) / bf.F
    return cx, cy, slot.w / bf.F, slot.h / bf.F


# ---- the four animations ---------------------------------------------------------------------------------------

def welcome():
    """Loop (8 s): the hands sweep, the ring morphs full -> halves -> quarters -> full, the slots pop in and out."""
    op = 240
    times = [(0, 40), (60, 100), (120, 160), (180, 240)]
    layouts = [0, 1, 2, 0]                                  # which of the three (start, end) pairs below: full, halves, quarters
    layout = {                                              # arc -> (start %, end %) per layout
        0: [(0, 100), (0, 48), (0, 23)],
        1: [(50, 50), (50, 98), (25, 48)],
        2: [(50, 50), (50, 50), (50, 73)],
        3: [(75, 75), (75, 75), (75, 98)],
    }
    arcs = []
    for i in range(4):
        s_pts, e_pts = [], []
        for (t0, t1), li in zip(times, layouts):
            s, e = layout[i][li]
            s_pts += [(t0, s), (t1, s)]
            e_pts += [(t0, e), (t1, e)]
        name = "accent" if i % 2 == 0 else "ink"
        arcs.append(group(name, [ellipse(RING_R), trim(anim(s_pts), anim(e_pts)), stroke(name, RING_W, cap=1)]))
    layers = [
        layer(1, "cap", cap(), op),
        layer(2, "second hand", [hand(g.SECOND_LEN, g.SECOND_TAIL, g.SECOND_W, "accent")], op, rot=anim([(0, 0), (op, 1440)], linear=True)),
        layer(3, "minute hand", [hand(g.MINUTE_LEN, g.HAND_BASE, g.HAND_W, "ink")], op, rot=anim([(0, 0), (op, 720)], linear=True)),
        layer(4, "hour hand", [hand(g.HOUR_LEN, g.HAND_BASE, g.HAND_W, "ink")], op, rot=anim([(0, 0), (op, 360)], linear=True)),
    ]
    full = SCALE * 100
    for n, slot in enumerate(bf.SLOTS):
        cx, cy, w, h = slot_geometry(slot)
        t0 = 70 + 8 * n
        pop = anim([(0, [0, 0, 100]), (t0, [0, 0, 100]), (t0 + 20, [full, full, 100]),
                    (215, [full, full, 100]), (235, [0, 0, 100]), (op, [0, 0, 100])])
        shapes = [group("ink", [rrect(w, h, 14), stroke("ink", 3, opacity=80)]),
                  group("accent", [rrect(w, h, 14), fill("accent", 16)])]
        layers.append(layer(5 + n, f"slot {slot.name}", shapes, op, pos=to_comp(cx, cy), scale_anim=pop))
    layers.append(layer(9, "ring", arcs + [ring_track()], op))
    layers.append(layer(10, "dial", [hour_dots("track")], op))
    return composition("onboarding welcome", op, layers)


def pulse(ind, name, op, mid_u):
    """A ring that grows and fades once per loop; mid_u in [0,1) is where in the cycle frame 0 sits."""
    def scale(u):
        k = (0.4 + 1.1 * u) * SCALE * 100
        return [k, k, 100]
    def alpha(u):
        return 100 * (1 - u)
    if mid_u == 0:
        sc = anim([(0, scale(0)), (op, scale(1))])
        al = anim([(0, alpha(0)), (op, alpha(1))])
    else:
        edge = round(op * (1 - mid_u))
        sc = anim([(0, scale(mid_u)), (edge, scale(1)), (edge + 1, scale(0)), (op, scale(mid_u))])
        al = anim([(0, alpha(mid_u)), (edge, alpha(1)), (edge + 1, alpha(0)), (op, alpha(mid_u))])
    return layer(ind, name, [group("accent", [ellipse(90), stroke("accent", 6)])], op, scale_anim=sc, opacity=al)


def searching():
    """Loop (3 s): a sweep and two pulses around a still watch at ten past ten."""
    op = 90
    sweep = group("accent", [ellipse(150), trim(num(0), num(25)), stroke("accent", 14)])
    return composition("onboarding searching", op, [
        layer(1, "cap", cap(), op),
        layer(2, "minute hand", [hand(g.MINUTE_LEN, g.HAND_BASE, g.HAND_W, "ink")], op, rot=num(60)),
        layer(3, "hour hand", [hand(g.HOUR_LEN, g.HAND_BASE, g.HAND_W, "ink")], op, rot=num(300)),
        layer(4, "sweep", [sweep], op, rot=anim([(0, 0), (op, 360)], linear=True)),
        pulse(5, "pulse a", op, 0),
        pulse(6, "pulse b", op, 0.5),
        layer(7, "dial", [hour_dots("track")], op),
    ])


def found():
    """Once (2 s): the ring closes, a disc pops in, a tick is drawn. The last frame is the finished state."""
    op = 60
    ring = group("accent", [ellipse(150), trim(num(0), anim([(0, 0), (30, 100)])), stroke("accent", 14, cap=1)])
    full = SCALE * 100
    disc_scale = anim([(0, [0, 0, 100]), (20, [0, 0, 100]), (40, [full, full, 100]), (op, [full, full, 100])])
    disc = group("accent", [ellipse(112), fill("accent")])
    tick = group("onaccent", [path([(-48, 4), (-14, 38), (52, -34)]), trim(num(0), anim([(0, 0), (34, 0), (52, 100)])),
                              stroke("onaccent", 22)])
    tick_opacity = anim([(0, 0), (33, 0), (34, 100), (op, 100)], linear=True)
    return composition("onboarding found", op, [
        layer(1, "tick", [tick], op, opacity=tick_opacity),
        layer(2, "disc", [disc], op, scale_anim=disc_scale),
        layer(3, "ring", [ring], op),
        layer(4, "dial", [hour_dots("track")], op),
    ])


def permission():
    """Once (5 s): a prompt slides up, the Allow button is tapped, its label becomes a tick."""
    op = 150
    sx, sy = C, C - 10
    slide = anim([(0, [sx, sy + 300, 0]), (24, [sx, sy, 0]), (op, [sx, sy, 0])])
    prompt = [
        group("ink", [rrect(190, 14, 7, 0, -52), rrect(130, 14, 7, -30, -26), fill("ink", 55)]),
        group("accent", [rrect(120, 46, 23, 0, 50), fill("accent")]),
        group("track", [rrect(300, 190, 36), fill("track", 22)]),
        group("track", [rrect(300, 190, 36), stroke("track", 3)]),
    ]
    bx, by = sx, sy + 50 * SCALE                            # button centre; the prompt layer is scaled by SCALE
    label_opacity = anim([(0, 0), (20, 0), (24, 100), (90, 100), (96, 0), (op, 0)], linear=True)
    tick = group("onaccent", [path([(-22, 2), (-8, 16), (22, -14)]), trim(num(0), anim([(0, 0), (96, 0), (118, 100)])),
                              stroke("onaccent", 8)])
    tick_opacity = anim([(0, 0), (95, 0), (96, 100), (op, 100)], linear=True)
    ripple_scale = anim([(0, [0, 0, 100]), (60, [0, 0, 100]), (90, [150, 150, 100]), (op, [150, 150, 100])])
    ripple_opacity = anim([(0, 0), (60, 0), (61, 80), (90, 0), (op, 0)], linear=True)
    return composition("onboarding permission", op, [
        layer(1, "tick", [tick], op, pos=(bx, by), opacity=tick_opacity),
        layer(2, "ripple", [group("onaccent", [ellipse(40), stroke("onaccent", 5)])], op, pos=(bx, by),
              scale_anim=ripple_scale, opacity=ripple_opacity),
        layer(3, "label", [group("onaccent", [rrect(56, 10, 5), fill("onaccent")])], op, pos=(bx, by), opacity=label_opacity),
        layer(4, "prompt", prompt, op, pos_anim=slide),
        layer(5, "watch", [group("track", [ellipse(215), stroke("track", 8, opacity=60)])], op),
    ])


ANIMATIONS = {
    "onboarding_welcome": welcome,
    "onboarding_searching": searching,
    "onboarding_found": found,
    "onboarding_permission": permission,
}


def build_all():
    return {name: make() for name, make in ANIMATIONS.items()}


def render(doc):
    """The exact bytes written to disk: compact, stable key order, trailing newline."""
    return json.dumps(doc, separators=(",", ":"), ensure_ascii=True) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--out", type=Path, default=OUT)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    for name, doc in build_all().items():
        path_ = args.out / f"{name}.json"
        path_.write_text(render(doc), encoding="utf-8")
        print(f"{path_}  {path_.stat().st_size} bytes")


if __name__ == "__main__":
    main()
