# Builder POC: user scenarios and where each is tested

Index of what a person can do with the builder POC (phone app + watch app) and which automated test protects it.
Plan: `docs/superpowers/plans/2026-10-08-builder-poc.md`. Spec: `docs/superpowers/specs/2026-10-08-builder-app-design.md`.
This is a coverage catalogue, not a queue item.

Actor: the owner (one person with a phone and a Wear OS 6 watch). There is no other role in the POC.

Test layers: **JVM** = `./gradlew testDebugUnitTest`; **Phone UI** = Compose tests on the phone emulator
(`:phone:connectedDebugAndroidTest`); **Device** = instrumented tests on an emulator (`:facegen`, `:packjni`, `:watch`);
**E2E** = `scripts/poc_e2e.sh` (real apps on a phone and a watch emulator, real Google validator and Watch Face Push,
screenshots in `docs/screenshots/poc/`; the phone-to-watch hop is an adb relay, see "Not covered").

| # | Scenario | Covered by |
|---|---|---|
| 1 | Open the app: the default design looks like today's face; the preview is always visible | Phone UI `EditorTest`, `AppearanceTest`; JVM `DesignTest` (default valid, templates valid); E2E screenshot 1 |
| 2 | Type any hex colour for the accent (and the other colours): the preview and the design change | Phone UI `EditorTest.typingAHexAccentChangesThePreview`, `AppearanceTest` |
| 3 | Type an invalid colour (`red`, `#fff`, spaces): a clear message, Send is disabled, fixing it enables Send; pasted spaces are trimmed | Phone UI `EditorTest.typingRedShowsTheMessageAndDisablesSend`, `AppearanceTest`; JVM `DesignTest.badColoursAreRejected`, `FieldHelpersTest` |
| 4 | Dial values: tick width and length, minute ticks, five-minute dots, numerals (all / quarters / off), text and title sizes, titles and icons on or off; typed numbers out of range are flagged, not crashed | Phone UI `AppearanceTest`; JVM `FieldHelpersTest`, `DesignTest.numericRanges`, `CoreXmlTest` |
| 5 | Hands and second hand: colours, style (sweep / tick / hidden), "use accent" | Phone UI `AppearanceTest`; Device `RenderTest` (hand images); JVM `CoreXmlTest` |
| 6 | Background: solid, gradient, photo (resized, EXIF-rotated, 350 KB cap), dim, remove photo; huge or odd photos do not crash | Phone UI `AppearanceTest`; Device `RenderTest`, `PackagerTest`; JVM `RenderMathTest`, `FieldHelpersTest` |
| 7 | Always-on mode: background hidden, dial dimmed; dark hands, ticks and numerals switch to light colours; a hint says so | JVM `AmbientXmlTest`, `DesignHintsTest`, `CoreXmlTest`; Device `RenderTest` (ambient hand images). Visual check on a real watch: see "Not covered" |
| 8 | Complications: drag, nudge, resize, add and remove slots (0 to 6), shapes, backgrounds, colours; overlaps, the hand centre and the dial edge are refused; the cap indicator shows "N of 8"; a numeral-overlap note | Phone UI `ComplicationsTest`, `EditorTest`; JVM `SlotGeometryTest`, `SlotRingXmlTest`; Device `PackagerTest` (odd slot sizes pass the Google validator) |
| 9 | Rings: 1, 2, 3 or 4 segments, colours, track, thickness, gap, start angle, caps, direction; layouts that would pass 8 complications are disabled | Phone UI `ComplicationsTest`, `EditorTest`; JVM `SlotRingXmlTest`, `RenderMathTest`; E2E screenshot 3 |
| 10 | Templates (Classic, Midnight, Neon, Minimal): applying one asks before replacing the design; save, load and delete my own designs | Phone UI `ShareTemplatesTest`, `EditorTest`; JVM `StoreTest` |
| 11 | Share a design: copy or share a link, paste a link, tap a link (asks before replacing), show a QR code, scan a QR code (asks before replacing); bad links give a message | Phone UI `ShareTemplatesTest`, `QrSectionTest`; JVM `QrCodeTest`, `LinkDialogTest`, `DesignTest` (share links) |
| 12 | Send to watch: the face is built on the phone, validated by Google's validator, version number goes up, every stage and error is shown, one send at a time | JVM `SendControllerTest`, `SenderTest`, `StoreTest`; Device `PackagerTest`, `PackNativeTest`; E2E (build + validate + apply for three designs) |
| 13 | The watch installs the face, then only updates it in place; a lower version is refused; never removed; activation is requested once; a denied permission is explained | Device `watch PushControllerTest` (real Watch Face Push); JVM `PushControllerLogicTest`, `PermissionsTest`, `ApplyFlowTest`; E2E (add then update, one face installed, version equals the last send) |
| 14 | The face on the watch looks right (dial, numerals, hands, complications, ring segments, moved slots) | E2E screenshots 1 to 3 (read by eye and pixel-checked for the accent colour) |
| 15 | Quit and reopen the app, or reinstall it: the design is restored; version numbers never repeat or go down | JVM `StoreTest` (counter never repeats, clock-based floor after a reinstall, corrupt preferences, photo kept separately) |
| 16 | The signing key is lost or damaged: the app says why updates are refused and how to recover; "Reset signing key" asks first | JVM `SendControllerTest`, `StoreTest`; Device `PackagerTest` (damaged key); Phone UI `ShareTemplatesTest`; JVM `PushControllerLogicTest` (the watch's own hint on a failed update) |
| 17 | Hostile input: huge share links, thousands of slots or colours, 2,000-character error values, control characters in names | JVM `DesignTest`, `FieldHelpersTest`, `QrCodeTest`; Phone UI `EditorTest`, `ComplicationsTest` |

## Not covered, and why

- **The phone-to-watch transfer over the Wear Data Layer (nodes, channel, apply message, reply).** It needs a phone and
  a watch that are really paired. The two emulators here cannot be paired: the phone emulator has no Wear OS companion
  app and installing it needs a Google account sign-in, which an automated run must not do (checked 2026-10-08). The
  logic around it (ordering, timeouts, node choice, file and message pairing by version) is unit-tested with fakes, and
  the E2E relays the same APK by adb. The owner's real-device checklist is in `docs/poc/README.md`. If it is later paired
  once by hand, a small instrumented test around `DataLayerSender` is the missing piece.
- **Scanning a QR code with the camera** (Google code scanner needs a camera and Play services). Showing the code and
  the decode round trip are tested.
- **Choosing complication data in the system editor (Galaxy Wearable), and what real providers deliver.** The emulator
  has a few providers. Ring value arcs with real data and ring text were not seen on a real watch.
- **Always-on mode as seen on the real watch**, and the face on the real SM-L315F in general. Only the Wear OS emulator
  renderer was observed.
- **Process death mid-apply on the watch** (Wear OS freezing or killing the app while it installs): inferred risk, not
  reproducible on an emulator.
- **Photo picker and system share sheet UIs**: the bytes and intents are tested, not the system screens.
