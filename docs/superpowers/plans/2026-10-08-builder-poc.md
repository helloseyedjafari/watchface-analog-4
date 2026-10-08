# Analog Plus builder POC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A working proof of concept where a phone app edits a watch face with free values (any hex colour, numbers), movable complication slots and ring complications, builds and validates the face on the phone, and pushes it to a Wear OS 6 watch with Watch Face Push.

**Architecture:** A shared Android library `:facegen` turns a `DesignSettings` object into a face (manifest text, `watchface.xml`, PNGs). A native module `:packjni` wraps Google's `pack` (Rust) to compile and sign the APK on the phone; Google's `validator-push-android` produces the token. The phone app sends APK plus token to the watch app over the Wear Data Layer; the watch app calls `WatchFacePushManager` (add, then only update, activate once).

**Tech Stack:** Kotlin 2.0.21, AGP 8.9.1, Gradle 8.11.1, Jetpack Compose (phone), `androidx.wear.watchfacepush:watchfacepush:1.0.0` (watch), `com.google.android.wearable.watchface.validator:validator-push-android:1.1.0-alpha01` (phone), Google `pack` (Rust, `cargo-ndk`), `play-services-wearable` (Data Layer).

**Spec:** `docs/superpowers/specs/2026-10-08-builder-app-design.md`. This POC covers: free-value core look, movable slots with backgrounds, ring complications, photo background, built-in templates, share-by-link. Out of POC: digital or dual clocks, animated backgrounds, community, web builder, store track.

## Global Constraints

- Phone app: Android 9 (API 28) and newer. Watch app: Wear OS 6 (API 36). Generated faces: `minSdk` 33, `hasCode="false"`.
- App id for phone and watch apps: `com.seyedjafari.analogplus`. Face package: `com.seyedjafari.analogplus.watchfacepush.analog`. The existing `:app` face (`com.seyedjafari.analogwatchface`) is not modified.
- A pushed APK contains only `AndroidManifest.xml`, `resources.arsc`, `res/**`, `META-INF/**`. It must include `res/xml/watch_face_info.xml` and `res/drawable/preview.png`.
- Colours are `#AARRGGBB`. A face has at most 8 complication slots in total (movable slots plus ring segments).
- Never call `removeWatchFace`. Call `setWatchFaceAsActive` at most once. Updates need a strictly higher `versionCode`.
- The signing key is generated once, persisted, and reused. A face signed with a different key cannot update an installed one.
- Gradle file watching misses edits under `/private/tmp`; set `org.gradle.vfs.watch=false` when building there.
- Default design must look like today's face (visual comparison on watch screenshot; pixel test is a later plan).

## Review Focus

- Colour text that is not `#AARRGGBB` (`#fff`, `red`, empty): rejected with a clear error, never written into the XML.
- More than 8 slots in total, or movable slots overlapping each other or the hand centre: rejected by validation and prevented in the editor.
- A ring segment whose provider returns text only, or a ranged value outside min and max: the arc shows nothing or the text, never crashes (values clamped).
- A photo background that makes the APK too large for Bluetooth: resized and compressed to a hard cap (350 KB), build fails clearly if still over.
- App restart or reinstall of the phone app between two pushes: the same signing key is reused so the update is accepted; lost key produces an explained error, not a silent failure.

## File Structure

```
settings.gradle.kts, build.gradle.kts, gradle/wrapper/gradle-wrapper.properties   modify (modules, AGP, Kotlin, Compose)
protocol/                    pure Kotlin JVM: Data Layer paths, message payloads (shared by phone and watch)
facegen/                     Android library
  src/main/kotlin/com/seyedjafari/analogplus/facegen/
    design/DesignSettings.kt     model, defaults
    design/Validation.kt         validate(): List<DesignError>
    design/DesignJson.kt         JSON in/out, share link encode/decode
    design/Templates.kt          built-in designs
    xml/FaceXml.kt               composes sections into FaceFiles
    xml/CoreSections.kt          background, dial ticks, numerals, hands, cap
    xml/SlotSections.kt          movable slots, backgrounds, complication text
    xml/RingSections.kt          ring complications
    render/ResourceRenderer.kt   PNGs: hands, second hand, numerals, background, preview.png
    render/PreviewRenderer.kt    on-screen preview Bitmap
    FacePackager.kt              design -> signed, validated FaceBundle
    KeyStore.kt                  persisted signing key (PEM)
  src/test/kotlin/...            JVM tests for design, validation, xml
  src/androidTest/kotlin/...     device tests for render, packager
packjni/                     Android library with Rust (Cargo.toml, src/lib.rs, PackNative.kt)
phone/                       app: Compose editor, preview, sender, share
watch/                       app: receiver, push controller, status UI, bundled default face
scripts/build_default_face.sh, scripts/poc_e2e.sh
```

