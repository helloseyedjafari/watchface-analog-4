# Analog watch face with four complications — design

Date: 2026-10-02
Status: APPROVED 2026-10-02. Amended the same day after prototyping the dial, hands and face XML
against Google's WFF validator and the emulator (version floor, measured slot positions, second
hand as an image, weather note).

## Goal

A personal Wear OS watch face that looks like the face currently on the owner's Galaxy Watch
(Samsung's stock analog face, `com.samsung.android.watch.watchface.simplebasic`), but with four
complication slots instead of two. It must adapt to any Wear OS screen size and shape, and every
complication must be individually hideable.

Reference capture of the original, taken from the real watch (SM-L315F, 480×480 round, 340 dpi,
Wear OS 6 / SDK 36): `docs/reference/original-watchface.png`.

Non-goals: Play Store listing, phone companion app, any custom rendering code.

## Original face, as observed

- Black background.
- Dial: numerals 1–12 in a light, Samsung-style font; some numerals are tilted tangentially
  (10, 11, 1, 2, 7, 8), 12/3/6/9 are upright. 12 dots on the hour positions and 60 minute ticks
  on the outer ring.
- Hour and minute hands: white outline, grey inlay, pointed tip.
- Second hand: thin red line with a red centre cap on a white ring.
- Two complications: weather at the top (icon, temperature, condition text) and battery at
  the right (red icon, grey percentage).

## Decisions

| Decision | Choice | Why |
|---|---|---|
| Format | Watch Face Format (declarative XML), `hasCode="false"` | Percent-based layout scales to every screen; system-rendered, so cheap on battery and AOD-safe; Google's current format |
| Format version | WFF v3, so the floor is Wear OS 5.1 (API 35) | Auto-sizing text (`isAutoSize`) needs v3; the validator rejects it on v1 and v2. The owner's watch runs Wear OS 6. A manifest declares one version, so this is the floor for the whole face. |
| Complication slots | 4 (top, right, left, bottom) | Fits the dial without colliding with numerals and hands |
| Hiding | Per-slot, via the system's "Empty" data source | No custom code; the slot draws nothing |
| Testing | Android emulator profiles, not only the real watch | Proves the sizing claim on real shapes |
| Project location | `~/Documents/projects/analog-watchface`, a sibling of `prayertime-widgets` | Requested; separate repo, separate lifecycle |
| Package | `com.seyedjafari.analogwatchface` | Matches the existing package style |

## Architecture

A single Gradle Android application module that ships XML and resources only.

```
analog-watchface/
  app/
    build.gradle.kts
    src/main/
      AndroidManifest.xml        # hasCode=false, WFF version property, watch feature
      res/
        raw/watchface.xml        # the face definition
        drawable-nodpi/          # dial, hour hand, minute hand (high-resolution PNG)
        xml/watch_face_info.xml  # preview, editor metadata
        values/strings.xml       # slot names shown in the editor
  docs/
    reference/original-watchface.png
    screenshots/<avd-name>.png   # per-emulator captures, committed
    superpowers/specs|plans/
```

### Layout units

All geometry is in the format's 450 x 450 reference space (centre 225, 225), which the system
scales to the real screen, so it behaves like percent-of-screen. The dial image and hands are
authored at 4 px per reference pixel (a 1920 px dial) and scaled by the system. Nothing is
positioned in device pixels.

### Dial

One pre-rendered image: numerals, hour dots and minute ticks. This keeps the original's
tangential numeral tilt exact and avoids font differences between devices. The font used for
authoring is an open-licence face chosen to match the original as closely as possible; exact
parity is limited by the original being a Samsung system font.

### Hands

- All three hands are images (the format's `SecondHand` requires an image resource, so a built-in
  line shape is not an option), rotated by the format's clock logic. They are authored at 4 px per
  reference pixel so they stay sharp on larger screens. Hand geometry must be whole numbers in the
  450-unit space, so the pivot is derived from the rounded box.
- The centre cap (white ring, red disc, black dot) is vector shapes, so it stays crisp at any size.
  The red disc and dot are hidden in ambient mode.
- All of it draws above the complications, as on the original.

### Complication slots

Positions are measured from the reference capture. Centres in percent of the screen, boxes in the
format's 450-unit space:

| Slot | Centre | Box (x, y, w, h) | Default data | Fallback if unavailable |
|---|---|---|---|---|
| Top | 50 / 32 | 150, 106, 150, 76 | Weather (see open items) | Empty |
| Right | 72 / 61 | 277, 236, 90, 76 | Battery (red icon, grey text) | System battery provider |
| Left | 28 / 61 | 83, 236, 90, 76 | Next prayer (`com.seyedjafari.prayertimes` `NextPrayerComplicationService`) | Empty |
| Bottom | 50 / 75 | 175, 300, 100, 76 | Step count | System step provider |

- Supported types: short text, ranged value and small image, so the user can pick any provider
  the watch offers.
- All slot bounds sit inside the circle touching the numerals.
- Text uses the format's fit-to-box sizing, so long values shrink instead of overflowing.
- Defaults are only starting points. The user can change any slot to any provider.

### Hiding complications

Each slot offers the system "Empty" data source in the face editor. An empty slot renders
nothing: no placeholder, no background and no tap target. Nothing reflows, because the dial is
fixed. With all four hidden the face is a plain analog dial like the original without its
complications. The choice persists per slot.

### Always-on display

Hands and ticks only, dimmed. Complications are hidden or reduced to monochrome and low
brightness to save battery and avoid burn-in.

### Adaptivity

- The format scales percent geometry to the physical screen.
- Round screens show the full dial.
- Square and rectangular screens show the same dial centred on black. Because all content is
  inside the inscribed circle, nothing is clipped.
- A larger display scales the dial image up, so the PNGs are authored at 4 px per reference pixel to stay sharp
  above 480 px.

## Testing

1. AVDs on the Wear OS API 36 image, created from Android Studio's Wear profiles:
   `wearos_small_round`, `wearos_large_round`, `wearos_square`, `wearos_rect`, plus the existing
   `GalaxyWatch7_API36` (480×480, matches the owner's watch).
2. For each AVD: install, set as the active face, capture a screenshot into
   `docs/screenshots/<avd>.png`.
3. Checks per AVD:
   - nothing clipped, nothing overlapping the numerals;
   - hands pass over the slots;
   - each slot shows its default data;
   - hiding each slot, and all four, leaves the dial intact;
   - ambient (always-on) rendering.
4. Visual comparison of the 480×480 AVD capture against `docs/reference/original-watchface.png`.
5. Final: sideload to the real watch over wireless debugging and capture again.

## Open items for the implementation plan

These are facts to check, not design questions:

- **Weather default.** WFF has no system weather *complication* provider (v2 added weather as a
  built-in data source, which is not a complication and could not be hidden per slot). The Wear
  emulator has no weather complication service either. The plan finds the Samsung Weather
  complication component on the real watch and uses it as the default provider if it can be
  addressed that way. Otherwise the slot defaults to Empty and the owner picks weather in the
  editor once.
- **Next-prayer default** only works when the prayer-times Wear app is installed on the same
  watch. Otherwise the slot is empty.
- **Samsung's editor.** Confirm on the real watch that "Empty" appears in Samsung's complication
  picker, and that the slot's default does not return after reboot.
- **Watch connection.** The wireless-debugging session to the real watch dropped after the
  initial screenshot and needs to be re-paired before the sideload step.
