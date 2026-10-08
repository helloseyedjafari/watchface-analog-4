# Commercial customizable Wear OS / Galaxy Watch watch face apps and stores (state as of 2026-10-08)

Method note: Play Store figures below were pulled directly from the live Play listing HTML on 2026-10-08 (US/en locale), so they are current. Anything dated before 2025 is marked **[STALE?]**. Items I could not verify are marked **[UNVERIFIED]**. I could not reach Reddit (search tool blocks the domain), Android Authority (Cloudflare block), or Facer's help center (Cloudflare block), so some of their content is reported second-hand via search-result summaries and flagged as such.

---

## 1. Which products exist, who makes them, and how users customize (preset themes, colour lists, free picker, sliders, layout editing, creator tools; watch vs phone vs web)

### Takeaway
The market is: (a) four large marketplace apps that Google named as Watch Face Push design partners (Facer, WatchMaker, Pujie, TIMEFLIK, plus Recreative, which now also has a marketplace/maker app), (b) thousands of single-face "indie" apps (Ballozi, Recreative, Sparkine, etc.) whose customisation is a designer-defined list of options edited on the watch, (c) first-party faces from Samsung and Google (Pixel Watch 5 now has a Gemini-based "Create" generator), and (d) creator tools (Facer Creator, WatchMaker Studio, Pujie designer, Samsung Watch Face Studio, Google Watch Face Designer for Figma) that are mostly aimed at designers rather than casual end users. Only Pujie, WatchMaker, TIMEFLIK and the new Recreative "AllFaces" app advertise an end-user "design your own face" editor on Wear OS; I found no source that confirms a free hex input, numeric sliders, or ring/arc complication authoring in any of them.

### Cited Findings

