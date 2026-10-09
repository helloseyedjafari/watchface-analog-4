# Builder app: session handoff (2026-10-08)

Everything a new session needs to continue. Read `CLAUDE.md` first, then this, then the spec and plan.

## Where we are

- **Direction decided:** build a **paid (£4.99) phone app + watch app** that lets users build their own Wear OS 6 face with free values (any hex colour, numbers), movable complication slots, ring complications and photo backgrounds. The phone generates and signs the face; Watch Face Push puts it on the watch.
- **Spec:** `docs/superpowers/specs/2026-10-08-builder-app-design.md` (draft, effectively approved for the POC).
- **Plan:** `docs/superpowers/plans/2026-10-08-builder-poc.md` (10 tasks). **Update 2026-10-08 (later): implemented and merged to `main`, not pushed.** Run and test it with `docs/poc/README.md`; test map in `docs/superpowers/scenarios/`. Not verified on real devices: the Data Layer transfer (the emulators cannot be paired), ring data/text with real providers, always-on look. Decisions made during the run: no bundled default face on the watch (phone-only), QR show+scan added, Light template dropped with ambient inversion for dark designs, ring defaults battery + steps (text), ring gaps centred on the start angle. Finding: uninstalling the watch app leaves the pushed face installed. **Update 2026-10-09: the phone app's eight-tab editor was replaced by a four-destination shell (Design, Library, Send, Account; plan `docs/superpowers/plans/2026-10-09-app-shell.md`); the signing-key reset now lives in Account; screenshots in `docs/poc/screens/`.** The owner was asked to review it and reply "go". On "go": do Task 1 (scaffold) inline, then dispatch Sonnet implementers in parallel worktrees per the global `CLAUDE.md` flow, read every diff, merge to `main`, delete worktrees.
- **On hold:** `docs/superpowers/specs/2026-10-08-configurable-watchface-design.md` (code-free face with fixed options). It is the fallback if the builder cannot ship.
- **Original face:** `app/` (+ `tools/*.py`, `tests/`) is the working code-free Analog Plus face. Do not break it; the Python generators are the reference for geometry.

## Owner decisions (in order)

1. Wants many options, free values, onboarding, movable complications, ring complications (1 full, 2 halves, 3 thirds, 4 quarters), backgrounds including photos and later animation.
2. Minimum **Wear OS 6** accepted. Builder first; skip the code-free version (kept as fallback).
3. Default face must exist out of the box (bundled default; works without the phone app) plus the normal system customisation (complication pickers in Galaxy Wearable).
4. Use Google's **`pack`** (native, on the phone) for maximum flexibility. Recolour the second hand (regenerate PNG).
5. Extra features after market research: analog/digital switch and several clocks (v1), templates and own faces (v1), share a design as link/QR (v1, no backend), animated backgrounds (after a spike), community gallery (later), web builder (later).
6. **Price £4.99 paid upfront.**
7. POC scope: everything (core look + movable slots + rings), in this repo as new modules, "shouldn't take more than a day".

## What the spikes proved (throwaway code in `docs/poc/spike/`)