## Shared interfaces (all tasks rely on these exact names)

```kotlin
// design/DesignSettings.kt
typealias Argb = String // "#AARRGGBB"
enum class NumeralMode { ALL, QUARTERS, OFF }
enum class SecondHandStyle { SWEEP, TICK, HIDDEN }
enum class SlotShape { BOX, OVAL, ROUND_BOX }
enum class RingLayout { NONE, FULL, HALVES, THIRDS, QUARTERS }
enum class RingCaps { ROUND, FLAT }
enum class BackgroundKind { SOLID, GRADIENT, PHOTO }

data class SlotDesign(val id: Int, val name: String, val x: Int, val y: Int, val w: Int, val h: Int,
    val shape: SlotShape = SlotShape.BOX, val backgroundColor: Argb? = null,
    val textColor: Argb = "#FFFFFFFF", val iconColor: Argb = "#FFFFFFFF")
data class RingDesign(val layout: RingLayout = RingLayout.NONE, val colors: List<Argb> = listOf("#FFE22B07"),
    val trackColor: Argb = "#33FFFFFF", val thicknessPx: Int = 14, val gapDegrees: Int = 6,
    val startAngle: Int = 0, val caps: RingCaps = RingCaps.ROUND, val clockwise: Boolean = true)
data class BackgroundDesign(val kind: BackgroundKind = BackgroundKind.SOLID, val color: Argb = "#FF000000",
    val color2: Argb = "#FF000000", val photoJpeg: ByteArray? = null, val dimPercent: Int = 0)
data class DesignSettings(
    val name: String = "Classic", val version: Int = 1,
    val accent: Argb = "#FFE22B07", val handColor: Argb = "#FFFFFFFF", val inlayColor: Argb = "#FFE0E0E0",
    val tickColor: Argb = "#FFC8C8C8", val minuteTickWidth: Int = 2, val minuteTickLength: Int = 9,
    val showMinuteTicks: Boolean = true, val fiveMinuteDots: Boolean = true,
    val numerals: NumeralMode = NumeralMode.ALL, val numeralColor: Argb = "#FFFAFAFA",
    val secondHand: SecondHandStyle = SecondHandStyle.SWEEP, val secondHandColor: Argb? = null, // null = accent
    val background: BackgroundDesign = BackgroundDesign(),
    val complicationTextSize: Int = 26, val complicationTitleSize: Int = 16,
    val showTitles: Boolean = true, val showIcons: Boolean = true,
    val slots: List<SlotDesign> = defaultSlots(), val ring: RingDesign = RingDesign())

// design/Validation.kt
data class DesignError(val field: String, val message: String)
fun DesignSettings.validate(): List<DesignError>
fun parseArgb(s: String): Int?            // null if not #AARRGGBB

// xml/FaceXml.kt
data class FaceResource(val type: String, val name: String, val bytes: ByteArray) // type: raw|drawable|xml|values
data class FaceFiles(val manifest: String, val resources: List<FaceResource>)
fun interface XmlSection { fun render(d: DesignSettings): String }
object FaceXml { fun watchFaceXml(d: DesignSettings): String; fun files(d: DesignSettings, versionCode: Int, images: List<FaceResource>): FaceFiles }

// render/ResourceRenderer.kt (Android)
object ResourceRenderer { fun render(d: DesignSettings, context: Context): List<FaceResource> }

// packjni
object PackNative { fun build(manifest: String, resources: List<FaceResource>, pem: String): ByteArray; fun generateKeysPem(): String }

// FacePackager.kt
data class FaceBundle(val apk: File, val token: String, val versionCode: Int, val packageName: String)
class FacePackager(context: Context) { fun build(d: DesignSettings, versionCode: Int): FaceBundle }

// protocol
object Protocol { const val FILE_PATH = "/analogplus/face"; const val APPLY = "/analogplus/apply"; const val RESULT = "/analogplus/result" }
data class ApplyMessage(val versionCode: Int, val token: String, val apkSize: Long)
data class ResultMessage(val ok: Boolean, val versionCode: Int, val error: String?)
```

## Tasks

Order: Task 1 first (me, inline). Then Tasks 2, 6, 8 in parallel. Then Tasks 3, 4, 5 in parallel (they need Task 2). Then Task 7. Then Task 9. Then Task 10. Implementers are Sonnet; every diff is read by the coordinator before it counts as done.

