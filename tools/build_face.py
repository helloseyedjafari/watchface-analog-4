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

# Ambient (always-on) brightness of the complications; the red cap is drawn unchanged
# (dimming it with alpha blends the red into the white ring and turns it pink).
AMBIENT_SLOT_ALPHA = 170


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

WEATHER_COMPONENT = ("com.samsung.android.watch.weather/"
                     "com.samsung.android.watch.weather.complication.WeatherComplicationService")

SLOTS = [
    Slot(0, "top", 150, 106, 150, 76, WHITE, WHITE,
         f'<DefaultProviderPolicy primaryProvider="{WEATHER_COMPONENT}" primaryProviderType="SHORT_TEXT" '
         'defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY"/>'),
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
      <Variant mode="AMBIENT" target="alpha" value="{AMBIENT_SLOT_ALPHA}"/>
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