| Fact | Detail |
|---|---|
| Push works on the owner's Galaxy Watch (SM-L315F, Wear OS 6, Android 16) | list, add, activate, in-place update, correct rendering incl. complications |
| 1 slot per app; activation is one-shot | `setWatchFaceAsActive` once; after remove + re-add it failed ("maximum number of attempts"). Never `removeWatchFace`; only update. Reinstalling the watch app reset it on the emulator. |
| Update keeps face active | needs strictly higher `versionCode` (lives in the manifest) |
| Validator | `validator-push-cli` and `validator-push-android` `1.1.0-alpha01` on Google Maven; token required. Android library needs phone minSdk 28. Call: `DwfValidatorFactory.create().validate(apkFile, "<marketplace package>")` |
| APK content rule | only manifest, `resources.arsc`, `res/**`, `META-INF/**`. A Gradle build adds an empty `classes.dex` (fails). `res/xml/watch_face_info.xml` is **required** (without it: "could not be parsed - Resource ID #0x0" and activation error code 1). |
| Naming and signing | package `<app>.watchfacepush.<name>`; sign the face with a different key from the app; updates need the same signer |
| `pack` (github.com/google/pack, commit 7b60931) | builds, aligns and signs a face APK from a text manifest and `res/<type>/<files>`; cross-compiled to aarch64 Android; ran on a phone emulator in 0.03 s; output passed the validator 10/10 and installed and activated on a Wear OS 6 emulator. Needs a persistent PEM (cert + key); its random key is slow. |
| Phone-side build without pack | template patching also worked (patch XML + `versionCode` sentinel, 4-byte aligned re-zip with commons-compress, apksig with an Android Keystore key), but adding new resources needs pack |
| Second hand | regenerate `second.png` in the accent colour (done in the spike with Pillow; do it with `android.graphics` in the app) |
| Not proven | real Data Layer transfer phone → watch; arc/ring rendering (BoundingArc + sweep expressions); complication choices surviving a face update; `pack` as a JNI library inside an app (Google's `pack-from-java` example exists); animated backgrounds |

## Constraints worth remembering

- Max **8 complication slots** per face in total (Google reference); ring segments count.
- Complication provider choice happens in the system editor (Galaxy Wearable), not in the phone app.
- Colours must be `#AARRGGBB`. Clamp ranged values to min and max before drawing (out of range crashes).
- APK travels over Bluetooth: keep small (photo background capped at 350 KB in the plan). Memory: 100 MB interactive, 10 MB ambient.
- Wear OS 6 only: Pixel Watch 2/3/4, Galaxy Watch 4–8/Ultra/FE, Xiaomi Watch 5 reported eligible (Oct 2025 reports); not Wear OS 5 and older, not iPhone. Original Pixel Watch never gets Wear OS 6. Wear OS 7 is rolling out (announced 2026-05-19).
- Wear Data Layer needs the same application id on phone and watch. Planned id: `com.seyedjafari.analogplus` (face: `com.seyedjafari.analogplus.watchfacepush.analog`). Not yet verified in practice.

## Market research verdict (`docs/research/Customizable Wear OS watch face market.md`)

Build it as a small, time-boxed bet, not a business plan. Builders exist (Facer 10M+, TIMEFLIK 10M+, WatchMaker 5M+, Pujie 100K+ and AllFaces 50K+) but no source documents the combination of free values, user-chosen ring splits and movable slots; this is a gap in documentation, not proof (nobody tested the competing editors hands-on). Google's Androidify app uses the same architecture; the nearest open-source rival (`miopea/bfg-watchfaces`) is early. No policy blocker found (permission level and Play review of push apps unconfirmed). Wear OS audience is flat; no income data for indie faces; Pixel Watch 5 has a limited AI "Create" feature. Biggest user complaints: lost faces after the move to WFF, complications reset on face switch, billing. Validate first: hands-on competitor audit, device spike on Galaxy and Pixel watches, closed-test upload, demand test. Competing with Facer's catalogue/community is the weakest bet.

## Gotchas hit in this session

- Gradle's file watching misses edits under `/private/tmp` (stale builds): set `org.gradle.vfs.watch=false` there, and check the APK is newer than the source.
- The push library needs **AGP 8.9.1+** (Gradle 8.11.1). The existing `app/` uses AGP 8.7.2 / Gradle 8.10.2.
- Wireless debugging to the watch dropped many times; serial looks like `adb-RFAY307S1VJ-nvYsDx (2)._adb-tls-connect._tcp` (quote it). Scripts should wait for the device.
- macOS has no `timeout`. `adb shell am start --es x '#ff...'` needs the value quoted inside the remote command (`#` is a comment).
- Kotlin: `"${1}"` is a template evaluating to 1, not a regex group; use a lambda in `Regex.replace`.
- Emulators: `GalaxyWatch7_API36` (watch, port 5560) and `1_Pixel_8_API_33` (phone, Play image, port 5562). They are not paired, so the Data Layer cannot be tested between them.
- Rust was installed under `~/.cargo` and `~/.rustup` (no shell profile changes). `pack` built with `CC_aarch64_linux_android` and linker env vars pointing at NDK 28.2.13676358 (see `docs/poc/spike/pack-cargo-config.toml`).

## State of the owner's real watch (not cleaned up)

The watch still has the **spike test app** `com.seyedjafari.wfpspike` and the pushed **test face** `com.seyedjafari.wfpspike.watchfacepush.analog` (blue disc, active). The owner's normal face is `com.seyedjafari.analogwatchface` (still installed). The owner has not answered whether to remove the test app and face. Removing the pushed face means the watch falls back to a default and the owner picks Analog Plus again by long-press. Ask before removing.

## Open items (from the spec)

Which fixed options the face keeps for the system editor; key backup and recovery after a phone reinstall; Play merchant account and how a paid phone app distributes its Wear OS app; Samsung look-alike decision before listing; animated background spike; whether the default face is bundled or generated.

## Owner working preferences

Wants **very short** chat answers (said twice that replies were too long). Wants the work done, with decisions asked in batches. Global rules in `~/.claude/CLAUDE.md` apply: worktrees, Sonnet writes and the session model verifies, no pushes, no co-author trailers, docs commit directly to `main`.
