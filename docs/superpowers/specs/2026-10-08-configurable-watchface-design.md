# Configurable watch face (Analog Plus settings) — design

Date: 2026-10-08
Status: ON HOLD 2026-10-08. The owner chose a builder app (onboarding, free values, Watch Face Push)
instead of this code-free configurable face. The settings catalogue below stays the input for the
builder spec. Revive this spec only if the push-API spike fails on the owner's watch.
Parent: `2026-10-02-analog-watchface-design.md` (the original face). That spec is not edited; where
this one changes a decision from it, the change is listed under "Changes to the original spec".

## Goal

Turn the fixed Analog Plus face into a configurable one, so it can grow into a polished app that
can later be listed on the Play Store. Every new option is an extra setting in the watch's
*Customize* editor. **The default configuration must look exactly like today's face.**

Success criteria:

1. A fresh install renders the same face as the current release (checked by an image comparison
   test, see Testing).
2. Each setting in the catalogue below works in the editor and in always-on mode.
3. The face stays code-free (`hasCode="false"`, XML plus images).
4. It validates against Google's `wff-validator` and runs on the emulator matrix and the owner's
   Galaxy Watch (Wear OS 6).

## Scope

In: accent colours, backgrounds (solid, gradient, bundled texture, user photo), dial and tick
styling, numerals, hands and cap, complication styling, ambient options, presets.

Out: a phone companion app (revisit only if the editor cannot do something we need); the Play Store
listing work itself, which is a separate track (see "Store readiness"); changes to which complication
providers are the defaults.

## Changes to the original spec

| Original decision | Now | Why |
|---|---|---|
| WFF v3, minimum Wear OS 5.1 (API 35) | **WFF v4, minimum Wear OS 6 (API 36)** | User-selected photos need WFF v4. v4 also adds ambient transitions and colour tinting of grouped elements. The owner's watch and the dev emulators already run Wear OS 6. Watches still on Wear OS 5.x are dropped. |
| Dial is one baked image (numerals, dots, ticks) | **Ticks are generated vector lines; numerals are 12 tintable images** | A baked PNG cannot change tick thickness, length or colour. |
| Non-goal: Play Store listing | **Now a goal, as a separate track** | Owner decision 2026-10-08. |

## Architecture

The generator pattern stays: `tools/build_face.py` writes `watchface.xml`, `tools/gen_assets.py`
writes the images, and the committed outputs must equal what the generators produce (existing test).

`build_face.py` is refactored around a **settings model**: a list of setting definitions (id, label,
options, default). From it the generator emits both the `<UserConfigurations>` block and the elements
that read each setting, so a setting's declaration and its use cannot drift apart. Labels live in
`strings.xml`.

