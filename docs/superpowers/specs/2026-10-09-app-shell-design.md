# Phone app shell and design system — design

Parent: `2026-10-08-builder-app-design.md` (the builder). Siblings: `2026-10-09-onboarding-design.md`,
`2026-10-09-accounts-sync-design.md`. Build order: this spec first, then onboarding, then accounts.
Status: DRAFT, awaiting owner review (2026-10-09).

## Intent (agreed with the owner)

The phone app is a paid (£4.99) product. Today it is a working POC with eight tabs and default Material styling.
It must look finished and professional. Owner decision: **full visual redesign plus a restructured navigation**, not
a re-skin.

Success: a first-time user can find "design", "my designs" and "send to watch" without being told; every screen has
designed empty, loading and error states; the live preview is always visible while editing; light and dark themes both
look deliberate.

## Navigation

Bottom navigation, four destinations (replaces the eight-tab `EditorScreen`):

| Destination | Contains | Replaces |
|---|---|---|
| **Design** | Live preview hero (pinned top) and an editor below it with six sections: Colours, Dial, Hands, Background, Complications, Rings | the six editing tabs |
| **Library** | Templates (built in), My designs (saved), Share (link, QR, load from link) | Templates and Share tabs, the saved-designs list |
| **Send** | Watch status (watch paired, Analog Plus installed on it, "needs a Wear OS 6 watch" note; the phone cannot read the watch's OS version), the Send button, last result and failure reasons | the send bar and its messages |
| **Account** | Sign-in, sync status, recovery passphrase, delete account, app info | new (spec 3 fills it; until then it shows app info and a "sign in comes later" state) |

- Design sections are a horizontally scrolling chip row under the preview (not tabs), with a shared-axis slide
  between sections. Preview stays put.
- A compact **Send** action is also reachable from Design (it navigates to Send and starts the send). One
  `SendController`, no second send path.
- Back behaviour: back from a non-Design destination returns to Design, back from Design exits.
- Deep links (`analogplus://design?d=...`) and scanned QR codes open Library > Share's confirm dialog regardless of the
  current destination (existing `pendingLinkDesign` flow, unchanged).

## Design system

- **Theme object** `AnalogTheme` wraps `MaterialTheme`: colour roles, type scale, shapes, spacing tokens (4 dp grid),
  elevation, motion tokens (durations and easings). No raw colours, text sizes or dp literals in screens.
- **Colour:** one accent (the brand blue from the face), neutral surfaces for light and dark, system dark mode followed.
  Dynamic colour (Material You) is **off** so the brand is consistent. Contrast checked to WCAG AA for text.
- **Type:** one family bundled in the app (a font with tabular numerals for colour/number fields); scale of
  display / title / body / label.
- **Components** (own wrappers, one file each, previewable): `SectionCard`, `SectionChips`, and restyled versions of the existing `ColourField` (swatch + hex, logic kept),
  `IntField` (number + slider), `ChoiceRow` and `SwitchRow`, `PrimaryButton`/`SecondaryButton`, `StatusPill`, `EmptyState`,
  `ErrorBanner`, `Skeleton`. The existing validation (`FieldHelpers`, `ColourField` parsing, clamping) is reused, not
  rewritten.
- **Motion:** shared-axis transitions between destinations and Design sections, animated preview on value change
  (hands/ring/colour crossfade, 200-300 ms), press feedback, skeleton shimmer while the photo loads. All animations
  respect the system "remove animations" setting (duration scale 0 -> snap).
- **Icons:** Material Symbols outlined, one set.
- **Accessibility:** touch targets 48 dp, content descriptions on every icon-only control, TalkBack order follows
  visual order, text scales to 200 % without clipping, no information carried by colour alone. One owner-approved
  exception (2026-10-09): the four bottom-navigation labels stop growing at 1.3x so "Account" stays on one line; all
  screen content still scales to 200 %.

## States

Every screen defines: empty (e.g. no saved designs), loading (photo being read, send in progress), error (invalid
colour, send failure with the reason and one recovery action) and success. Messages use the existing failure reasons
from `SendController` and recovery advice already in the app (for example uninstalling the watch app does not remove
the face).

## Code structure

- `ui/theme/` (`AnalogTheme`, tokens, type), `ui/components/` (list above), `ui/nav/` (`AppNav`, destinations),
  `ui/design/`, `ui/library/`, `ui/send/`, `ui/account/`.
- Existing files move rather than get rewritten: `ColoursTab.kt` -> `ui/design/ColoursSection.kt`, and so on;
  `EditorState`, `Store`, `SendController`, `Sender`, `SlotDragEditor`, `PreviewPane` logic stay as they are. Only the
  UI layer changes.
- `MainActivity` hosts `AppNav`; the theme moves out of it.
- Navigation is plain state (enum, `rememberSaveable`, `BackHandler`, `AnimatedContent`); Navigation Compose is not used, because there are four flat destinations. The Compose BoM stays as is.

## Testing

- JVM tests (`StoreTest`, `SendControllerTest`, `FieldHelpersTest`, `SlotGeometryTest`, ...) must stay green
  untouched; they cover logic that does not move.
- Instrumented UI tests (`EditorTest`, `ComplicationsTest`, `AppearanceTest`, `ShareTemplatesTest`, `QrSectionTest`)
  are updated for the new navigation. Their test tags (`Tags.kt`) are kept where the control survives, so the
  assertions mostly stay.
- New: navigation test (each destination reachable, back behaviour), theme test (light and dark render, no
  hard-coded colours via a lint-style grep), accessibility checks (content descriptions present), screenshot set for
  light and dark per destination refreshed in `docs/poc`.
- Visual verification on the phone emulator and the owner's phone, with screenshots.

## Out of scope

Tablet/foldable layouts beyond "does not break" (single-pane stretch), localisation (strings are moved to resources so
it is possible later), the Wear OS watch app UI.

## Open items

None blocking. The font family is chosen at plan time (a licensed-free option bundled in the app).
