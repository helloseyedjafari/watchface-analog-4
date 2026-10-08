# Open-source / source-available tooling for generating, editing and building Wear OS Watch Face Format (WFF) watch faces

State as of 2026-10-08. Star counts and "updated_at" values come from GitHub search metadata fetched on that date; "last commit" dates come from the commit lists I pulled (marked where I did not pull them). Items I could not verify are marked UNVERIFIED.

## 1. Has anyone already built open-source phone-based, user-customizable watch face builders (WFF + Watch Face Push + pack)?

### Takeaway
Yes, but only as very early, tiny-audience projects plus one Google reference app. The closest hit is `miopea/bfg-watchfaces`, an Apache-2.0 phone app using exactly the stack in the planned spec (WFF generation, `google/pack` on device, Watch Face Push, 5 complication slots, user photo as dial), but it is a generative-pattern dial designer, has 0 stars, is a one-person project with an unfinished watch-side readiness bug, and has no ring-complication or free-value builder. Google's own Androidify is the canonical proof that the phone-side pack-to-push pipeline works, but it is template-based, not a general builder. No mature, widely used open-source "phone builder with free values" exists.

### Cited Findings

**Closest match: BFG Watch Faces**
- `miopea/bfg-watchfaces` (Kotlin, Apache-2.0, 0 stars, 0 forks, created 2026-08-27, last commit 2026-10-03). Description: "Open-source Wear OS watch face generator. Design a dial on your phone, push it to your watch. No server, no account, no ads." — [repo](https://github.com/miopea/bfg-watchfaces), [README](https://github.com/miopea/bfg-watchfaces/blob/main/README.md)
- What it does: a library of generative dial styles (knotwork, clous de Paris, rosette, barleycorn, sunburst, botanical); five complication slots (top, left, middle, right, bottom); user can import their own image as the dial; faces stored as small parameter files (JSON, a few KB), shareable; requires Wear OS 6+. — [README](https://github.com/miopea/bfg-watchfaces/blob/main/README.md)
- Pipeline per its README: a `generator` module turns parameters into WFF XML plus dial geometry; "On the device, `google/pack` compiles and signs the APK locally and Watch Face Push installs it on the watch." The `mobile/` Compose design UI and `wear/` Watch Face Push host are labelled "scaffold" in the README layout table, while the localhost "workbench" (a browser design loop at port 7777) is the mature part. — [README](https://github.com/miopea/bfg-watchfaces/blob/main/README.md)
- Its validation lesson: the workbench validates emitted WFF against Google's XSD on every change because "a schema-invalid face installs perfectly and then silently never appears in the carousel." — [README](https://github.com/miopea/bfg-watchfaces/blob/main/README.md); [docs/SPEC.md](https://github.com/miopea/bfg-watchfaces/blob/main/docs/SPEC.md)
- Maturity signals from recent commits: phone 1.100 / watch 1.54 on the Play internal track only (production at 1.97 / 1.51); a readiness guard "wrongly blocked a healthy Pixel Watch 5" and is undiagnosed; several watch-reply paths "have never executed on hardware"; commits were co-authored by an AI assistant. — [commit 5c4b62d](https://github.com/miopea/bfg-watchfaces/commit/5c4b62daf9ccdfd9ac69a749625578deeeb32099), [commit b368c68](https://github.com/miopea/bfg-watchfaces/commit/b368c685f879d96c89739396a019b1679ba7c1bb)
- Its SPEC records hard-won constraints that apply to any competing build: canvas 456x456 for Pixel Watch 4/5; colours are `#AARRGGBB` and six-digit values are silently wrong; ambient is per-element `<Variant mode="AMBIENT">`, not a second Scene; `res/xml/watch_face_info.xml` with `<Preview>` is required; AGP adds `kotlin/` files that Push rejects (so it uses aapt2 directly in the template build); dial PNG quantization cut a 368KB asset to 77KB for Bluetooth transfer; a watch-side app is mandatory because `addWatchFace` takes a local `ParcelFileDescriptor`. It also runs a Cloudflare Workers/D1 community catalog of parameter files. — [docs/SPEC.md](https://github.com/miopea/bfg-watchfaces/blob/main/docs/SPEC.md)
- Its own market caveat: Watch Face Push is Wear OS 6+ only (Pixel Watch 4/5, recent Galaxy Watch); "Pixel Watch 1-3 cannot run this." — [docs/SPEC.md](https://github.com/miopea/bfg-watchfaces/blob/main/docs/SPEC.md)

**Google reference: Androidify**
- `android/androidify` (Kotlin, Apache-2.0, ~1,984 stars, updated 2026-10-08, last commit pulled 2026-06-02). Primarily a Gemini/Imagen avatar app; it added a Wear OS watch-face feature. — [repo](https://github.com/android/androidify), [README](https://github.com/android/androidify/blob/main/README.md)
- The Android Developers blog (2025-12-18) describes the pipeline: a WFF template designed in the Watch Face Designer Figma plugin and bundled as assets; the user's avatar is combined with the template in `WatchFaceCreator.kt`; the APK is assembled on the phone by Pack running as a native library (jniLibs, Rust bindings in `watchface/pack-java/src/lib.rs`); the Watch Face Push validator is run and produces a token; the APK is streamed with `MessageClient`/`ChannelClient`; the watch app calls `addWatchFace` or `updateWatchFace`. — [blog](https://developer.android.com/blog/posts/bringing-androidify-to-wear-os-with-watch-face-push/)
- Limits noted: on Wear OS 6 each app gets one slot (Androidify replaces the existing face when none free); `setWatchFaceAsActive` can only be used once; the `SET_PUSHED_WATCH_FACE_AS_ACTIVE` permission cannot be re-requested after denial. — [blog](https://developer.android.com/blog/posts/bringing-androidify-to-wear-os-with-watch-face-push/); one-slot figure also in Android Authority-style summary via search, but the blog is the primary source.

**In-browser editor pushing to a watch**
- `jamesbiederbeck/wfpush-studio` (TypeScript + Kotlin, 0 stars, created and last commit 2026-09-16; the repo's functional work dates to 2026-05-24). Browser editor where you write raw WFF XML, drop PNG/font assets, and click Build & Push. The APK is built and signed in WebAssembly with `google/pack` and POSTed over Wi-Fi to an HTTP server running in a watch app (PSK-protected). The README says "Work in progress"; the 2026-05-24 commit says it wired up the Watch Face Push API end to end. — [repo](https://github.com/jamesbiederbeck/wfpush-studio), [README](https://github.com/jamesbiederbeck/wfpush-studio/blob/main/README.md)
- License inconsistency: README says Apache 2.0, the LICENSE file is MIT (added in the last commit). — [README](https://github.com/jamesbiederbeck/wfpush-studio/blob/main/README.md), [LICENSE](https://github.com/jamesbiederbeck/wfpush-studio/blob/main/LICENSE)
- It is a developer tool (hand-written XML) with no visual editor, so not a consumer builder.

**Google's Watch Face Push samples**
- `android/wear-os-samples` (Kotlin, ~1,406 stars, 641 forks, updated 2026-10-02) contains a `WatchFacePush` marketplace sample, plus `WatchFaceFormat`, `Complications`. Its latest `WatchFacePush` commit I saw is 2026-06-15. The README is minimal (builds with Gradle; "enables you to build watch face marketplace apps"; Wear OS 6+). — [repo](https://github.com/android/wear-os-samples), [WatchFacePush README](https://github.com/android/wear-os-samples/blob/main/WatchFacePush/README.md)
- `garanj/wfp-use-cases` ("not an official Google sample", 4 stars, 2 forks, last updated 2026-03-15) explores uses beyond marketplaces: Football (phone app changes colours, images and data to a favourite team), Palette (phone app sets a colour theme from a photo through a bundled complication), Photo (WorkManager photo service), Surf, DeviceData. Palette passes colours through a SHORT_TEXT complication whose TEXT field holds hex RGB values. Faces are prebuilt APKs bundled in app assets, not generated on the phone. — [repo](https://github.com/garanj/wfp-use-cases), [README](https://github.com/garanj/wfp-use-cases/blob/main/README.md), [Android Developers blog, Aug 2025](https://android-developers.googleblog.com/2025/08/further-explorations-with-watch-face-push.html)
- Official guidance: Watch Face Push is "high complexity"; it recommends it for phone apps where users "design and customize faces for installation on their watch." Faces must be WFF; package name must be `<app>.watchfacepush.<name>`; APK may contain only `/AndroidManifest.xml`, `/resources.arsc`, `/res/**`, `/META-INF/**`; `minSdk >= 33`, `hasCode="false"`; every face needs a validation token from the official validator (CLI, JVM or Android library; `1.0.0-alpha10` at time of docs). — [Watch Face Push policies](https://developer.android.com/watch-face-push-policies), [Watch Face Push guide](https://developer.android.com/training/wearables/watch-face-push)
- `androidx.wear.watchfacepush:watchfacepush` reached 1.0.0 on 2026-04-08; release notes say it is designed for cases where a user may "select, create, or edit a watch face on their phone" and add it to the watch. — [release notes](https://developer.android.com/jetpack/androidx/releases/wear-watchfacepush)

**Other apps using Watch Face Push in public source**
- `amoledwatchfaces/Solar-Path-Watch-Face-Wear-OS` (Kotlin, GPLv3, 4 stars, created 2026-10-05, updated 2026-10-08): a commercial-brand face that bundles a WFF v4 APK in its companion app and pushes it with AndroidX Watch Face Push; 4 arc complication slots, 70+ colour palettes, 11 presets. Shows an established face vendor adopting Push for distribution, not a builder. — [repo](https://github.com/amoledwatchfaces/Solar-Path-Watch-Face-Wear-OS)
- Code search also surfaces Watch Face Push helper code in `savek-cc/FrankensteinAPS` (AAPS fork) and `lumose-health/android-unofficial` (glucose app installing a face). I did not open these; they are consumers of the API, not builders. — [code search result list, unopened]

### Inferences
- The exact architecture the user plans (phone UI -> WFF XML -> `pack` on device -> validator token -> Data Layer -> `addWatchFace`) has been demonstrated end to end by Google (Androidify) and attempted in the open by BFG and wfpush-studio. So technical feasibility is established; differentiation must come from the editing model (free values, ring complications, photo backgrounds), UX, and polish.
- BFG is the only public project I found positioned as a consumer "design on phone, push to watch" tool. Its fixed generative engines and parameter-only catalog mean it is not a free-form editor. It is a lightweight competitor and a useful public reference for pitfalls (SPEC.md), not an obvious threat today (0 stars, internal-track releases).
- Google appears to treat phone-side custom faces as a developer-level opportunity (docs say "high complexity"), which leaves room for a third-party consumer builder, but Google's own first-party creator (see Q4) narrows that room.

### Gaps
- I did not find any published user numbers, Play installs or reviews for BFG Watch Faces; Play listing status ("internal only") comes from its commit messages only.
- Could not confirm which Play Store apps (closed source) already do phone-side generation via Watch Face Push; GitHub search only covers open source.
- Did not read the Androidify `WatchFaceCreator.kt` source myself; the description comes from Google's blog post.

---

## 2. WFF libraries, generators, validators, templates (programmatic generation, linters, converters)

### Takeaway
There is one official validation/optimisation repo, one complete-but-stale Kotlin DSL, and a long tail of one-off Python generators (often AI-assisted) for individual faces. Nothing found is a polished reusable "WFF model + editor" library; for XML emission you are choosing between writing your own emitter, the Kotlin WffDsl, or following BFG/Kerteriz patterns.

### Cited Findings
- `google/watchface` (Kotlin, Apache-2.0, 71 stars, 11 forks, 13 open issues; latest commits 2026-09-08 "Handle malformed WEBP headers", 2026-08-13 "Support schema version 5"). Contains the WFF XSD (v1, v2; commit says schema version 5 now supported), an XSD validator in `third_party/wff`, a memory-footprint evaluator in `play-validations`, and a WFF optimizer in `tools/wff-optimizer`. No samples (points to wear-os-samples). — [repo](https://github.com/google/watchface), [commits](https://github.com/google/watchface/commits/main)
- `Splitties/WffDsl` (Kotlin, 21 stars, 5 forks, 14 open issues, last commit 2024-04-19, repo updated 2026-03-12): Kotlin DSL generating WFF XML; README claims "100 % of the tags and attributes are implemented" and typed arithmetic expressions, but warns the API will break, and docs/Maven publication are unchecked. Used for a paid Play face ("Digitalog Sharp", $3.5). License not captured. Stale against current WFF versions (v3/v4/v5). — [repo](https://github.com/Splitties/WffDsl)
- `TrianguloY/WatchFaceGenerator` (Kotlin, Creative Commons licence per README, 0 stars, created 2026-09-30, last commit 2026-10-04): a personal Kotlin DSL that generates `watchface.xml` plus the face built with it; claims it was made without AI. — [repo](https://github.com/TrianguloY/WatchFaceGenerator)
- `afsozer/kerteriz` (Python, MIT, 0 stars, created 2026-09-23): six WFF v2 faces with Pillow-generated assets, a `validate.py` that checks XML against a relaxed XSD 1.0 copy plus resource links, hand pivots and the ambient branch, and an aapt2/zipalign/apksigner build script (no Gradle). Uses WFF `UserConfigurations` for accent colours and `BitmapFonts` for a 7-segment face. — [repo](https://github.com/afsozer/kerteriz)
- `cparnin/pixel-watch-face` (Python + Gradle, MIT code, 1 star, created 2026-08-26, updated 2026-10-04): "Photo-theme watch face for the Pixel Watch (Wear OS 5)". `./face add photo.jpg "Name"` crops/darkens a photo, picks an accent, regenerates the WFF XML, validates with Google's validator, and builds an APK; every tile is a standard complication; each theme appears as a preset in the Pixel Watch app. This is photo-background-from-user-photo done on a laptop, not on the phone. — [repo](https://github.com/cparnin/pixel-watch-face)
- `Superl3/S3WearOSWatchFaceEditor` ("Photo2WFF", Python, 0 stars, no licence stated, last commit 2026-08-25): experimental pipeline that rebuilds minimalist 438x438 faces from a reference image (analyzer -> `scene.json` -> WFF XML -> preview -> compare); CLI driven, not a visual editor. — [repo](https://github.com/Superl3/S3WearOSWatchFaceEditor)
- Long tail of small hobby generators (all 0-1 stars, created mid-2026, mostly Python scripts producing a WFF XML + assets): `pburney/BinaryWatchFace`, `QuinaryWatchFace`, `RadixWatchFace`; `mayflower/daliclock`; `harsharora08/word-grid-watchface`; `SamakaCD/utility-wff` (rebuild of Pixel "Utility"); `miopea/` and others. Of note: many carry `claude` / AI-assistant traces (branch `claude/jolly-ptolemy-pxjebt` in `dspremberg-web/ziffernblatt1`). Topic `watch-face-format` has only 15 repos in total. — [topic search results](https://github.com/topics/watch-face-format)
- Two reference-quality real faces in WFF: `lukakilic/concentric-watch-face` (65 stars, 13 forks; "Concentric watch face from the Pixel Watch, built with WFF"; updated 2026-09-28) — [repo](https://github.com/lukakilic/concentric-watch-face); and `hms-douglas/dsh008v2` (1 star; WFF face with a phone helper app and widget). Useful as WFF samples, not tools.
- `hms-douglas/wfe` ("Watch face editor library for building Wear OS watch faces editor/customizer that uses the Jetpack Watch Face API"): 3 stars, **archived**, last updated 2026-01-05, targets the old Jetpack Watch Face API (not WFF). — [repo](https://github.com/hms-douglas/wfe)
- Google's official authoring tool: Watch Face Designer, a Figma plugin (announced Aug 2025) supporting digital/analog time, interactive and ambient modes, custom fonts, complications, and automatic theme creation from Figma styles. Not open source as far as found. — [blog](https://android-developers.googleblog.com/2025/08/introducing-watch-face-designer.html)

### Inferences
- No open-source WFF validator/linter beyond Google's own XSD + Push validator exists in what I found; Kerteriz and BFG each wrote their own extra checks (hand pivots, XSD in a JVM test), which suggests a gap and also a lesson: schema-invalid faces silently fail to appear, so any builder needs a built-in validation step.
- The Kotlin DSL ecosystem is thin and stale (WffDsl untouched since April 2024), so reusing it directly is risky; it is better treated as a reference for the tag/attribute model.
- Nobody has built a visual WFF editor (web or desktop). The only editors found are for other watch ecosystems (Q4).

### Gaps
- I did not find converters from other formats (Facer, Tizen, WFS projects) to WFF. Search was limited to GitHub repository search; closed-source or private tools may exist.
- Did not find AI/LLM face generators as named tools beyond the many repos that were evidently produced with an AI assistant, and Google's own Pixel Watch 5 text-to-face feature (Q4). I searched GitHub for MCP/skill-style WFF tools and got zero hits.
- WffDsl and Kerteriz licences (WffDsl) not captured; verify before reuse.

---

## 3. `google/pack`: usage, activity, documented limitations

### Takeaway
`google/pack` is real, Apache-2.0, actively maintained by Google (but "not an officially supported Google product"), and is the enabling piece for on-device APK building. Its public footprint is small: 235 stars, no GitHub-listed dependents, but Google's Androidify and at least two community projects use it. Documented limits: single-language strings only, `drawable` only (no density qualifiers), no resource qualifiers/forks, no APK signature scheme v4.

### Cited Findings
- Repo: `google/pack`, Rust, Apache-2.0, 235 stars, 26 forks, 15 open issues (GitHub search metadata; issues page shows 4 open at fetch time), created 2025-03-10, updated 2026-09-20. Most recent commits I saw were dependabot bumps (latest 2026-01-06, "Bump zip from 6.0.0 to 7.0.0"), i.e. no feature work visible in recent commits. — [repo](https://github.com/google/pack), [commits](https://github.com/google/pack/commits/main)
- Purpose: compile and sign APKs and AABs "without requiring native software like Android Studio or the SDK Build Tools." Runs on macOS/Linux/Windows/Android/Web, as CLI, WASM/JS module, Rust crate, on-device Android compiler, and inside an Android app (`advanced-examples/pack-from-java`). "Currently, pack is being developed for compiling Wear OS Watch Face Format packages in the browser" because they are resources only, with no compiled code. — [README](https://github.com/google/pack/blob/main/README.md)
- Documented coverage limits (README compatibility table): only single-language `strings.xml`; only `drawable` (no `drawable-xhdpi`); no resource qualifiers such as `values-b+es/`; APK Signature Scheme v4 not implemented; "supports _enough_ to compile basic Wear OS watch faces as a proof of concept." — [README](https://github.com/google/pack/blob/main/README.md)
- Open issues (all opened 2025-04-29 by one contributor): #39 resource qualifiers/forks, #38 signing scheme v4, #37 warn on missing critical attributes, #36 CLI switches for compile-time attribute injection. — [issues](https://github.com/google/pack/issues)
- GitHub "dependents" page shows 0 repositories and 0 packages. — [dependents](https://github.com/google/pack/network/dependents). Contradicted in practice by known users: Androidify (Google blog, above) and `jamesbiederbeck/wfpush-studio` and `miopea/bfg-watchfaces` (both state they use it), which GitHub does not list because they do not depend through a package manifest it indexes.
- Default signing uses a random testing key; custom signing takes a `.pem` file. — [README](https://github.com/google/pack/blob/main/README.md). Push policy requires watch face APKs to be signed with a key different from the main app's key. — [policies](https://developer.android.com/watch-face-push-policies)
- Androidify ships pack as prebuilt `jniLibs` (Rust JNI bindings) that BFG's SPEC suggests re-using "if the ABIs cover you." — [blog](https://developer.android.com/blog/posts/bringing-androidify-to-wear-os-with-watch-face-push/), [BFG SPEC](https://github.com/miopea/bfg-watchfaces/blob/main/docs/SPEC.md)
- Note BFG's finding that AGP-built APKs include `kotlin/` and `DebugProbesKt.bin` which Push rejects; pack's resource-only output avoids this class of problem, though BFG's template build still uses aapt2 directly. — [BFG SPEC](https://github.com/miopea/bfg-watchfaces/blob/main/docs/SPEC.md)

### Inferences
- Pack is a viable but young dependency: the maintainer cadence appears low (only dependency bumps in the last visible commits), the issue tracker is small, and Google disclaims support. A builder that depends on it should vendor/pin a version and keep an aapt2 fallback in the toolchain for desktop-side testing.
- Resource limits (drawable only, single language) are unlikely to block a phone builder whose output is a single dial with generated images, but string localisation and density-specific assets would not work.

### Gaps
- I did not list pack's commits older than 2025-12-15 or check whether a tagged release exists; "last meaningful feature commit" is unknown.
- Did not check forks of `google/pack`.

---

## 4. Community and adjacent ecosystems: Watch Face Studio, Facer, older Wear / Tizen / Amazfit editors, and first-party competition

### Takeaway
Open-source visual editors for watch faces exist only for non-Wear OS ecosystems (Xiaomi, Amazfit, Huawei/Honor, Apple dumpers). For Wear OS there is no open-source GUI editor; the mainstream authoring tools (Samsung Watch Face Studio, Google's Watch Face Designer, Facer, WatchMaker) are closed. A first-party competitor is emerging: Google's Pixel Watch 5 on-watch AI face creator.

### Cited Findings
- Samsung Watch Face Studio: GitHub only contains users' projects, not tooling (`sderaps/DMM` 11 stars, `LED_WatchFace` 1 star asset generator for WFS/Wear OS, etc.). The tool itself is closed-source. — [GitHub search "watch face studio"](https://github.com/search?q=watch+face+studio&type=repositories)
- Legacy Wear OS projects on the Jetpack Watch Face API (canvas-based faces, not WFF): `VladimirWrites/AnalogWatchFace` (135 stars, archived), `LayoutXML/Twelveish` (53 stars, archived). Not WFF and not reusable for a builder. — [AnalogWatchFace](https://github.com/VladimirWrites/AnalogWatchFace), [Twelveish](https://github.com/LayoutXML/Twelveish)
- Non-Wear OS open-source watch face editors, for context only: `m0tral/EasyFace` (Xiaomi, 742 stars, updated 2026-10-07), `Johnson070/MiBand-5-watchface-editor` (288 stars, archived), `v1ack/watchfaceEditor` (Amazfit Bip/Cor, 185 stars), `banjun/WatchFaceDumper` (Apple Watch `.watchface` viewer/editor, 63 stars), `Nadeflore/watchface-web-editor` (Huami .bin, 33 stars), `DivoomDevelop/divoom-watchface-visual-editor_v2` (Divoom, 5 stars). Only useful as UX references (drag-and-drop, live preview). — [EasyFace](https://github.com/m0tral/EasyFace), [watchfaceEditor](https://github.com/v1ack/watchfaceEditor), [WatchFaceDumper](https://github.com/banjun/WatchFaceDumper)
- Tizen/Galaxy Watch Studio GitHub content is faces and small toolkits (`Rinned/VJQ_Web_Watchface_OpenKit`, 0 stars); no maintained open-source Tizen editor found. — [search result](https://github.com/Rinned/VJQ_Web_Watchface_OpenKit)
- Facer / WatchMaker / Pujie: closed ecosystems. Android Authority reported Wear OS 5's mandatory WFF "nerfed" third-party face apps; a later Android Central piece says Wear OS 6 reintroduced custom-face capability. Treat as secondhand from search summaries. — [Android Authority](https://www.androidauthority.com/wear-os-5-killed-facer-watchmaker-watch-face-creativity-3508653/) (title only, page not fetched), search summary of Android Central
- KWCH (Kustom Watchface Maker) was a WYSIWYG Wear OS maker reported in 2023, pre-WFF mandatory; compatibility with WFF unknown. — [9to5Google, 2023](https://9to5google.com/2023/09/26/wear-os-custom-watch-faces-kwch-app/) (search result, not fetched)
- First-party competition: Google's Pixel Watch 5 launch added Weave and Chronograph faces and "the ability to create your own custom watch face"; Tom's Guide describes a "Create Watch Face" app with text or voice prompt (10-30 s generation, needs connectivity) whose output is static; Tech Advisor says it comes to Pixel Watch 4 in a Q3 OTA update. — [Tech Advisor](https://www.techadvisor.com/article/3213107/the-pixel-watch-5-has-a-surprising-list-of-exclusive-features.html), [Tom's Guide](https://www.tomsguide.com/wellness/smartwatches/i-used-ai-to-create-a-deli-meat-inspired-watch-face-for-my-pixel-watch-5-and-now-im-questioning-everything), [Android Authority sideload guide](https://androidauthority.com/install-google-pixel-watch-5-watch-faces-pixel-watch-4-3703401) (all via search snippets; pages not individually fetched, one returned HTTP 403)

### Inferences
- There is no open-source Wear OS GUI editor to fork; any visual editor would be greenfield, and the UX reference points are the Xiaomi/Amazfit editors plus Google's Figma plugin.
- Google's on-watch AI generator produces static, prompt-driven faces, which differs from a data-rich analog builder with free values and ring complications; but it sets consumer expectations for "make your own face" and could erode the novelty on Pixel Watches.

### Gaps
- I did not verify the Pixel Watch 5 feature on a primary Google source; relying on press snippets. Another researcher on the product-market side may have better coverage.
- No Hacker News or Reddit threads found specifically about open-source WFF tooling; the only Reddit-derived item in results was a sideloading tip. Searching Reddit/HN directly was not possible with the tools available, so absence there is not evidence.
- Did not research Facer's current creator tooling status or Samsung Watch Face Studio's export formats in depth.

---

## Summary table (for the report writer)

| Project | URL | License | Stars | Last commit (verified) | What it is | Closeness to "phone builder, free values, ring complications, photo backgrounds" | Competitor or component? |
|---|---|---|---|---|---|---|---|
| miopea/bfg-watchfaces | https://github.com/miopea/bfg-watchfaces | Apache-2.0 | 0 | 2026-10-03 | Phone generator of generative dials, pack + Push, 5 complications, own-image dial | High on architecture, low on editing model (parametric engines only), early | Weak competitor; best public architecture reference |
| android/androidify | https://github.com/android/androidify | Apache-2.0 | ~1,984 | 2026-06-02 | Gemini avatar app that builds a WFF face on phone with pack and pushes | Proves pipeline; template-only | Component/reference (pack JNI libs, transfer code) |
| jamesbiederbeck/wfpush-studio | https://github.com/jamesbiederbeck/wfpush-studio | MIT file / Apache README (conflict) | 0 | 2026-09-16 | Browser XML editor, pack-WASM, push over Wi-Fi | Low (dev tool) | Reference for pack WASM + push |
| google/pack | https://github.com/google/pack | Apache-2.0 | 235 | 2026-01-06 (dependabot) | On-device/Web APK compiler | Enabler | Core component (pin version) |
| google/watchface | https://github.com/google/watchface | Apache-2.0 | 71 | 2026-09-08 | XSD, validator, memory evaluator, optimizer | Enabler | Reuse for validation |
| android/wear-os-samples (WatchFacePush) | https://github.com/android/wear-os-samples | Apache-2.0 (not individually confirmed) | ~1,406 | 2026-06-15 (WatchFacePush path) | Marketplace sample | Reference | Component/reference |
| garanj/wfp-use-cases | https://github.com/garanj/wfp-use-cases | not captured | 4 | 2026-03 (updated) | Push samples: colours from photo, team colours | Medium on concept (palette from photo) | Reference |
| Splitties/WffDsl | https://github.com/Splitties/WffDsl | not captured | 21 | 2024-04-19 | Kotlin WFF DSL | Component, stale | Reference only |
| TrianguloY/WatchFaceGenerator | https://github.com/TrianguloY/WatchFaceGenerator | CC (per README) | 0 | 2026-10-04 | Personal Kotlin DSL | Low | Reference |
| cparnin/pixel-watch-face | https://github.com/cparnin/pixel-watch-face | MIT (code) | 1 | not pulled (updated 2026-10-04) | Photo-themed face, CLI on laptop | Medium on photo backgrounds | Reference for photo crop/darken pipeline |
| afsozer/kerteriz | https://github.com/afsozer/kerteriz | MIT | 0 | not pulled (updated 2026-09-23) | Python WFF faces + validator | Low | Reference for validation checks |
| Superl3/S3WearOSWatchFaceEditor | https://github.com/Superl3/S3WearOSWatchFaceEditor | none stated | 0 | 2026-08-25 | Image-to-WFF reconstruction CLI | Low | Not reusable |
| amoledwatchfaces/Solar-Path-Watch-Face-Wear-OS | https://github.com/amoledwatchfaces/Solar-Path-Watch-Face-Wear-OS | GPLv3 | 4 | not pulled (updated 2026-10-08) | Commercial face using Push for distribution | Low | Market signal |
| hms-douglas/wfe | https://github.com/hms-douglas/wfe | not captured | 3 | archived (updated 2026-01-05) | Old Jetpack Watch Face API editor lib | Low | Dead |