Rules for how settings are consumed (WFF facts confirmed from Google's docs):

- A colour setting is a `ColorConfiguration`; its colour is read as `[CONFIGURATION.<id>]`
  (or `.0`, `.1` when an option holds several colours) in `tintColor` and `Fill color`.
- A choice setting is a `ListConfiguration`; the selected option id is compared as a string, for
  example `[CONFIGURATION.tick_width] == "1"`.
- A toggle is a `BooleanConfiguration`.
- Configuration elements cannot be nested inside each other.
- Where a numeric attribute cannot take a configuration expression, the generator emits one prepared
  variant per option and shows only the selected one. The plan for each piece checks which applies.
- Presets are the format's **Flavors**, which are named bundles of setting values.

## Settings catalogue

Defaults reproduce today's face.

| Group | Setting | Options (default first) |
|---|---|---|
| Colour | Accent | Red, orange, yellow, green, teal, blue, purple, pink, white. Drives the second hand, the centre disc and the battery icon. |
| Background | Style | Black, solid colours, gradients, bundled textures, photo |
| Background | Photo change | Never, on tap, on wake, tap and wake (only with Photo) |
| Background | Photo dim | 0%, 30%, 50% (keeps hands and complications readable) |
| Dial | Tick width | Normal, thin, thick |
| Dial | Tick length | Normal, short, long |
| Dial | Minute ticks | On, off |
| Dial | 5-minute markers | Dots, bars, off |
| Dial | Tick colour | Current grey, white, accent |
| Numerals | Numerals | All 12, quarters only (12, 3, 6, 9), off |
| Numerals | Numeral colour | White, accent, grey |
| Hands | Hand colour | White, accent, grey |
| Hands | Inlay | On, off |
| Hands | Second hand | Sweep, tick, hidden |
| Hands | Centre cap | Current style, plain, ring only |
| Complications | Text colour | White, accent, grey |
| Complications | Titles | On, off |
| Complications | Icons | On, off |
| Complications | Progress arcs | Off, on (ranged-value providers) |
| Ambient | Dimness | Current, darker, lighter |
| Ambient | Complications | Visible, hidden |
| Presets | Flavor | Classic (default), Midnight, Light, and a few more chosen during that plan |

The low-battery warning (battery icon turns red below a threshold) is built in and has no setting.

The number of settings makes the editor long. The generator orders them into pages by group, and
presets are the quick path for most users.

## Backgrounds in detail

- **Solid and gradient** are drawn as a full-screen shape filled from the setting. No image needed.
- **Bundled textures** are a few small tiling or full images. Memory is checked per image.
- **User photo** uses one `PhotosConfiguration` (`configType="MULTIPLE"`). The format allows only one
  photo setting per face. To offer both a fixed photo and a cycling gallery, the face has four
  `Photos` layers, one per *Photo change* choice (never, tap, wake, tap and wake), and only the
  selected layer is visible. A default image is required by the format and is the plain black dial
  background.
- The photo layer is hidden in ambient mode (black behind the dial) to protect the display from
  burn-in and to save power.
- A dark overlay of the chosen *Photo dim* strength is drawn above the photo, below the dial.

## Always-on mode

Unchanged by default. Ambient dimness and complication visibility become settings. Photo, textures
and gradients do not appear in ambient. The red centre disc rule from the original spec stays the
default.

## Memory and performance

Google's Wear OS quality guidance sets budgets of 100 MB (interactive) and 10 MB (ambient) for
assets. Each plan must check the images it adds against these. The vector tick approach is chosen
partly because it adds no image memory. The size of the generated XML (tick variants) is also
checked on the emulator for rendering cost.

## Testing

- **Default parity:** render the default face through the generator's compositing path and compare it
  against the current release image within a tight tolerance. This protects the "default stays
  exactly as is" rule.
- **Generator tests:** extend `tests/test_face.py` so every setting has a default, every option
  resolves to a valid element, and the committed XML equals the generated XML.
- **Validator:** `scripts/validate.sh` runs against WFF v4.
- **Emulator matrix:** the existing round, large round, square, rectangular and Galaxy Watch 7
  profiles, now API 36 only. For each piece, screenshots of every option of the new settings.
- **Real watch:** the owner's Galaxy Watch (SM-L315F, Wear OS 6) for the editor and the photo picker.

## Plan breakdown

Each is its own plan, in this order.

0. **Spike (throwaway).** A minimal v4 face with one colour setting, one list setting and one photo
   setting, sideloaded to the real watch. It answers: does Samsung's editor show all three; does the
   photo picker work; does a `MULTIPLE` photo setting with a single photo and no `change` behave as a
   fixed photo; does a configuration expression work in a numeric attribute. Output is an answer and a
   recommendation. The spike face is not kept.
1. **Foundation.** Wear OS 6 and WFF v4 (`minSdk`, manifest, CI, validator version), the settings
   model in the generator, default-parity test, accent colour, solid and gradient backgrounds.
2. **Dial.** Vector ticks, tick styles, numerals.
3. **Hands and cap.**
4. **Complication styling.** Includes progress arcs and the low-battery warning.
5. **Photo background.**
6. **Ambient and presets.**

## Store readiness (separate track, not planned here)

Signing and release workflow exist already. Still needed before a listing: store graphics and
screenshots, privacy policy, Wear OS form-factor setup in Play Console, and a decision on how
distinct the default look is from Samsung's stock analog face, which the dial is measured from.
Forum reports show clones of official faces do get reported on Google Play; no case involving a
generic analog face was found. Google's account and testing requirements for new developers should
be re-checked on Google's own pages at that time.

## Risks

| Risk | Handling |
|---|---|
| Samsung's editor does not support photo or colour settings the way Google's docs describe | Spike (plan 0) before building on them. |
| Fixed-photo behaviour of a `MULTIPLE` setting is not as assumed | Spike. Fallback: offer only a single photo, or only cycling. |
| Too many settings overwhelm the editor | Pages by group, presets as the quick path; drop settings that prove awkward. |
| Memory budget exceeded by textures or numeral images | Per-plan check; vector rendering where possible. |
| Dropping Wear OS 5.x watches limits the audience | Accepted by the owner; re-check device coverage in Play Console before listing. |