### Task 1: Scaffold the modules (coordinator, inline)

**Files:** Modify `settings.gradle.kts`, `build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties` (Gradle 8.11.1), `.gitignore` (add `phone/build`, `watch/build`, `**/target/`, `*.pem`). Create `protocol/`, `facegen/`, `packjni/`, `phone/`, `watch/` with empty buildable skeletons.

**Interfaces:** Produces the module names and the `protocol` payload classes above.

- [ ] **Step 1:** Upgrade root plugins to AGP 8.9.1 and add Kotlin 2.0.21 and Compose compiler plugins; keep `:app` building. Run `./gradlew :app:assembleDebug` — expect BUILD SUCCESSFUL.
- [ ] **Step 2:** Add the four modules with minimal sources, `facegen` minSdk 28, `phone` minSdk 28, `watch` minSdk 36, `protocol` as `kotlin("jvm")`. Run `./gradlew assembleDebug testDebugUnitTest` — expect BUILD SUCCESSFUL.
- [ ] **Step 3:** Write `Protocol`, `ApplyMessage`, `ResultMessage` with `toJson()/fromJson()` using `org.json` (Android) — place in `protocol` using plain string building so it also runs on the JVM; add a round-trip test. Run `./gradlew :protocol:test` — PASS.
- [ ] **Step 4:** Commit `feat: scaffold builder modules`.

### Task 2: Design model, validation, JSON, templates, share link

**Files:** Create `facegen/.../design/{DesignSettings,Validation,DesignJson,Templates}.kt`; Test `facegen/src/test/kotlin/.../design/DesignTest.kt`.

**Interfaces:** Produces everything under "Shared interfaces / design". `defaultSlots()` returns the four slots of today's face (top 150,106,150,76; right 277,236,90,76; left 83,236,90,76; bottom 175,300,100,76), ids 0..3, names top/right/left/bottom. `Templates.all: List<DesignSettings>` has at least Classic (the default), Midnight, Light, Neon, Minimal. `DesignJson.toJson(d): String`, `fromJson(s): DesignSettings`, `shareLink(d): String` (`analogplus://design?d=` + base64url of gzip JSON; photo bytes excluded), `fromShareLink(s): DesignSettings`.

- [ ] **Step 1: Write the failing tests**

```kotlin
class DesignTest {
    @Test fun defaultIsValid() = assertTrue(DesignSettings().validate().isEmpty())
    @Test fun badColoursAreRejected() {
        for (bad in listOf("#fff", "red", "", "#GGGGGGGG", "FF000000")) {
            val errs = DesignSettings(accent = bad).validate()
            assertTrue("accent '$bad'", errs.any { it.field == "accent" })
        }
    }
    @Test fun parseArgbAcceptsOnlyEightHexDigits() {
        assertEquals(0xFFE22B07.toInt(), parseArgb("#FFE22B07"))
        assertNull(parseArgb("#E22B07"))
    }
    @Test fun capIsEightSlotsInTotal() {
        val slots = (0 until 6).map { SlotDesign(it, "s$it", 100 + it * 10, 100, 8, 8) }
        val d = DesignSettings(slots = slots, ring = RingDesign(RingLayout.QUARTERS))
        assertTrue(d.validate().any { it.field == "slots" && "8" in it.message })
    }
    @Test fun overlappingSlotsAreRejected() {
        val a = SlotDesign(0, "a", 100, 100, 80, 60); val b = SlotDesign(1, "b", 150, 120, 80, 60)
        assertTrue(DesignSettings(slots = listOf(a, b)).validate().any { it.field == "slots" })
    }
    @Test fun slotCannotCoverTheHandCentre() {
        val s = SlotDesign(0, "c", 200, 200, 50, 50)
        assertTrue(DesignSettings(slots = listOf(s)).validate().any { it.field == "slots" })
    }
    @Test fun jsonRoundTripKeepsEverything() {
        val d = DesignSettings(accent = "#FF1E6BFF", numerals = NumeralMode.QUARTERS, ring = RingDesign(RingLayout.THIRDS))
        assertEquals(d, DesignJson.fromJson(DesignJson.toJson(d)))
    }
    @Test fun shareLinkRoundTrips() {
        val d = Templates.all[1]
        assertEquals(d, DesignJson.fromShareLink(DesignJson.shareLink(d)))
    }
    @Test fun everyTemplateIsValid() = Templates.all.forEach { assertTrue(it.name, it.validate().isEmpty()) }
}
```