**Platform background (why the market reshuffled)**
- Google's Watch Face Push API (Wear OS 6+) is built for marketplace apps; works only with Watch Face Format (WFF) faces; Google says it was designed with Facer, TIMEFLIK, WatchMaker, Pujie and Recreative. — [Android Developers Blog, May 2025](https://android-developers.googleblog.com/2025/05/whats-new-in-watch-faces.html)
- Push API constraints: extra validation step/token per face; limited number of slots per app; separate permission to set the pushed face active. — [Android Developers Blog, May 2025](https://android-developers.googleblog.com/2025/05/whats-new-in-watch-faces.html)
- On Wear OS 6 each app gets ONE push slot; the Androidify sample overwrites the existing face with `updateWatchFace`; `setWatchFaceAsActive` needs a runtime permission, and once the user denies it the app cannot re-request it. — [Android Developers Blog, Dec 2025 (Androidify)](https://android-developers.googleblog.com/2025/12/bringing-androidify-to-wear-os-with.html)
- Androidify's pipeline is phone-side: WFF template + user data assembled into an APK with the "Pack" compiler running as a native library on the phone, validated by the Push validator (token), streamed to the watch with ChannelClient, "within seconds". — [Android Developers Blog, Dec 2025 (Androidify)](https://android-developers.googleblog.com/2025/12/bringing-androidify-to-wear-os-with.html)
- Android Central describes the same one-slot behaviour from the user side: each watch face app gets one slot in the favourites menu and each new pick overwrites the previous one. — [Android Central via Yahoo Tech, 4 Sep 2025](https://tech.yahoo.com/wearables/articles/tested-best-watch-face-apps-150400676.html)
- Google removed legacy (non-WFF) watch apps from Play on 14 Jan 2026; legacy-watch owners cannot renew subscriptions or buy faces via Play unless the watch moves to Wear OS 6. — [Facer Play listing](https://play.google.com/store/apps/details?id=com.jeremysteckling.facerrel); background in [Tom's Guide](https://www.tomsguide.com/wellness/smartwatches/google-is-dropping-support-for-legacy-wear-os-faces-heres-what-we-know) (only headline retrievable)
- WFF "ColorConfiguration" (user colour choice) is a designer-defined list of 1 to 100 `ColorOption` palettes; the docs describe no free colour picker or hex entry for the end user; introduced in Wear OS 4. — [WFF color configuration reference](https://developer.android.com/reference/wear-os/wff/user-configuration/color-configuration)
- WFF v4 (Wear OS 6) adds `PhotosConfiguration` (single image or gallery chosen by the user via the companion app, auto-advance or on tap), colour transforms (`extractColorFromColors`, `colorArgb`, `colorRgb`, `tintColor` on Group/Part) and a Reference element. — [Android Developers Blog, May 2025](https://android-developers.googleblog.com/2025/05/whats-new-in-watch-faces.html)

**Facer (Little Labs, Inc. / Facer Studios; Culver City, US)**
- Play: 10M+ downloads, 4.3 stars, ~191.9K ratings, updated 7 Oct 2026, "Contains ads", in-app purchases, "#2 top grossing personalization". — [Play listing](https://play.google.com/store/apps/details?id=com.jeremysteckling.facerrel)
- Claims 500,000+ faces; supports Wear OS and Apple Watch; WFF-based architecture introduced with Wear OS 6 (July 2025). Workflow: install Facer on phone AND paired watch, set Facer face on watch, change faces from the phone app. — [Play listing](https://play.google.com/store/apps/details?id=com.jeremysteckling.facerrel)
- Customisation of existing faces on Wear OS 6 happens via the system long-press "Customize" editor on the watch (theme colours, complications); a Facer Galaxy Watch 8 Classic user says phone-side editing worked "until recently" and now only watch-side editing works. — [Facer community thread, Aug 2026](https://community.facer.io/t/has-facer-disappeared-from-galaxy-wearable/103726)
- Creating your own face: Facer Creator (web, "without coding"), publish to Facer; Facer lists Wear OS, Apple Watch, Tizen, VEE Wear as targets. Creator is on the web; desktop/mobile not stated on the page. — [Facer Creator page](https://www.facer.io/creator), [Play listing](https://play.google.com/store/apps/details?id=com.jeremysteckling.facerrel)
- Facer July 2025 relaunch: direct sync from phone, "Power Impact" ratings per face, "Looks" social layer, Watch Band store, new Facer Creator "for designers". Editing workflows and colour options not described. — [Facer news, 24 Jul 2025](https://news.facer.io/massive-facer-update-wear-os-6-new-features-for-all/), [9to5Google, 24 Jul 2025](https://9to5google.com/2025/07/24/facer-wear-os-6-launch/)
- Sept 2026: storefront redesign (tabs for curated, brand partnerships, sport/activity), "Facer Originals" programme for independent designers (exclusive to Facer), Timex licence (Marlin, Expedition, T80 from fall 2026). — reported by [Android Authority, 30 Sep 2026](https://androidauthority.com/facer-design-refresh-timex-watch-face-3717209) (page blocked to me; via search summary) **[second-hand]**; Notebookcheck: [Timex x Facer](https://www.notebookcheck.net/Timex-and-Facer-team-up-to-bring-the-Marlin-Expedition-and-T80-to-Wear-OS-and-Apple-Watch.1412641.0.html)
- Historical creator tiers: free Creator, Creator Pro (colour customisation, interactivity, app launchers, statistics), invite-only Partner. **[STALE? 2024 forum]** — [Facer community](https://community.facer.io/t/introducing-facer-creator-pro/35365)
- Hands-on (Sept 2025): Creator lets you save designs in "My Designs" and push to the watch without publishing; at least 100 free faces; quality varies. — [Android Central via Yahoo](https://tech.yahoo.com/wearables/articles/tested-best-watch-face-apps-150400676.html)

**WatchMaker (androidslide / WATCH FACES & WIDGETS LIMITED, London)**
- Play: 5M+ downloads, listing shows 3.6 stars on 78.7K reviews (page JSON: 3.72 on 98.3K ratings, so the two counters disagree), updated 10 Sep 2026, v10.2.9, in-app purchases. — [Play listing](https://play.google.com/store/apps/details?id=slide.watchFrenzy)
- Listing claims 150,000+ faces and an in-app editor: "animations, complications, 3D elements, video backgrounds, stopwatches, calendars"; "change colors, layouts, hands, complications, fonts, backgrounds, tap actions". — [Play listing](https://play.google.com/store/apps/details?id=slide.watchFrenzy)
- WatchMaker's own announcement (as reposted): "reimagined" for Wear OS 6 using Watch Face Push, native in-app VIP subscription, thousands of faces re-engineered to WFF, "new customization tools" promised. — via search summary of [r/WatchMaker post](https://redlib.hbubli.cc/r/WatchMaker) **[second-hand; Reddit blocked]**
- Web: "WatchMaker Studio" editor ("free"), "Launch Studio", "New Desktop Editor" banner. — [getwatchmaker.com](https://getwatchmaker.com)
- Hands-on (Sept 2025): "New Watch" button starts a design; adding custom images/design tools needed the separate $12 Premium; free tier ~30 rotating faces that cannot be saved. — [Android Central via Yahoo](https://tech.yahoo.com/wearables/articles/tested-best-watch-face-apps-150400676.html)

**Pujie (Pujie, Amsterdam)**
- Two Play apps: "Pujie Watch Faces - Wear OS 6" (com.pujie.watchfaces; 100K+, 4.5 stars, ~2.8K ratings, updated 28 Jul 2026, v7.0.76) and "Wear OS 4-" (com.pujie.wristwear.pujieblack) for older watches. — [Wear OS 6 listing](https://play.google.com/store/apps/details?id=com.pujie.watchfaces), split announced in [tecnoandroid](https://www.tecnoandroid.it/news/dopo-facer-anche-pujie-e-watchmaker-supportano-wear-os-6-1602082/)
- Listing: "full custom watch face editor with fonts, colors, animations, and complications"; "design from scratch / customise existing / browse library"; 20+ free faces; Always-On support; Tasker integration; phone-battery complication data provider; publish own designs or import others'. Free = designer + 20+ faces; Premium = full library + "save unlimited creations". — [Play listing](https://play.google.com/store/apps/details?id=com.pujie.watchfaces)
- Designer is a rich vector-based editor (hands, backgrounds, complications built from parts; SVG import and clipping masks added in v6.1; separate ambient vs interactive styling). **[Pre-WFF (Wear OS 4 app), pre-2025 sources: STALE?; unknown which survive the WFF port]** — [9to5Google, Dec 2023](https://9to5google.com/2023/12/19/wear-os-watch-face-calculator-stopwatch/), [Droid Life 2019](https://www.droid-life.com/2019/07/10/pujie-black-available-for-samsung-galaxy-wearables/)
- Android Central: designer is "complex", for dedicated users; ~20 free faces; library smaller than competitors. — [Android Central via Yahoo](https://tech.yahoo.com/wearables/articles/tested-best-watch-face-apps-150400676.html)
- Android Authority via search summary: ambient and interactive modes styled separately; a "real-time simulator and animation preview" is described for the new app. — [tecnoandroid](https://www.tecnoandroid.it/news/dopo-facer-anche-pujie-e-watchmaker-supportano-wear-os-6-1602082/)

**TIMEFLIK (Apposter, Seoul; formerly "Mr Time")**
- Play: 10M+ downloads, 3.8 stars, ~68.7K ratings, updated 7 Oct 2026, ads + IAP, "Google Play Best for Wear Winner". — [Play listing](https://play.google.com/store/apps/details?id=com.apposter.watchmaker)
- Claims 2M+ faces and 50+ pro designers; "design your own watch faces ... customize with your photos or free templates like hands, subdials, battery status, even health features; steps, weather and UV index". Official Wear OS 6+ support, WFF. Needs the TIMEFLIK watch app set as the active face; "My Page > Settings" to switch to the new version on Wear OS 6. — [Play listing](https://play.google.com/store/apps/details?id=com.apposter.watchmaker)
- Editor runs in the phone app; free tier forces ~30 s ads before finalising and before sending to the watch (2023 review, **[STALE?]**). — [Play reviews](https://play.google.com/store/apps/details?id=com.apposter.watchmaker)

**Recreative (Representum Creative SRL, Romania)**
- Mainly paid/free single faces (RE01 to RE68...) customised on the watch via long-press "Customize" (e.g. RE30: 13 colour variations, 4 hand styles, 5 index styles, 3 complications, 2 shortcuts; 100K+ installs, 4.2 stars, 1.07K ratings, updated 19 Sep 2026). — [RE30 listing](https://play.google.com/store/apps/details?id=com.recreative.recreative30), [recreative-watch.com](https://www.recreative-watch.com/)
- New marketplace + maker app "Watch Face Maker - AllFaces" (com.recreative.recreativefaces): 50K+, 3.8 to 3.9 stars (page 3.8/131 reviews; JSON 3.85/218), updated 28 Aug 2026. Claims "build a custom design from scratch right on your device": asset library (animated stickers, backgrounds, fonts), "customize colors directly on your watch", complications (steps, battery, moon phase, HR, weather), WFF; free to build, premium via subscription or one-time payment (price not on page). Lists Pixel Watch 5 and Galaxy Watch 9. — [AllFaces listing](https://play.google.com/store/apps/details?id=com.recreative.recreativefaces)
- A Feb 2026 developer reply said "We will soon add a watch face builder", so the builder is new (months old) in this app. — [AllFaces reviews](https://play.google.com/store/apps/details?id=com.recreative.recreativefaces)

**Indie single-face apps (customise on the watch, designer-defined option lists)**
- Ballozi (BALLOZI Watch Faces, Philippines; built with Samsung Watch Face Studio): $1.99 one-off per face, Wear OS 5+; examples: EXQUIS 20 background colours, 4 hand/index colours, 2 editable complications, 4 hidden shortcuts; TREUN 2 10 backgrounds, 10 accents, 13 stripe colours, 3 complications; LUXOS 9 metal backgrounds, 4 editable complications. Installs 500+ to 1K+. Edit flow: long-press, Customize, swipe. — [EXQUIS](https://play.google.com/store/apps/details?id=com.watchfacestudio.ballozi.exquis.watchface), [TREUN 2](https://play.google.com/store/apps/details?id=com.watchfacestudio.ballozi.treun2.watchface), [LUXOS](https://play.google.com/store/apps/details?id=com.watchfacestudio.ballozi.luxos.watchface)
- Pilot ONE (SOOON): analog; free shows H:M:S only, premium adds 3 complications, 9 colour themes, 2 ambient modes; written for Wear OS 2.0. Listing returned "Not Found" when I fetched it on 2026-10-08 (possibly delisted or region-gated). **[STALE? Last data c. 2020; current status UNVERIFIED]** — [search listing](https://play.google.com/store/apps/details?id=ch.sooon.pilot&hl=en_GB&gl=US), [BlueStacks mirror, modified Feb 2020](https://www.bluestacks.com/campaign/ch.sooon.pilot/es)
- Photo-face indies: Muviz "Photo Watch Face - Wear OS 6+" (Sparkine; 5K+, 3.7 stars/36 ratings, 50+ colour palettes, 5 clock positions, photo dimness; last update 1 Sep 2025); "My PhotoWear Custom Watch Face" (100+ installs; claims gallery photos, crop, fonts, hands/dial styles, complications from a phone-side editor). — [Muviz](https://play.google.com/store/apps/details?id=com.sparkine.mwf.muvizphoto), [My PhotoWear](https://play.google.com/store/apps/details?id=myphotowearcustomwatchface.classicphotowearwatchface.familyphotowatchface)
- Catalog-only apps (no builder): "Galaxy Watch Faces" (Codemy; 50K+, 4.0/390; ads; users cite ~$8/month subscription; edit complications by long-press on the watch). — [listing](https://play.google.com/store/apps/details?id=com.watchland.studio.galaxy_watch_faces)

**First-party: Samsung**
- Galaxy Watch faces edit via long-press Customize on the watch or Galaxy Wearable on the phone; options differ per face (colour, dial, background, hands). "My Photo+" takes up to 20 photos in rotation. — [Samsung support (search summary)](https://www.samsung.com/uk/support/mobile-devices/how-to-customise-a-galaxy-smartwatch/) **[second-hand]**
- Samsung Watch Face Studio (free, Windows/macOS; Wear OS API 30+; WFF from v1.4.13) is Samsung's designer tool; Samsung's April 2026 blog documents a "Photo slot" component whose images are added by the end user in Galaxy Wearable (Customize, then "+"), one Photo slot per project, no stated image-count limit. — [WFS overview](https://developer.samsung.com/watch-face-studio/overview.html), [Samsung blog 28 Apr 2026](https://developer.samsung.com/watch-face-studio/blog/en/2026/04/28/personalized-watch-faces-with-photo-slot), [Engadget 27 Jul 2026](https://engadget.com/2218758/customize-samsung-galaxy-watch-free-graphic-design-tool-watch-face-studio)
- Galaxy Store as a seller channel for Wear OS faces: no 2026 evidence found; Samsung dev forum says apps for Wear OS Powered by Samsung are distributed on Play. Tizen distribution is discontinued (Samsung ended Tizen support Oct 2025 per Facer). — [Samsung dev page](https://developer.samsung.com/galaxy-watch/design/watch-designer), [Facer Play listing](https://play.google.com/store/apps/details?id=com.jeremysteckling.facerrel)

**First-party: Google Pixel Watch**
- Pixel Watch: long-press, Edit, swipe through colour/layout styles; Photos face holds up to 30 photos; "Create a watch face" (AI-assisted, text/voice prompt, needs Gemini-enabled personal Google Account and internet). — [Google Pixel Watch Help](https://support.google.com/googlepixelwatch/answer/12662644?hl=en)
- Pixel Watch 5 (Aug 2026) "Create" generator: three fixed layouts (vertical digital, vertical digital with complication arcs, horizontal with one complication), 10 to 20 s generation, "Try again"; could not produce an analog face; not extended to earlier models. — [9to5Google, 21 Aug 2026](https://9to5google.com/?p=726965); static output and "basic concepts only" per [Best Buy Canada review](https://blog.bestbuy.ca/wearable-technology/smartwatches-accessories/google-pixel-watch-5-review)
- Pixel Watch 5 adds colourable complications. — [9to5Google, 19 Aug 2026](https://9to5google.com/2026/08/19/pixel-watch-5-initial-review/)

### Inferences
- The marketplace trio (Facer / WatchMaker / TIMEFLIK) are catalog-first businesses whose creation tools are secondary; Pujie is the only one of the four whose Play pitch is "maker first". Recreative's AllFaces is a new, small entrant moving into the same space.
- The 1-slot Watch Face Push rule means any builder app on Wear OS 6 that replaces its face in a single slot will have the same "reconfigure complications on every switch" complaint that Facer users report, unless the app persists complication choices itself.
- Because a builder can generate a fresh WFF APK per design (the Androidify/Pack pattern), it is not bound by the stock `ColorConfiguration` palette-list model; I did not find any shipping product doing hex entry this way (see Q3).

### Gaps
- No Play listing or review I could reach describes the editor UI details (colour picker type, sliders, gauge/arc authoring) for Pujie, WatchMaker, TIMEFLIK or AllFaces; would need hands-on testing with each app.
- Facer's Wear OS 6 editor/Creator capabilities are only described at marketing level; Facer help center was Cloudflare-blocked to me.
- Galaxy Store policy for Wear OS faces in 2026: no authoritative source found.
- Pilot ONE current status: listing unreachable; data is 2020-era.
- OnePlus/Xiaomi/Oppo-specific first-party face tools not researched.

---

## 2. Pricing and monetisation, approximate installs/ratings, and recent status (Wear OS 6, active in 2026?)

### Takeaway
All large apps are freemium subscription or ad-supported with price points clustered around $5/month and $40/year (Facer, WatchMaker); Pujie is the outlier at about $2/month or $5 one-off. Every major marketplace app was updated within the last ~3 months (Facer and TIMEFLIK on 7 Oct 2026, WatchMaker 10 Sep 2026, Pujie 28 Jul 2026) and all advertise Wear OS 6 / WFF support.

### Cited Findings

| Product | Installs (Play) | Rating | Last updated | Wear OS 6 / WFF | Monetisation (best evidence) |
|---|---|---|---|---|---|
| Facer | 10M+ | 4.3 (~191.9K ratings) | 7 Oct 2026 | Yes (WFF rebuild July 2025) | Free + ads; Premium sub; per-face IAP ($1.99 examples) |
| WatchMaker | 5M+ | 3.6 to 3.7 (78.7K to 98.3K) | 10 Sep 2026 (v10.2.9) | Yes (claims; 130K+ faces converted) | Free + ads; VIP sub; separate Premium lifetime for tools (2025) |
| TIMEFLIK | 10M+ | 3.8 (~68.7K) | 7 Oct 2026 | Yes (listing) | Free + ads; "FLIK PASS" sub |
| Pujie (Wear OS 6 app) | 100K+ | 4.5 (~2.8K) | 28 Jul 2026 (v7.0.76) | Yes (WFF only) | Free designer + 20 faces; Premium |
| Recreative AllFaces | 50K+ | 3.8 to 3.9 (131 to 218) | 28 Aug 2026 | Yes | Free + ads/rewarded points; subscription or one-time premium |
| Recreative RE30 (single face) | 100K+ | 4.2 (~1.07K) | 19 Sep 2026 | Yes (WFF, API 33+) | Free; phone app has ads |
| Ballozi faces | 500+ to 1K+ each | 4.4 to 5.0 | Aug 2026 | Wear OS 5+ | $1.99 to $2.29 one-off |
| Muviz Photo Watch Face | 5K+ | 3.7 (36) | 1 Sep 2025 | Wear OS 6+ | Free |
| Galaxy Watch Faces (Codemy) | 50K+ | 4.0 (390) | 28 Sep 2026 | Wear OS 6+ | Ads + ~$8/mo sub (review) |

Sources for the table: [Facer](https://play.google.com/store/apps/details?id=com.jeremysteckling.facerrel), [WatchMaker](https://play.google.com/store/apps/details?id=slide.watchFrenzy), [TIMEFLIK](https://play.google.com/store/apps/details?id=com.apposter.watchmaker), [Pujie](https://play.google.com/store/apps/details?id=com.pujie.watchfaces), [AllFaces](https://play.google.com/store/apps/details?id=com.recreative.recreativefaces), [RE30](https://play.google.com/store/apps/details?id=com.recreative.recreative30), [Ballozi EXQUIS](https://play.google.com/store/apps/details?id=com.watchfacestudio.ballozi.exquis.watchface), [Muviz](https://play.google.com/store/apps/details?id=com.sparkine.mwf.muvizphoto), [Galaxy Watch Faces](https://play.google.com/store/apps/details?id=com.watchland.studio.galaxy_watch_faces). Counts read from live listing HTML on 2026-10-08; Play shows install buckets, not exact numbers.

**Facer pricing**
- Premium regular $39.99/year; launch promo $14.99 first year (one passage also says "$19.99 introductory"; Facer's own page is internally inconsistent); 50% off watch bands for new annual subscribers; promo deadline 30 Sep 2025 has passed. — [Facer news, Jul 2025](https://news.facer.io/massive-facer-update-wear-os-6-new-features-for-all/), [Facer community, Premium deal](https://community.facer.io/t/details-on-premium-deal-for-wear-os-5-users-waiting-for-wear-os-6/97900)
- Android Central (Sept 2025): Premium $40/yr or $5/mo. — [Android Central via Yahoo](https://tech.yahoo.com/wearables/articles/tested-best-watch-face-apps-150400676.html)
- Premium does not cover all faces: Facer's reply to a complaint states "some individually priced faces may not be included". — [Facer Play reviews, Sep 2026](https://play.google.com/store/apps/details?id=com.jeremysteckling.facerrel)
- Creator Pro (design tool) $49.99/yr **[STALE? May 2024 forum]**. — [Facer community](https://community.facer.io/t/introducing-facer-creator-pro/35365)

**WatchMaker pricing**
- Current web paywall (fetched 2026-10-08): $39.99/year (labelled "save 33%") or $4.99/month, benefits: all faces, "Customize Any Watch Face", weekly designer packs, ad-free; a "Summer sale, 50% off" banner was showing. — [getwatchmaker.com/premium](https://getwatchmaker.com/premium)
- Android Central (Sept 2025): VIP $20/yr or $5/mo; separate Premium $12 lifetime needed for custom images/design tools; reviewer found the split "confusing". — [Android Central via Yahoo](https://tech.yahoo.com/wearables/articles/tested-best-watch-face-apps-150400676.html)
- Old one-off Premium $7.99 **[STALE? 2020 review]**. — [Play review](https://play.google.com/store/apps/details?id=slide.watchFrenzy)

**TIMEFLIK pricing**
- Play listing: first-year subscription $19.99 ("$71.99 value"), 7-day trial mentioned in earlier listing. — [Play listing](https://play.google.com/store/apps/details?id=com.apposter.watchmaker)
- Android Central (Sept 2025): Flik Pass Pro $60/yr or $5/mo; ad before every face sent to the watch unless paying $2.49/mo. — [Android Central via Yahoo](https://tech.yahoo.com/wearables/articles/tested-best-watch-face-apps-150400676.html)

**Pujie pricing**
- Android Central (Sept 2025): Premium $2/month or $5 one-time. — [Android Central via Yahoo](https://tech.yahoo.com/wearables/articles/tested-best-watch-face-apps-150400676.html)
- Xataka (May 2025, covers Wear OS 4 app): 3.99 EUR one-time, no free version. **[Pre-WOS6; STALE?]** — [Xataka Android](https://www.xatakandroid.com/gadgets-android/no-te-conformes-que-trae-tu-reloj-tres-maneras-faciles-crear-tus-propias-watch-faces-para-wear-os)
- Third-party aggregator shows 272,811+ downloads, 4.1 stars, last updated 23 Sep 2025 for the same app; this conflicts with the live Play data above (100K+, 4.5, 28 Jul 2026), so treat the aggregator as stale/unreliable. — [grand-screen.com](https://grand-screen.com/apps/pujie-watch-faces-wear-os-6/)

**Wear OS 6 status per vendor**
- Facer: Wear OS 6 support July 2025; legacy app can't be newly installed via Play since 14 Jan 2026. — [Play listing](https://play.google.com/store/apps/details?id=com.jeremysteckling.facerrel)
- Pujie: split into Wear OS 6+ (WFF only) and Wear OS 4- apps. — [tecnoandroid](https://www.tecnoandroid.it/news/dopo-facer-anche-pujie-e-watchmaker-supportano-wear-os-6-1602082/)
- WatchMaker: one app, only WFF faces shown on Wear OS 6; 130,000+ faces converted per Play (as of Aug 2025). — [tecnoandroid](https://www.tecnoandroid.it/news/dopo-facer-anche-pujie-e-watchmaker-supportano-wear-os-6-1602082/)
- Supported-watch lists in Play listings now include Galaxy Watch 8/Ultra 2025, Pixel Watch 4 (WatchMaker, Pujie, Facer), and Pixel Watch 5 / Galaxy Watch 9 (AllFaces). — respective Play listings above

### Inferences
- A one-off or low-monthly price (Pujie) coexists with $40/yr incumbents; the sub-$10 one-off model is viable in this niche.
- Facer and TIMEFLIK ranks (10M+) vs Pujie (100K+) suggest end users mostly want a catalog; the maker-first segment is two orders of magnitude smaller in installs. The Wear OS 6-only Pujie install base is also naturally capped by the Wear OS 6 reset.
- TIMEFLIK's ad-per-send and WatchMaker's Premium-vs-VIP split are monetisation friction points a new entrant can avoid.

### Gaps
- Exact current Pujie, Recreative AllFaces and TIMEFLIK prices not on Play pages (Play shows IAP only in a collapsed section I could not scrape). Android Central figures are Sept 2025 and may have moved.
- Galaxy Store listings/prices: not retrieved; no evidence Galaxy Store sells Wear OS faces.
- Sensor Tower/AppBrain-style download estimates not obtained.

---

## 3. Which products already offer: free hex colour input, numeric sliders, user-movable complications, ring/arc complications, per-slot backgrounds, photo backgrounds, onboarding wizards

### Takeaway
I found no source confirming free hex colour input, numeric sliders for sizes/thickness, or user-movable (repositionable) complications in any shipping Wear OS app; user-facing options across stock faces are discrete designer-defined lists. Photo backgrounds exist (Samsung Watch Face Studio photo slot, Samsung My Photo+, Pixel Photos face, TIMEFLIK and Muviz and My PhotoWear), and the maker-class apps (Pujie, WatchMaker, TIMEFLIK, AllFaces) imply layout freedom, but their listings do not document hex entry, sliders or arc complications. This is a market-wide evidence gap rather than proof of absence.

### Cited Findings

Feature matrix. Legend: Y = documented in a source; N = documented as absent; ? = no evidence found (not verified either way).

| Capability | Facer | WatchMaker | Pujie (WOS6) | TIMEFLIK | Recreative AllFaces | Samsung stock/WFS | Pixel Watch 4/5 | Indie single-face (Ballozi/RE) |
|---|---|---|---|---|---|---|---|---|
| Free hex / free colour picker for end user | ? | ? | ? | ? | ? ("customize colors") | N for stock option lists | ? (5: complication colour) | N (fixed lists: 9 to 20 colours) |
| Colour from curated palettes | Y (face theme pickers) | Y (colour per face) | Y (editor lists "colors") | Y | Y | Y | Y | Y |
| Numeric sliders (size/length/thickness) | ? | ? | ? | ? | ? | ? | N | N |
| User-movable complications | ? | ? ("layouts") | ? ("layout") | ? | ? | N (fixed slots) | N | N |
| Editable complication slots (content, not position) | Y (limited, re-set per face switch) | Y | Y | ? | Y | Y | Y | Y (2 to 5 slots) |
| Ring/arc complications | ? | ? | ? | ? | ? | Y in WFS/WFF (designer-side) | Y (Pixel Watch 5 AI layout "complication arcs") | partial (arc/subdial progress, designer-defined) |
| Per-slot backgrounds | ? | ? | ? | ? | Y (asset library backgrounds) | Y (styles) | Y | Y (10 to 20 backgrounds) |
| Photo background | ? | Y ("video backgrounds", images need Premium) | ? | Y | ? ("hundreds of images" in library) | Y (My Photo+, WFS photo slot) | Y (Photos face, up to 30) | Muviz Y, My PhotoWear Y |
| Onboarding wizard | Y (onboarding in Facer app is a precondition for the Premium deal) | ? (mentions sign-up form for subscription rollout) | ? | Y (stepwise "install watch app, set face, connect") | ? | Y (Wearable app) | Y (Pixel Watch app "Create" guided categories) | N |

Sources by cell:
- Facer onboarding as precondition: [Facer community Premium deal](https://community.facer.io/t/details-on-premium-deal-for-wear-os-5-users-waiting-for-wear-os-6/97900); Facer complications re-set on each face change: [Facer community thread](https://community.facer.io/t/wearos-6-need-to-reconfig-complications-on-face-change/101502).
- WatchMaker video backgrounds, complications, layouts, colours, fonts (marketing): [Play listing](https://play.google.com/store/apps/details?id=slide.watchFrenzy); images require Premium: [Android Central via Yahoo](https://tech.yahoo.com/wearables/articles/tested-best-watch-face-apps-150400676.html).
- Pujie: [Play listing](https://play.google.com/store/apps/details?id=com.pujie.watchfaces); a May 2026 reviewer could not find how to start a new face from scratch in v7 (UI discoverability).
- TIMEFLIK: photos/templates/hands/subdials: [Play listing](https://play.google.com/store/apps/details?id=com.apposter.watchmaker).
- Recreative AllFaces: [listing](https://play.google.com/store/apps/details?id=com.recreative.recreativefaces).
- Samsung photo: [WFS Photo slot blog](https://developer.samsung.com/watch-face-studio/blog/en/2026/04/28/personalized-watch-faces-with-photo-slot); My Photo+: [Samsung support, via search summary](https://www.samsung.com/uk/support/mobile-devices/how-to-customise-a-galaxy-smartwatch/).
- Pixel: [Pixel Watch Help](https://support.google.com/googlepixelwatch/answer/12662644?hl=en); layouts with "complication arcs": [9to5Google](https://9to5google.com/?p=726965).
- Indie option counts: Ballozi listings in Q1.
- Samsung WFS "arc complications": Samsung dev forum says arc complications are not doable in WFS and need Android Studio/WFF directly. — [Samsung forum thread](https://forum.developer.samsung.com/t/how-to-add-arc-complications/28832) (via search snippet; undated, **[STALE? possibly pre-2025]**)

Format-level facts relevant to a builder:
- WFF user config is palette-list based (max 100 options); photos via `PhotosConfiguration` (v4). — [WFF color config](https://developer.android.com/reference/wear-os/wff/user-configuration/color-configuration), [Android Developers Blog](https://android-developers.googleblog.com/2025/05/whats-new-in-watch-faces.html)
- WFF user-changeable options are edited via the watch's system Watch Face Editor; a pushed/generated face can carry baked-in values from the phone (Androidify, Palette example passes colours via a complication data pipe: TEXT field with space-delimited RGB hex values; three colours extracted with `extractColorFromColors()`). — [Android Developers Blog, Aug 2025](https://android-developers.googleblog.com/2025/08/further-explorations-with-watch-face-push.html), [Support personalization](https://developer.android.com/training/wearables/wff/personalization)
- Google's Figma "Watch Face Designer" plugin uses hex colours in design. — [Basics of Watch Face Designer](https://developer.android.com/training/wearables/watch-face-designer/basics)
- Facer on Wear OS 6: some data/complications unavailable (weather, location, phone battery on some faces), no 3D, less interactivity (Facer help, via search summary). — [Facer help FAQ](https://help.facer.io/hc/en-us/articles/46283550815643-FAQ-Wear-OS-Update-Watch-Face-Compatibility-Purchases), [Facer compat article](https://help.facer.io/hc/en-us/articles/40760314555803-Wear-OS-watch-compatibility-with-Facer-i-e-Galaxy-Watch-7-Pixel-Watch-etc) **[second-hand; pages Cloudflare-blocked]**

### Inferences
- If hex entry, numeric sliders, repositionable complications and arc complications are not documented anywhere, a builder with all four plus phone-side live preview and a guided wizard would be differentiated on paper; but the absence of documentation is weaker evidence than a hands-on audit, so treat the "none offers it" claim as unproven.
- The most likely closest competitor on feature depth is Pujie (full vector editor heritage) and potentially WatchMaker Studio; both need hands-on testing before concluding.
- Pixel Watch 5's AI generator proves Google is investing in "create your own" but with fixed layouts and no analog, leaving a gap a deterministic builder can fill (inference from the 9to5Google review).
- Ring/arc complications are available to designers (Pixel layouts, WFS/WFF) but nothing shows end-users authoring them.

### Gaps
- Hands-on verification of Pujie, WatchMaker Studio, TIMEFLIK editor, AllFaces maker for hex input, sliders, drag-to-move, arcs, wizard: not possible from listings; recommend installing each (free tiers) on a Wear OS 6 device or emulator.
- Samsung Galaxy Store as a face builder surface: none found.
- No documentation of arc "thirds/quarters/halves" style complication authoring in any app.

---

## 4. Recurring user complaints per product

### Takeaway
Dominant complaints: (1) Facer on Wear OS 6: one face at a time, complications must be re-set on each switch, many purchased faces now "Not Available", features lost to WFF, support non-responsive/premium-vs-paid confusion; (2) WatchMaker: customisations not saved, regression in customisation, crashes and no support, confusing Premium vs VIP; (3) TIMEFLIK: ads before every send, slow/inaccurate data, colours not transferring; (4) Pujie: discoverability of "new face" in v7, legacy faces not migrating; (5) catalog apps mislabel paid faces as free.

### Cited Findings

**Facer**
- Wear OS 6 version supports only a single face on the watch; complications must be re-configured every time the face changes; a user says they would not use the app because of it (1 Oct 2025); Facer reportedly "working on it" (second-hand). — [Facer community](https://community.facer.io/t/wearos-6-need-to-reconfig-complications-on-face-change/101502)
- Many purchased faces show "Not Available" after upgrading to Wear OS 6 / One UI 8; user reports losing about $30; removed features include VAR, Chronograph, GMT hand (Dec 2025). — [Facer community](https://community.facer.io/t/upgrading-to-wearos6-options/101989)
- Long-press Customize misbehaving on Galaxy Watch 7 / Wear OS 6 (flicker, blank face), tap gestures (3x face switcher, 5x QR) not working, "no quick switching between recent faces", "tedious colour changes" (Dec 2025 to Jan 2026). — [Facer community](https://community.facer.io/t/bug-long-press-on-facer-faces-on-my-gw7-wos6-not-working/102146)
- Aug 2026: Facer face no longer listed in Galaxy Wearable watch faces; can't be edited from phone, only from the watch; confusion between duplicate versions of the same face. — [Facer community](https://community.facer.io/t/has-facer-disappeared-from-galaxy-wearable/103726)
- Wear OS 6 beta (Jul 2025): old tags don't work, phone battery/sunrise/sunset/humidity/pressure/wind unsupported, creation needs a compilation step, platform filter hides Wear OS 6 faces. — [Facer beta thread](https://community.facer.io/t/facer-wear-os-6-beta-testing-overview-release-notes/97851?page=3)
- Play reviews Sept 2026: "watch faces don't stay in my watch ... have to re-download"; "free" section contains paid faces; "paid for yearly premium ... still tries to charge me for them"; support unresponsive; help centre confusing. Facer replies it will pass feedback on and says some priced faces are not included in Premium. — [Facer Play listing](https://play.google.com/store/apps/details?id=com.jeremysteckling.facerrel)
- Facer admits it previously lacked real Wear OS-newer-version support for about a year. — [Facer community post](https://community.facer.io/t/details-on-premium-deal-for-wear-os-5-users-waiting-for-wear-os-6/97900)

**WatchMaker**
- 7 Sep 2026 review: customised face does not save; recent update removed angle adjustment; developer apologised. — [Play listing](https://play.google.com/store/apps/details?id=slide.watchFrenzy)
- Crashes on Pixel Watch, no support reply (Feb 2024, 59 helpful votes) **[STALE?]**; "scam" complaint that user-made faces became paid, ads even after paying (2020) **[STALE?]**. — [Play reviews](https://play.google.com/store/apps/details?id=slide.watchFrenzy)
- Premium-vs-VIP split "feels like paying twice"; free faces rotate daily and can't be saved; main menu mostly links to other apps (Sept 2025). — [Android Central via Yahoo](https://tech.yahoo.com/wearables/articles/tested-best-watch-face-apps-150400676.html)
- Overall rating 3.6 to 3.7 is the lowest among the big four. — [Play listing](https://play.google.com/store/apps/details?id=slide.watchFrenzy)

**TIMEFLIK**
- Ad (about 30 s) before finalising and again before sending a design to the watch on the free tier (Nov 2023, 126 helpful) **[STALE?]**; still ad-per-send per Android Central, Sept 2025. — [Play reviews](https://play.google.com/store/apps/details?id=com.apposter.watchmaker), [Android Central via Yahoo](https://tech.yahoo.com/wearables/articles/tested-best-watch-face-apps-150400676.html)
- Custom digital time colours (blue) arrive black/white on the watch (Jan 2024, 108 helpful) **[STALE?]**; app slows with use and shows inaccurate steps/HR/temperature (Dec 2022) **[STALE?]**; complications don't link back to the watch app (Nov 2023) **[STALE?]**. — [Play reviews](https://play.google.com/store/apps/details?id=com.apposter.watchmaker)
- Only one face stored, so ads recur even for previously used faces. — [Android Central via Yahoo](https://tech.yahoo.com/wearables/articles/tested-best-watch-face-apps-150400676.html)
- Requires installing a separate watch app and keeping it as active face; no Huawei/Xiaomi/Fitbit/Garmin. — [Play listing](https://play.google.com/store/apps/details?id=com.apposter.watchmaker)

**Pujie**
- May 2026: reviewer spent over an hour in v7 unable to find a way to create a new watch face from scratch (no + button/menu). — [Pujie Play reviews](https://play.google.com/store/apps/details?id=com.pujie.watchfaces)
- Dec 2025: old primary face not compatible with the new version and "upgrading it is not easy"; smaller catalogue than the old app. — [Pujie Play reviews](https://play.google.com/store/apps/details?id=com.pujie.watchfaces)
- Aug 2026: many premade faces not yet updated for Wear OS 6/7; incompatible elements show a warning triangle and can be removed/replaced; reviewer likes the "make it yourself" focus (positive). — [Pujie Play reviews](https://play.google.com/store/apps/details?id=com.pujie.watchfaces)
- Android Central: designer "complex" (steep learning curve); Xataka: steep learning curve. — [Android Central via Yahoo](https://tech.yahoo.com/wearables/articles/tested-best-watch-face-apps-150400676.html), [Xataka](https://www.xatakandroid.com/gadgets-android/no-te-conformes-que-trae-tu-reloj-tres-maneras-faciles-crear-tus-propias-watch-faces-para-wear-os)
- Aggregator "Cons": confusing interface at first, can be slow on older watches (low-confidence summary). — [grand-screen.com](https://grand-screen.com/apps/pujie-watch-faces-wear-os-6/)

**Recreative / AllFaces / indie**
- AllFaces (Aug 2026): ads fail to load for reward points, sign-in problems, streak wiped. — [AllFaces reviews](https://play.google.com/store/apps/details?id=com.recreative.recreativefaces)
- RE30 (2024 reviews) **[STALE?]**: font too small, shortcuts should also offer complication option, z-order of shortcuts vs complications over hands. — [RE30 reviews](https://play.google.com/store/apps/details?id=com.recreative.recreative30)
- Muviz Photo face (Dec 2025): photo-selection option missing/not appearing on Pixel Watch 4 app and watch; can't re-centre/crop images manually; can't remove from watch. — [Muviz reviews](https://play.google.com/store/apps/details?id=com.sparkine.mwf.muvizphoto)
- Galaxy Watch Faces (Aug 2026): 2 to 3 minutes of ads to apply a face; confusion about whether complications are editable; ~$8/mo subscription. — [listing](https://play.google.com/store/apps/details?id=com.watchland.studio.galaxy_watch_faces)

**Cross-cutting (Samsung dev forum)**
- Customisation UI lag in the phone's Galaxy Wearable editor (about 1 s per option; a face with 13 settings took 18 to 20 s to load) **[STALE? undated forum thread]**. — [Samsung forum](https://forum.developer.samsung.com/t/when-will-the-customization-lagging-on-the-phone-be-fixed/42263)
- Newly restored/set-up watches may not offer older faces; Google support notes some older watch faces may not transfer. — [Google Wear OS Help](https://support.google.com/wearos/answer/14912500)

**Pixel Watch 5 generator**
- Output static, "basic concepts only", 3 fixed layouts, no analog face even when requested. — [9to5Google](https://9to5google.com/?p=726965), [Best Buy Canada](https://blog.bestbuy.ca/wearable-technology/smartwatches-accessories/google-pixel-watch-5-review)

### Inferences
- The recurring structural complaint (single slot, complications reset on switch, faces not persisted) is a direct consequence of Watch Face Push's one-slot-per-app rule and applies to any app built on it; a builder app should treat "persist my design and complication choices across switches" as a day-one feature.
- Monetisation complaints (ads per send, "free" faces that are paid, Premium that doesn't include everything) cluster around catalog-subscription models, less around a tool-first one-time price.
- Pujie complaints are about discoverability/onboarding, supporting the value of a guided wizard.

### Gaps
- Reddit (r/WearOS, r/GalaxyWatch, r/WatchMaker) threads could not be read: the search tool blocks reddit.com; only second-hand search summaries and mirror hits were available.
- XDA and Android Police 2025-2026 roundups not retrieved; Android Authority pages blocked.
- Only the three most recent Play reviews per app are rendered in the listing HTML, so frequency counts of complaint types are not available.
