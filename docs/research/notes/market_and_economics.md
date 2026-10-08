# Market size, platform trajectory and economics for a customizable Wear OS watch face builder (state as of 2026-10-08)

Method note: roughly 20 tool calls (searches plus fetches). Several primary sources returned 403/404 (Android Authority, Wareable, Samsung forum), so some claims rest on search-result summaries; these are marked "(search summary only, not opened)". Counterpoint, IDC, Canalys and Statista OS-level numbers are paywalled and I did not obtain them. No figure below is invented.

## 1. Wear OS install base, market share, Wear OS 6 vs older, upgrade status

### Takeaway
No public absolute Wear OS install base exists (Google does not publish it). The only dated share signals show Wear OS shipments declining in 2025 because of Samsung, with Google's Pixel Watch 4 expected to drive a partial recovery. Samsung held 5% of global smartwatch shipments in Q1 2026 (down from 7%). Wear OS 7 already started rolling out to Pixel watches in 2026, so "Wear OS 6" is no longer the newest target.

### Cited Findings
- Counterpoint Q1 2026 global smartwatch shipment share: Apple 23% (20% in Q1 2025), Huawei 17% (16%), Samsung 5% (7%). Wear OS is not named; Google is not listed separately (likely in "others"). Published 2026-06-18 by Android Headlines, relaying Counterpoint. Secondary source. — [Android Headlines](https://www.androidheadlines.com/?p=1290915)
- Counterpoint June 2025 update: Wear OS shipments had a "significant double-digit decline" in Q1 2025 due to Samsung smartwatch shipment decline; Samsung is the majority of Wear OS shipments. — [Counterpoint, June 2025 update](https://www.counterpointresearch.com/en/reports/smartwatch-shipment-forecast-by-operating-system-june-2025-update) (summary text via search result)
- Counterpoint October 2025 update (published 2025-10-17): Wear OS shipments declined year on year on lower Samsung shipments; growth expected to slow further as brands like Xiaomi move to their own OS. Report body is subscription-only. — [Counterpoint, Oct 2025 update](https://counterpointresearch.com/en/reports/global-smartwatch-shipment-forecast-by-operating-system-october-2025-update) (summary from search results, not the paywalled body)
- Counterpoint December 2025 update: Q3 2025 decline retained; Wear OS growth expected in coming years driven by Google smartwatches after the Pixel Watch 4. — [Counterpoint, Dec 2025 forecast](https://counterpointresearch.com/jp/reports/global-smartwatch-shipment-forecast-by-os-december-2025) (search summary only)
- Counterpoint (via 9to5Google) projection, excluding China: Wear OS 21% share in 2023, projected 27% in 2024 (a projection, made before the 2025 decline). — [9to5Google](https://9to5google.com/?p=617549) (search summary only)
- Counterpoint Q2 2026 global smartwatch analysis report exists (dated 2026-08-31) but is paywalled; I did not obtain figures. — [Counterpoint](https://counterpointresearch.com/de/reports/global-smartwatch-market-analysis-q2-2026)
- Google last gave Wear OS growth only as relative figures (3x active devices after Wear OS 3, I/O 2022 and CES 2023), never absolute totals. — [9to5Google, I/O 2022 numbers](https://9to5google.com/2022/05/11/google-io-2022-numbers/); [SamMobile](https://www.sammobile.com/news/wear-os-3-3x-more-active-wear-os-smartwatches/)
- Wear OS 6 / One UI 8 Watch timeline: Galaxy Watch Ultra got it first on 2025-07-22; Pixel Watch 2 and 3 on 2025-10-08; Galaxy Watch 7 regionally from 2025-10-14; Wear OS 6.1 (Android 16 QPR2) released 2026-04-01. — [9to5Google Wear OS 6 guide](https://9to5google.com/guides/wear-os-6/)
- Original Pixel Watch (2022) never gets Wear OS 6; stays on Wear OS 5.1 (past its three-year window), still gets Play app updates. — [heise](https://heise.de/-10751312); [9to5Google](https://9to5google.com/2025/10/08/pixel-watch-wear-os-6-update/) (search summaries)
- Pixel Watch 2 gets Wear OS 6 but not the new features. — [Tech Advisor](https://www.techadvisor.com/article/2935932/wear-os-6-update-coming-to-pixel-watch-2-and-3.html) (search summary)
- Wear OS 5 issue: watches launching with Wear OS 5 (e.g. Galaxy Watch 7, Pixel Watch 3) could only install Watch Face Format (WFF) faces; watches upgraded from Wear OS 3/4 retained non-WFF face access "for now". — [Android Authority / 9to5Google results](https://9to5google.com/2024/12/07/wear-os-watch-faces-no-longer-work/) (search summary only)
- Wear OS 7 announced at I/O 2026 (2026-05-19); Canary build for developers that day; "later this year" for devices. — [9to5Google](https://9to5google.com/2026/05/19/google-announces-wear-os-7/)
- Wear OS 7 has begun rolling out to Pixel Watch 2, 3 and 4 (original Pixel Watch excluded); Samsung One UI 9 Watch (Wear OS 7) was beta/unconfirmed in sources dated around June 2026; one analysis expects non-Google hardware to take well into 2027. Sources are June-ish 2026, so status at October 2026 is unconfirmed. — [Wareable, Wear OS 7 rollout](https://www.wareable.com/android-wear/google-wear-os-7-official-rollout-which-pixel-watch-models); [Notebookcheck](https://www.notebookcheck.net/Samsung-Galaxy-Watch-7-and-8-could-soon-receive-One-UI-9-Watch-based-on-Wear-OS-7.1348968.0.html) (search summaries only)
- Pixel Watch 5 announced 2026-08-12; runs Wear OS 7 (Android 17) per one report. — [Tech Advisor / search results](https://www.techadvisor.com/article/3213107/the-pixel-watch-5-has-a-surprising-list-of-exclusive-features.html) (search summary)
- Counterpoint/Samsung context: Samsung is the largest Wear OS vendor, with Galaxy Watch 4 expected to receive Wear OS 6 (July 2025 report) and Watch 5 / 5 Pro "next in line"; I could not confirm completion. — [9to5Google guide](https://9to5google.com/guides/wear-os-6/); [Wareable](https://www.wareable.com/android-wear/google-wear-os-6-new-features-which-watches-rollout) (search summary)
- OnePlus Watch 2/3: Wear OS 6 update promised for 2026 with no date (search summary only, older source). — [Wareable](https://www.wareable.com/android-wear/google-wear-os-6-new-features-which-watches-rollout)

### Inferences
- Wear OS is a shrinking-to-flat, Samsung-dependent pool in 2025-26. Any addressable market estimate must be built from vendor shipments, not a Google-published install base.
- Because the pool is split across Wear OS 3/4/5 (older Galaxy Watch 4/5, TicWatch, Fossil-era devices), 6, and now 7, a Wear OS 6-only app excludes a meaningful tail; but Watch Face Push needs Wear OS 6+, so the target floor is hard-set by that API. By late 2026 the 6+ set likely covers Pixel 2/3/4/5, Galaxy Watch 4 onward (if updated), Watch Ultra, FE, 7, 8 (unverified for each).
- Wear OS 7 is arriving; a Wear OS 6 target will be a floor, not a ceiling, for the rest of 2026.

### Gaps
- No absolute active-device count for Wear OS from Google, Counterpoint, IDC, Canalys or Statista that I could open. IDC and Canalys 2025-26 Wear OS-specific numbers not found.
- No Wear OS 6 vs 5 vs older distribution (Play/device dashboard) found.
- Samsung's share within Wear OS: only qualitative ("majority") from Counterpoint.
- Completed status of Galaxy Watch 4/5 and One UI 9 Watch rollout as of October 2026 unconfirmed.
- Which watches cannot get Wear OS 6: confirmed only Pixel Watch 1; others (TicWatch, older Fossil/Montblanc, OnePlus Watch 1) not verified from a primary source.

## 2. Watch face market economics

### Takeaway
No credible public per-developer earnings data exist for Play Store watch faces. Anecdotal evidence points to very low paid conversion and a crowded catalogue; the only quantified income figure is Facer's cumulative $1M+ payout to 75+ designers by December 2021. Play's fee on a one-off sale is now 20% + 5% (US/UK/EEA, from 2026-06-30), and 10% + 5% for subscriptions.

### Cited Findings
- Facer, 2021-12-07: "over $1 million" paid out to designers, 75+ independent designers in the partner program (cumulative, over unspecified years). — [Facer news](https://news.facer.io/celebrating-1-million-in-designer-payouts-and-launching-our-first-facer-creator-partner-program/)
- Facer 2026-02-03: claims "a lot of our top designers quit their day jobs"; no figures. Self-interested marketing claim. — [Facer community, 2025 Facer Awards](https://community.facer.io/t/2025-facer-awards-winners-announced-and-an-invite-to-designers/102394)
- Facer pays designers when users sync paid faces via Premium or buy individually; payout split not published. Community-quoted partner eligibility threshold of 5,000 syncs in 30 days (unverified repost). — [Facer partner page](https://www.facer.io/creator/partner); [Facer forum](https://community.facer.io/t/got-5-000-sync-how-to-become-facer-partner/71297) (search summaries)
- Facer Premium price history: $4.99/month or $39.99/year (May 2024 community post); other users saw $5.99/month with a 3-day trial. Current Wear OS 6-era price not confirmed. — [Facer community](https://community.facer.io/t/facer-subscription/101505) (search summary); [Droid Life](https://droid-life.com/tag/facer-premium) (launch coverage: $5/month)
- Facer return on Wear OS 6 (2025-05-20/28): uses Watch Face Push, in-app subscriptions, free, a-la-carte and Premium all supported; "thousands" of faces converted to WFF; no subscriber counts or prices. — [Facer community](https://community.facer.io/t/the-full-facer-experience-is-coming-back-to-wear-os-better-than-ever/97179)
- Facer app returned with Wear OS 6 support on 2025-07-24 per timeline. — [9to5Google guide](https://9to5google.com/guides/wear-os-6/)
- Indie anecdotes (Samsung developer forum, various dates 2019-2022, search snippets): one developer sold one paid face versus 600+ free downloads (~100:1 free-to-paid estimated, on Galaxy Store); another doubted anyone had profited since Google Play; "80 thousand other watch faces" competing; faces described as great but "not worth $1.99". Individual anecdotes, not data. — [Samsung forum: Sales are very low](https://forum.developer.samsung.com/t/sales-are-very-low/8457); [Profit](https://forum.developer.samsung.com/t/profit/16704); [Pricing strategies](https://forum.developer.samsung.com/t/pricing-strategies-on-the-play-store/30770); [Commercializing watch faces](https://forum.developer.samsung.com/t/commercializing-watch-faces/33885) (search summaries only; direct fetches returned 404)
- r/WatchFacesWearOS: about 9k members; most common flair is coupons, then paid/sale, indicating promo-code-driven distribution (via a third-party summary). — [GummySearch](https://gummysearch.com/r/WatchFacesWearOS/)
- Google Play: Published WFF faces on Play grew over 180% in the year 2024-03-24 to 2025-03-23. — [Android Developers Blog, 2025-05-20](https://android-developers.googleblog.com/2025/05/whats-new-in-watch-faces.html)
- Google Play service fee (Google support page): from 2026-06-30 in US/UK/EEA (2026-09-30 Australia/Japan): auto-renewing subscriptions 10% + 5% billing fee; other transactions on first $1M annual earnings 20% + 5% billing fee for new installs (25% + 5% for existing installs); elsewhere 15% on first $1M, 30% above, 15% on subscriptions. The table layout was ambiguous to the fetch tool, so verify in Play Console. — [Google Play Console Help](https://support.google.com/googleplay/android-developer/answer/112622?hl=en)
- Facer-like fact: Google's Watch Face Push partners are Facer, TIMEFLIK, WatchMaker, Pujie, Recreative. — [Android Developers Blog](https://android-developers.googleblog.com/2025/05/whats-new-in-watch-faces.html)
- Watch Face Push has per-app slot limits and a developer validation step; requires Wear OS 6+ and WFF only. — [Android Developers Blog](https://android-developers.googleblog.com/2025/05/whats-new-in-watch-faces.html)

### Inferences
- With a free-to-paid ratio on the order of 100:1 (single anecdote), an Android-only, Wear OS 6+ audience, and the Samsung-driven shipment decline, a one-off paid builder is likely to earn little unless backed by marketing; Facer's model (subscription plus content catalogue with sync) is the only demonstrated route to meaningful designer income, and it is a marketplace, not a builder.
- Subscription fee cut to 10% + 5% makes a subscription structurally cheaper for the developer than a one-off sale (20% + 5%, new installs) in US/UK/EEA. A builder with ongoing value (cloud-hosted face library, sync) fits the better fee tier.
- Ad-supported models: I found no evidence on watch face ad revenue.

### Gaps
- No reported Play Store revenue per watch face developer, no subscription-vs-one-off conversion rate, no ad-model data, no IndieHackers or Reddit income posts found. Facer's payout split percentage not published. Facer Premium price after the Wear OS 6 return not confirmed. Typical price points beyond $1.99-ish anecdotes not sourced.

## 3. Platform competitive risk (Google, Samsung)

### Takeaway
Yes, Google has already shipped first-party AI watch face generation on the Pixel Watch 5 (announced 2026-08-12), which is a direct (if currently limited) overlap with a customizable builder. Reviewers call it restrictive (clock typography only, static, rigid complications). It is not confirmed on Galaxy Watches. Wear OS 7 (I/O 2026) announced widgets, Live Updates and Gemini Intelligence, with no coverage found of an expanded general-purpose editor or Gemini watch-face creation for all Wear OS 7 devices.

### Cited Findings
- Pixel Watch 5 (announced 2026-08-12) includes a "Create Watch Face" feature: text or voice prompt, 10-30 seconds, requires connectivity; choices include face only, four complications, or a Gemini button; generator powered by Nano Banana per one reviewer. — [Tom's Guide](https://www.tomsguide.com/wellness/smartwatches/i-used-ai-to-create-a-deli-meat-inspired-watch-face-for-my-pixel-watch-5-and-now-im-questioning-everything) (search summary; page truncated on fetch); [Tech Advisor](https://www.techadvisor.com/article/3213107/the-pixel-watch-5-has-a-surprising-list-of-exclusive-features.html) (search summary)
- Limitations per Engadget: prompts change only the clock numeral font style; complication size/placement fixed; you choose complications for four arcs and a colour palette; two new faces (Weave, Chronograph) and a Multicolor complication option. — [Engadget Pixel Watch 5 review](https://engadget.com/2241496/google-pixel-watch-5-review)
- Tom's Guide verdict (search summary): Google already offers plenty of free watch faces more feature-rich than the AI generator. Faces are static; SlashGear found it generated only number styles. — [Tom's Guide](https://www.tomsguide.com/wellness/smartwatches/i-used-ai-to-create-a-deli-meat-inspired-watch-face-for-my-pixel-watch-5-and-now-im-questioning-everything); [SlashGear](https://www.slashgear.com/2246785/google-pixel-watch-5-review-2026/) (search summaries)
- Availability: Google lists "Create Your Watch Face" as Pixel Watch 5 first, "also PW4 with Q3 OTA" (one outlet's reading of Google's confirmation). No evidence found of Galaxy Watch or other Wear OS availability. — [Tech Advisor](https://www.techadvisor.com/article/3213107/the-pixel-watch-5-has-a-surprising-list-of-exclusive-features.html) (search summary). Contradiction/uncertainty: the 9to5Google Made by Google Gemini Intelligence article (2026-08-12) does not mention it. — [9to5Google](https://9to5google.com/2026/08/12/pixel-watch-5-gemini-intelligence/)
- Wear OS 7 (I/O 2026): widgets replace Tiles (2x1, 2x2), Live Updates on watch face, Gemini Intelligence on "select watches", AppFunctions API, up to 10% battery gain; no mention of watch face editor or Watch Face Format changes in the article. — [9to5Google](https://9to5google.com/2026/05/19/google-announces-wear-os-7/)
- A Softonic piece claims Wear OS 7 brings Gemini tools to create custom watch face widgets via natural language; single source, could not be opened, treat as unverified. — [Softonic](https://en.softonic.com/articles/gemini-on-wear-os-starts-rolling-out-google-brings-a-new-watch-interface)
- Pixel Watch 5 "At a Glance" area at the bottom of watch faces shows timers, flight, navigation etc. — [9to5Google](https://9to5google.com/2026/08/12/pixel-watch-5-gemini-intelligence/)
- Samsung One UI 8 Watch (2025): seven new faces on Watch 8 series; phone-side face management in Galaxy Wearable; leaks (APK teardown, unconfirmed) of favourites, shuffle and recommendations. No Samsung AI face generator found. — [SamMobile](https://www.sammobile.com/news/galaxy-watch-6-one-ui-8-watch-features-changelog/); [Neowin](https://www.neowin.net/news/new-watch-faces-from-one-ui-8-watch-rolling-out-for-older-samsung-galaxy-smartwatches/) (search summaries)
- WFF v4 (Wear OS 6) already lets users choose photos via a companion app (single image or gallery), plus ambient transitions and colour transforms; Google positions Watch Face Push for third-party marketplaces. — [Android Developers Blog](https://android-developers.googleblog.com/2025/05/whats-new-in-watch-faces.html)

### Inferences
- Google is moving toward prompt-driven, first-party face creation on Pixel hardware; today's version is far narrower than a layout-level builder, but the direction (Gemini plus WFF) means the "casual customization" end of the market is the most exposed; a power-user builder with layout, complication and asset control is less exposed in the near term.
- Samsung, the largest Wear OS vendor, has not shown (in sources found) an equivalent; Samsung's own Watch Face Studio tool targets designers, not end users.
- Risk of Google extending Create Watch Face to all Wear OS 7 devices through the Play-delivered Google app or Wear OS 7 is plausible but unconfirmed; monitor.

### Gaps
- No Google announcement found (as of 2026-10-08) of a general first-party layout-level editor or Gemini face creation for non-Pixel watches. Samsung One UI 9 Watch face features not found. Tom's Guide, Android Authority, Wareable full articles not readable.

## 4. Distribution realities

### Takeaway
Discoverability is poor for faces without a phone companion; phone-plus-watch install friction is real but Google has worked to cut it. Watch Face Push is the sanctioned route for an app that installs many faces, with slot limits and validation.

### Cited Findings
- Samsung forum developers report a face with no phone companion is hard to find from the phone Play Store; a bundled phone app is called the best way to get found; some report incompatibility with every device when uploaded without a companion. Anecdotal, 2022-2024. — [Samsung forum thread](https://forum.developer.samsung.com/t/google-play-wear-os-companion-app/18400?page=5); [Samsung developer blog, 2022-04-05](https://developer.samsung.com/sdp/blog/en/2022/04/05/how-to-install-wear-os-powered-by-samsung-watch-faces) (search summaries)
- Facer's flow: companion app "Install On Your Watch", then tap Install on watch, then manually switch faces. — [Facer help](https://help.facer.io/hc/en-us/articles/18313715191195-How-to-install-watch-faces-from-Google-Play) (search summary)
- Dec 2024 Play Store APK teardown suggested a "Set as watch face" button after install; shipping unconfirmed. — [Kowatek](https://blog.kowatek.com/2024/12/07/setting-a-new-wear-os-watch-face-might-soon-be-possible-with-a-single-click/) (search summary)
- Watch Face Push: covers only the watch side; developer still owns phone app, cloud, Wear OS app; limited face slots; extra permission to set active face; validation token per face. — [Android Developers Blog](https://android-developers.googleblog.com/2025/05/whats-new-in-watch-faces.html)
- Developer account friction: Google's 20-tester requirement for new personal developer accounts (forum mention). — [Samsung forum search result](https://forum.developer.samsung.com/t/availability-watch-faces-on-play-store-and-user-complaints/15454) (search summary only)

### Inferences
- A phone+watch builder lives inside the Android phone Play Store plus watch-side install; discoverability must come from outside (YouTube, Reddit communities, Facer-style catalogue) rather than organic Play search, based on developer anecdotes.
- Android-phone-only coupling excludes iPhone users, who are a significant share of Galaxy Watch/Pixel Watch-class pairing constraints (Wear OS requires Android for most features); this is already implied by Wear OS itself, so not an extra cost.

### Gaps
- No data on watch app discovery on-watch (Play on watch) share or install conversion for companion apps; no quantified Play Store search behaviour for watch faces.

## 5. Evidence on how many users customize beyond defaults

### Takeaway
No reliable percentage found. Academic work shows a bimodal pattern (frequent customizers vs. non-customizers) and that personalized faces raise engagement in small samples.

### Cited Findings
- Study of 386 self-trackers using fitness smartwatches: most either customize their display frequently (sometimes several times a day) or not at all; exact split not in the abstract. — [Gouveia and Epstein, "This Watchface Fits with my Tattoos"](https://research.utwente.nl/en/publications/this-watchface-fits-with-my-tattoos-investigating-customisation-n/) (search summary)
- University of Twente thesis (2021, Fitbit): higher self-reported engagement with a personalized face versus default; physical activity unchanged; small study. — [Twente thesis](https://essay.utwente.nl/essays/85995)
- "78% of Pixel Watch users cited watch faces as a key purchase factor" (2023 "Smartwatch Insights"), unverified, from a low-quality blog; do not rely on it. — [Expert Beacon](https://expertbeacon.com/the-future-of-smartwatch-personalization-a-comprehensive-look-at-the-google-pixel-watch-2-watch-faces/)
- Indirect signals: Google invests in face customization (photos in WFF v4, Pixel Watch 5 generator) and Samsung adds shuffle/favourites, which suggests customization is a feature vendors compete on, though not a usage rate. — [Android Developers Blog](https://android-developers.googleblog.com/2025/05/whats-new-in-watch-faces.html); [Engadget](https://engadget.com/2241496/google-pixel-watch-5-review)

### Inferences
- The customizing minority is probably small but intense (bimodal), which suits a niche paid tool and not a mass-market free app; this is an inference from a single study abstract, not a measured Wear OS rate.

### Gaps
- No Wear OS-specific share of users who change watch faces, no Play Store download counts for top watch face apps retrieved, no Statista data accessed.

## Overall judgement notes for the report writer (inferences, not findings)
- Audience: Wear OS shipments fell in 2025 (Counterpoint), Samsung (majority of Wear OS) lost share through Q1 2026; recovery depends on Pixel Watch 4/5 and a Galaxy Watch 9 cycle. Audience is shrinking or flat, not growing, on current evidence.
- Economics: no evidence of meaningful indie Play Store watch face income; fees are 20% + 5% for one-off purchases and 10% + 5% for subscriptions in US/UK/EEA since 2026-06-30. Facer is the only demonstrated large-scale designer payout, and predates the Wear OS 5 gap.
- Platform risk: real and already visible (Pixel Watch 5 Create Watch Face, 2026-08-12), though limited in scope today; whether it expands to Galaxy Watches/all Wear OS 7 is unconfirmed.