- [ ] **Step 2:** Run `./gradlew :facegen:testDebugUnitTest --tests '*DesignTest*'` — expect compile failure (types missing).
- [ ] **Step 3:** Implement the types, `parseArgb` (regex `^#[0-9A-Fa-f]{8}$`), `validate()` (all colour fields, ranges: tick width 1..8, tick length 3..20, text sizes 10..48, dim 0..80, ring thickness 4..40, gap 0..30; slots in 0..450 space inside radius 190 of the centre; no overlaps; none covering the 36-unit square around 225,225; total slots + ring segments ≤ 8 where segments are 1/2/3/4 for FULL/HALVES/THIRDS/QUARTERS), JSON (hand-written with `org.json` or kotlinx-serialization; choose kotlinx-serialization), and the templates.
- [ ] **Step 4:** Run the tests — PASS.
- [ ] **Step 5:** Commit `feat: design model, validation and share links`.

### Task 3: XML sections for the core look

**Files:** Create `facegen/.../xml/{FaceXml,CoreSections}.kt`; Test `facegen/src/test/kotlin/.../xml/CoreXmlTest.kt`.

**Interfaces:** Consumes `DesignSettings`. Produces `FaceXml.watchFaceXml(d)` composing `listOf(BackgroundSection, TicksSection, NumeralsSection, SlotsSection /*Task 4*/, RingSection /*Task 4*/, HandsSection, CapSection)` in that draw order; sections from Task 4 are referenced through a `SlotSections.all` and `RingSections.all` list that Task 4 provides (Task 3 creates empty placeholders files `SlotSections.kt`/`RingSections.kt` with `val all: List<XmlSection> = emptyList()` for Task 4 to fill). Produces `FaceXml.files(d, versionCode, images)` building the manifest text (package, `versionCode`, `minSdk 33`, WFF version property `3`), plus `res/raw/watchface.xml`, `res/xml/watch_face_info.xml`, `res/values/strings.xml`.

Behaviour: background SOLID writes `Scene backgroundColor`; GRADIENT writes a full-screen rectangle with `LinearGradient`; PHOTO writes a `PartImage` of `drawable/background` plus a black rectangle of `dimPercent` alpha above it, hidden in ambient. Ticks: 60 `Line` elements inside a `PartDraw` (48 minute ticks of `minuteTickLength`/`minuteTickWidth`, and 12 five-minute dots as `Ellipse` when `fiveMinuteDots`), colour `tickColor`, hidden when `showMinuteTicks` is false for minute ticks. Numerals: one `PartImage` per numeral referencing `drawable/num_1`..`num_12` (tinted `numeralColor`), skipped per `NumeralMode`. Hands: `HourHand`, `MinuteHand`, `SecondHand` with resources `hour`, `minute`, `second` and the geometry from `tools/build_face.py` (copy the numbers, do not recompute); `SecondHand` omitted for `HIDDEN`, `Sweep frequency="15"` for `SWEEP`, none for `TICK`. Cap: white ring, accent disc, black dot, drawn unchanged in ambient. All dial layers use `Variant mode="AMBIENT" target="alpha" value="120"`.

- [ ] **Step 1: Write failing tests**

```kotlin
class CoreXmlTest {
    private fun doc(d: DesignSettings) = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        .parse(FaceXml.watchFaceXml(d).byteInputStream())
    @Test fun defaultXmlIsWellFormed() { doc(DesignSettings()) }
    @Test fun accentReachesTheCapDisc() =
        assertTrue(FaceXml.watchFaceXml(DesignSettings(accent = "#FF1E6BFF")).contains("""<Fill color="#FF1E6BFF"/>"""))
    @Test fun noRedLeftWhenAccentChanges() =
        assertFalse(FaceXml.watchFaceXml(DesignSettings(accent = "#FF1E6BFF")).contains("E22B07", ignoreCase = true))
    @Test fun tickWidthIsUsed() =
        assertTrue(FaceXml.watchFaceXml(DesignSettings(minuteTickWidth = 5)).contains("""thickness="5""""))
    @Test fun quartersKeepOnlyFourNumerals() {
        val x = FaceXml.watchFaceXml(DesignSettings(numerals = NumeralMode.QUARTERS))
        assertEquals(4, Regex("""drawable/num_""").findAll(x).count())
    }
    @Test fun hiddenSecondHandHasNoSecondHandElement() =
        assertFalse(FaceXml.watchFaceXml(DesignSettings(secondHand = SecondHandStyle.HIDDEN)).contains("<SecondHand"))
    @Test fun manifestCarriesTheVersionCode() {
        val f = FaceXml.files(DesignSettings(), 42, emptyList())
        assertTrue(f.manifest.contains("""android:versionCode="42""""))
        assertTrue(f.resources.any { it.type == "xml" && it.name == "watch_face_info.xml" })
    }
    @Test fun invalidDesignIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { FaceXml.watchFaceXml(DesignSettings(accent = "red")) }
    }
}
```

- [ ] **Step 2:** Run `./gradlew :facegen:testDebugUnitTest --tests '*CoreXmlTest*'` — FAIL.
- [ ] **Step 3:** Implement. `watchFaceXml` calls `require(d.validate().isEmpty())` first. Use the existing `app/src/main/res/raw/watchface.xml` and `tools/build_face.py` as the reference for element syntax; also run Google's validator on the output in Step 4.
- [ ] **Step 4:** Run the tests — PASS. Then write the default design's XML to `build/outputs/default_watchface.xml` from a test and run `scripts/validate.sh`-style validation against it with `WFF_VERSION=3` (`java -jar tools/bin/wff-validator.jar 3 <file>`) — expect PASSED.
- [ ] **Step 5:** Commit `feat: core face XML sections`.

### Task 4: XML sections for movable slots and rings

**Files:** Modify `facegen/.../xml/SlotSections.kt`, `RingSections.kt`; Test `facegen/src/test/kotlin/.../xml/SlotRingXmlTest.kt`.

**Interfaces:** Consumes `SlotDesign`, `RingDesign`, `FaceXml` composition from Task 3. Produces `SlotSections.all`, `RingSections.all`. Ring segments get slot ids `100 + n` (never clash with movable ids 0..5).

Behaviour: each `SlotDesign` becomes a `ComplicationSlot` with `isCustomizable="TRUE"`, supported types `SHORT_TEXT RANGED_VALUE MONOCHROMATIC_IMAGE SMALL_IMAGE EMPTY`, a bounding shape per `shape` (`BoundingBox`, `BoundingOval`, `BoundingRoundBox`), an optional background (`PartDraw` rectangle, ellipse or rounded rectangle in `backgroundColor`) drawn first inside the slot, then icon (if `showIcons`), text at `complicationTextSize`, title at `complicationTitleSize` (if `showTitles`), colours from the slot. Default provider policy per slot name copies the four in `tools/build_face.py` (weather, battery, next prayer, steps). Rings: for `layout` N segments, segment i spans `(360 − N·gap)/N` degrees starting at `startAngle + i·(span + gap)`; each is a `ComplicationSlot` with `supportedTypes="RANGED_VALUE GOAL_PROGRESS SHORT_TEXT EMPTY"` and `BoundingArc` (centre 225,225, size 450, thickness, start and end angle); inside, a track `Arc` in `trackColor` and a value `Arc` whose sweep is `span × clamp((value−min)/(max−min), 0, 1)` using `[COMPLICATION.RANGED_VALUE_VALUE]`, `_MIN`, `_MAX` (verify these exact data-source names against the WFF reference and the emulator; if different, update the XML and this plan); `SHORT_TEXT` shows the text at the arc midpoint; colour from `colors[i % colors.size]`; round or flat `Stroke` caps. All slots dim in ambient with `Variant AMBIENT alpha 170`.

- [ ] **Step 1: Write failing tests**

```kotlin
class SlotRingXmlTest {
    private fun x(d: DesignSettings) = FaceXml.watchFaceXml(d)
    @Test fun defaultHasFourSlotsWithTheOldIds() {
        val s = Regex("""<ComplicationSlot slotId="(\d+)"""").findAll(x(DesignSettings())).map { it.groupValues[1] }.toList()
        assertEquals(listOf("0", "1", "2", "3"), s)
    }
    @Test fun movedSlotKeepsItsIdAndGetsNewBounds() {
        val moved = defaultSlots().map { if (it.id == 0) it.copy(x = 120, y = 90) else it }
        assertTrue(x(DesignSettings(slots = moved)).contains("""slotId="0" name="top" isCustomizable="TRUE"""))
        assertTrue(x(DesignSettings(slots = moved)).contains("""x="120" y="90""""))
    }
    @Test fun slotBackgroundIsDrawnInsideTheSlot() {
        val s = defaultSlots().map { if (it.id == 1) it.copy(backgroundColor = "#FF223344", shape = SlotShape.OVAL) else it }
        val xml = x(DesignSettings(slots = s))
        assertTrue(xml.contains("#FF223344")); assertTrue(xml.contains("<BoundingOval"))
    }
    @Test fun ringSegmentCountMatchesLayout() {
        for ((l, n) in listOf(RingLayout.FULL to 1, RingLayout.HALVES to 2, RingLayout.THIRDS to 3, RingLayout.QUARTERS to 4)) {
            val ids = Regex("""slotId="(1\d\d)"""").findAll(x(DesignSettings(slots = emptyList(), ring = RingDesign(l)))).count()
            assertEquals(l.name, n, ids)
        }
    }
    @Test fun ringUsesAnArcBoundingShapeAndClampsTheValue() {
        val xml = x(DesignSettings(slots = emptyList(), ring = RingDesign(RingLayout.QUARTERS)))
        assertTrue(xml.contains("<BoundingArc")); assertTrue(xml.contains("RANGED_VALUE_MIN"))
    }
    @Test fun noRingMeansNoArcSlots() = assertFalse(x(DesignSettings()).contains("<BoundingArc"))
    @Test fun titlesAndIconsCanBeTurnedOff() {
        val xml = x(DesignSettings(showTitles = false, showIcons = false))
        assertFalse(xml.contains("COMPLICATION.TITLE"))
        // icons stay only in the MONOCHROMATIC_IMAGE block of each of the 4 slots (default design has 12 uses: 3 per slot)
        assertEquals(4, Regex("""\[COMPLICATION.MONOCHROMATIC_IMAGE]""").findAll(xml).count())
        assertEquals(12, Regex("""\[COMPLICATION.MONOCHROMATIC_IMAGE]""").findAll(x(DesignSettings())).count())
    }
}
```

- [ ] **Step 2:** Run `./gradlew :facegen:testDebugUnitTest --tests '*SlotRingXmlTest*'` — FAIL.
- [ ] **Step 3:** Implement the sections.
- [ ] **Step 4:** Run the tests — PASS. Validate the XML of a design with `RingLayout.THIRDS` and one moved slot with the WFF validator (`WFF_VERSION=3`; use `4` if BoundingArc or an expression requires it, and then raise the manifest version property in Task 3 and the spec) — expect PASSED.
- [ ] **Step 5:** Commit `feat: movable slots and ring complications`.

### Task 5: Resource renderer and preview

**Files:** Create `facegen/.../render/{ResourceRenderer,PreviewRenderer}.kt`, copy `assets/fonts/Roboto-Regular.ttf` to `facegen/src/main/assets/`; Test `facegen/src/androidTest/kotlin/.../RenderTest.kt`.

**Interfaces:** Consumes `DesignSettings`. Produces `ResourceRenderer.render(d, ctx): List<FaceResource>` returning drawable PNGs `hour`, `minute`, `second` (colours from `handColor`, `inlayColor`, `secondHandColor ?: accent`; geometry copied from `tools/gen_assets.py`: hour 125, minute 197, width 17, inlay 8, chamfer 4, second 210 plus tail 49 and width 3.5, 4 px per reference px), `num_1`..`num_12` (white glyphs, rotation table from `NUMERAL_ROTATION`, size 28, supersampled), `background` when `PHOTO` (centre-cropped to 450×450 units rendered at 450×450 px, JPEG, quality stepped down until the encoded size is ≤ 350 KB, else throws `IllegalStateException("background too large")`), and `preview` (480×480 PNG of the whole face from `PreviewRenderer`). `PreviewRenderer.render(d, ctx, sizePx, hms = Triple(10, 9, 36)): Bitmap` draws background, ticks, numerals, slot boxes with sample text, rings with a 60% sample value, hands and cap, matching the XML geometry.

- [ ] **Step 1: Write failing device tests** (run with `./gradlew :facegen:connectedDebugAndroidTest` on the phone emulator): second hand image has the accent colour at its centre pixel; changing `handColor` changes `hour` bytes; `QUARTERS` still renders all 12 numeral PNGs only when needed (renderer returns only the numerals the mode uses); a 4000×3000 photo is reduced to ≤ 350 KB; preview is 480×480 and its centre pixel is not the background colour.
- [ ] **Step 2:** Run — FAIL. **Step 3:** Implement with `android.graphics` (supersample 4×, downscale). **Step 4:** Run — PASS.
- [ ] **Step 5:** Commit `feat: render hands, numerals, background and preview`.

### Task 6: `pack` native module

**Files:** Create `packjni/Cargo.toml`, `packjni/src/lib.rs`, `packjni/src/main/kotlin/.../PackNative.kt`, `packjni/build.gradle.kts` (a task that runs `cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o src/main/jniLibs build --release`), `docs/poc/pack-native.md` (how to install `cargo-ndk`, the Rust Android targets and the NDK path); Test `packjni/src/androidTest/kotlin/.../PackNativeTest.kt`.

**Interfaces:** Produces `PackNative.build(manifest, resources, pem): ByteArray` and `PackNative.generateKeysPem(): String`. Rust side depends on `pack-api` pinned to commit `7b60931` of `github.com/google/pack` (git dependency) and the `jni` crate; it builds `pack_api::Package { android_manifest, resources: Vec<FileResource> }` and calls `compile_and_sign_apk(pkg, Keys::from_combined_pem_string(pem)?)`; `generateKeysPem` serialises `Keys::generate_random_testing_keys()` to a combined PEM (check `pack-api` for the exported function; if none exists, generate an RSA 2048 key and self-signed certificate in Rust with the `rsa` and `x509-cert` crates already in pack's dependency tree).

- [ ] **Step 1: Write the failing device test:** build a minimal face (a manifest, `res/raw/watchface.xml` from `app/src/main/res/raw`, `res/xml/watch_face_info.xml`, `res/drawable/preview.png`) through `PackNative.build` with a freshly generated PEM and assert the result starts with the zip magic `PK` and is under 300 KB; assert two builds with the same PEM give the same signer certificate (read with `PackageManager.getPackageArchiveInfo` signing info or by comparing the certificate bytes in `META-INF`).
- [ ] **Step 2:** Run `./gradlew :packjni:connectedDebugAndroidTest` — FAIL. **Step 3:** Implement. **Step 4:** Run — PASS on arm64 emulator.
- [ ] **Step 5:** Commit `feat: pack native module`.

### Task 7: Packager, signing key and validation

**Files:** Create `facegen/.../{KeyStore,FacePackager}.kt`; Test `facegen/src/androidTest/kotlin/.../PackagerTest.kt`.

**Interfaces:** Consumes Tasks 3, 4, 5, 6. `KeyStore(context).pem(): String` generates through `PackNative.generateKeysPem()` on first use and stores the PEM in `EncryptedFile` (or `filesDir` with `MODE_PRIVATE` for the POC) and returns the same value afterwards. `FacePackager.build(d, versionCode)`: validate design (throws `IllegalArgumentException` listing errors), render resources, create `FaceFiles`, `PackNative.build`, write `files/face-<versionCode>.apk`, run `DwfValidatorFactory.create().validate(file, "com.seyedjafari.analogplus")`, throw `FaceBuildException(failures)` if any check fails, return `FaceBundle`. It also enforces the APK ≤ 400 KB cap.

- [ ] **Step 1: Write failing device tests:** default design builds and validates (token not blank, 10 checks pass); the same `KeyStore` returns the same PEM after the context is recreated; a design with `accent = "red"` throws `IllegalArgumentException`; a design with ring `THIRDS` plus a photo builds and validates; two builds with versionCodes 5 and 6 are signed by the same certificate.
- [ ] **Step 2:** Run `./gradlew :facegen:connectedDebugAndroidTest --tests '*PackagerTest*'` — FAIL. **Step 3:** Implement. **Step 4:** Run — PASS.
- [ ] **Step 5:** Commit `feat: face packager with persisted signing key and validation`.

### Task 8: Watch app

**Files:** Create `watch/src/main/kotlin/.../{MainActivity,PushController,ApplyReceiver}.kt`, `watch/src/main/AndroidManifest.xml`, `scripts/build_default_face.sh`, `watch/src/main/assets/default_watchface.apk` (generated); Test `watch/src/androidTest/kotlin/.../PushControllerTest.kt`.

**Interfaces:** Consumes `protocol`. `PushController(context)`: `suspend fun apply(apk: File, token: String, versionCode: Int): ResultMessage` — lists faces, `addWatchFace` if none is installed, otherwise `updateWatchFace` (never remove); rejects `versionCode` not greater than the installed one with `ResultMessage(false, …, "version not higher")`; `suspend fun activateOnce(): ResultMessage` requests nothing itself (the Activity requests the runtime permission) and calls `setWatchFaceAsActive`, remembering success in `SharedPreferences` so it is never called twice. `ApplyReceiver` is a `WearableListenerService` receiving the channel file and the `APPLY` message, then replying with `RESULT`. `MainActivity` shows status (installed version, active or not, last error) and a button to request the activation permission and activate. First launch installs `default_watchface.apk` with its token from `strings.xml` (`default_wf_token`) if no face is installed, and offers activation.

- [ ] **Step 1: Write the failing device test** (watch emulator, API 36): with the permission granted by `pm grant`, apply the checked-in test face, then apply a second with a higher version and assert one installed face with the higher version; applying a lower version returns `ok=false`; `activateOnce` called twice calls the system once.
- [ ] **Step 2:** Run `./gradlew :watch:connectedDebugAndroidTest` — FAIL. **Step 3:** Implement; `scripts/build_default_face.sh` builds the default face from the `app/` resources with the `pack-cli` binary, validates it with `validator-push-cli` and writes the token to `watch/src/main/res/values/default_token.xml`. **Step 4:** Run — PASS.
- [ ] **Step 5:** Commit `feat: watch app with push controller`.

### Task 9: Phone app

**Files:** Create `phone/src/main/kotlin/.../{MainActivity,EditorScreen,PreviewPane,ColourField,SlotDragEditor,RingTab,BackgroundTab,Sender,Store}.kt`, `phone/src/main/AndroidManifest.xml`; Test `phone/src/androidTest/kotlin/.../EditorTest.kt` (Compose UI tests).

**Interfaces:** Consumes `facegen` and `protocol`. `Store` keeps the current design, the saved designs and the version counter in `DataStore`/`SharedPreferences`. `Sender.send(bundle: FaceBundle): Flow<SendState>` sends the APK with `ChannelClient` on `Protocol.FILE_PATH`, then the `APPLY` message, and completes when `RESULT` arrives (timeout 60 s). The UI has tabs: Colours (hex field with a swatch, validation message, preset chips), Dial (tick width, length, five-minute dots, numerals mode, second hand), Hands, Background (solid, gradient, photo picker with dim slider), Complications (drag the slots on a circular canvas, resize, shape, background colour, 4 or 6 slots, cap indicator "n of 8"), Rings (layout, colours, thickness, gap, start angle, caps), Templates (built-in designs, apply), Share (copy link, scan or paste a link). A live preview (`PreviewRenderer`) is always visible and updates on every change. A "Send to watch" button runs `FacePackager.build` off the main thread, then `Sender`, and shows each stage and any error text.

- [ ] **Step 1: Write failing Compose tests:** typing `#FF1E6BFF` into the accent field changes the preview centre pixel; typing `red` shows the validation message and disables "Send to watch"; dragging a slot onto another is refused and the cap indicator shows "5 of 8" after choosing quarter rings with four slots; applying a template replaces the design; pasting a share link loads that design.
- [ ] **Step 2:** Run `./gradlew :phone:connectedDebugAndroidTest` — FAIL. **Step 3:** Implement. **Step 4:** Run — PASS.
- [ ] **Step 5:** Commit `feat: phone editor with live preview and send`.

### Task 10: End-to-end proof and README

**Files:** Create `scripts/poc_e2e.sh`, `docs/poc/README.md`; Modify `README.md` (a short "Builder POC" section).

- [ ] **Step 1:** `scripts/poc_e2e.sh` boots the phone emulator (API 33, Play image) and the watch emulator (API 36), installs both apps, grants the active permission, drives the phone app through `adb` intents to build and send three designs (default, blue accent with moved slot, quarter rings), and after each captures the watch screenshot into `docs/screenshots/poc/` and asserts the centre disc colour and the version number from the watch log.
- [ ] **Step 2:** Run it — PASS with three screenshots. Run `./gradlew testDebugUnitTest connectedDebugAndroidTest` — PASS.
- [ ] **Step 3:** Write `docs/poc/README.md`: how to install the phone and watch APKs on the owner's devices (`adb install`, wireless debugging), how to use the editor, known limits (the Data Layer needs the phone and watch paired; one activation per install; version counter).
- [ ] **Step 4:** Commit `docs: builder POC end-to-end proof and readme`.

## Self-review notes

- Spec coverage: free values (Tasks 2 to 4), movable slots and backgrounds (4, 9), rings (4, 9), photo background (3, 5, 9), templates and share link (2, 9), phone-side build, validation and persisted key (6, 7), watch push with one-time activation and never removing (8), default face bundled (8). Not covered, by design: digital and dual clocks, animation, community, web builder, store track, pixel-parity test.
- Unverified in this plan and checked during execution: the ring value data-source names (Task 4), whether `BoundingArc` or any expression needs WFF v4 (Task 4), the `pack-api` key-generation export (Task 6), Data Layer addressing with one shared application id (Task 8 and 9).
