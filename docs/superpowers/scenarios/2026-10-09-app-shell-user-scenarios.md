# Phone app shell and design system: user scenarios and where each is tested

Index of what a person can do and see in the redesigned phone app (four destinations, new design system) and which automated test
protects it. Spec: `docs/superpowers/specs/2026-10-09-app-shell-design.md`. Plan: `docs/superpowers/plans/2026-10-09-app-shell.md`.
This is a coverage catalogue, not a queue item. It continues `2026-10-08-builder-poc-user-scenarios.md`, whose rows 1 to 17 still hold for
the editing, sharing and sending logic; this file covers the shell around them.

Actor: the owner (one person with a phone and a Wear OS 6 watch). There is no other role.

Test layers (all under `phone/`): **JVM** = `./gradlew :phone:testDebugUnitTest`, in `src/test/kotlin/com/seyedjafari/analogplus/phone/`;
**UI** = Compose tests on the phone emulator, `scripts/with_phone_emulator_lock.sh ./gradlew :phone:connectedDebugAndroidTest`, in
`src/androidTest/kotlin/com/seyedjafari/analogplus/phone/`; **E2E** = `scripts/poc_e2e.sh` (two emulators; it builds the face through the
debug broadcast receiver and relays it by adb, so it does not touch the phone UI or its navigation: it passed unchanged on the shell,
and the shell itself is covered by the UI layer only). Test names below are
`File.testName`; a file name alone means every test in it. `ui/` and `ui/<area>/` mean the sub-folder of the same tree.

How the UI tests drive the app: through `AppShell` (the real shell, all four destinations, real sections, real preview) inside
`PinnedWindow` (a window of an exact size in dp, whatever the emulator is), or through the real `MainActivity` where a scenario needs
the system (Back key, VIEW intents, rotation, recreation). Only the watch link is replaced: **the Wear Data Layer cannot be reached
from an emulator** (the emulators cannot be paired without a Google sign-in), so the project's convention is a fake `WatchTransport`
(`FakeWatchTransport` in `ShellTestSupport.kt`, with gates that stop a step so the screen can be read between stages) under the
**real** `DataLayerSender`, `SendController` and `TransportWatchStatusChecker`; older tests use the `FaceSender` / `WatchStatusChecker`
fakes `testSend` and `watchReady`. The fake builder returns a small file; the real builder (Rust packer, Google validator) is covered by
`:facegen`/`:packjni` device tests and by E2E.

## A. Finding things and moving around

| # | Scenario (what the person does, what they see) | Covered by |
|---|---|---|
| 1 | Opens the app for the first time: it opens on Design with the watch preview, the six section chips, a "Send to watch" button, and four named places (Design, Library, Send, Account) with Design marked. Upright and sideways. | UI `FirstTimeUserTest.theAppOpensOnTheDesignWithAWatchPreviewTheSectionsAndASendButton`, `.theBottomNavigationNamesTheFourPlacesInWords`; `NavigationTest.startsOnDesignAndEveryDestinationIsReachable`; `AccessibilityTest.everyNavigationItemHasALabelAndIsAtLeast48dp` |
| 2 | Looks for "my designs" without being told: Library, then the "My designs" chip, which says "Nothing saved yet" and how to save one. | UI `FirstTimeUserTest.myDesignsAreFoundUnderLibraryAndThenSayHowToSaveOne` (by visible text only, no tags, both orientations) |
| 3 | Looks for "send to watch": the Send place shows "Your watch", its two checks, "Ready to send." and the button; Design also has the button. | UI `FirstTimeUserTest.sendToWatchIsOnTheSendPlaceWithTheWatchStatusAboveIt`, `MainShellTest.designSendShortcutOpensTheSendDestination` |
| 4 | Looks at Account: version, "Backup and sync" (sign-in comes later), signing key. | UI `FirstTimeUserTest.theAccountPlaceShowsTheVersionAndTheSigningKey`, `AccountFlowTest` (see 38) |
| 5 | Switches between the four places and back; the chosen one is marked; content slides in the direction of the order (forward from the end, backward from the start). | UI `NavigationTest` (bottom bar and rail), `ShellThemeAndSweepTest`; JVM `ui/nav/DestinationTest.tagsAreUnique`, `ui/theme/MotionTest.laterItemsSlideInFromTheEndAndEarlierOnesFromTheStart` |
| 6 | Leaves a place and comes back: the Design section, the Library section and what was typed in it are as they were. | UI `RecreationTest.sectionsSurviveSwitchingDestinations`, `LibraryAccountTest.librarySectionsKeepTheirTypedTextWhenYouSwitchChipsAndBack`, `DesignScreenTest.aSectionKeepsItsInnerStateWhenYouSwitchChipsAndBack`, `LibraryJourneyTest` |
| 7 | Presses Back: from Library, Send or Account (also from inside a Library section) it goes to Design; from Design the app leaves (the task moves to the back on Android 12+). A real Back key event in the real activity. | UI `BackButtonTest` (both tests), `NavigationTest.backFromAnotherDestinationGoesToDesign`; JVM `ui/nav/DestinationTest.designBackLeavesTheApp`, `.everyOtherDestinationBacksToDesign` |
| 8 | Presses Back while the "Load shared design?" question is up: it answers "keep mine" first, the destination stays, the next Back goes to Design. | UI `BackButtonOverDialogTest.backAnswersTheLinkQuestionWithKeepMineBeforeItLeavesTheDestination` |
| 9 | Holds the phone sideways: a rail at the start edge replaces the bar; Design puts the preview beside the controls; the rail keeps clear of a camera cutout; upright goes back to the bar. | UI `NavigationRailTest`, `DesignLayoutTest`, `ShellInsetsTest`, `RotationTest`; JVM `ui/theme/ScreenLayoutTest`, `ui/design/SidePaneWidthTest` |

## B. Design

| # | Scenario | Covered by |
|---|---|---|
| 10 | Moves between the six sections with the chip row; a section is entered at its top, keeps its scroll while sliding out, slides in the direction of its order; the preview stays put. | UI `DesignScreenTest` (`switchingSectionStartsAtTheTop`, `aSectionSlidingOutKeepsItsScrollOffsetInsteadOfJumpingToTheTop`, `comingBackToAScrolledSectionStartsAtTheTop`, `sectionsSlideInTheDirectionOfTheirOrder`); JVM `ui/design/DesignSectionTest` |
| 11 | Changes a value (typed colour, preset, number, switch, slot, ring): the design changes and the preview follows; a new render fades in over the old one (200 to 300 ms), never more than two images, the last render wins. | UI `EditorTest.typingAHexAccentChangesThePreview`, `AppearanceTest`, `ComplicationsTest`, `PreviewCrossfadeTest` (all three); JVM `CrossfadeLayersTest`, `FieldHelpersTest` |
| 12 | Types an invalid or out-of-range value: a message in the field, the slider stays in range, "Send to watch" is off and the Design screen says what to fix first; fixing it turns Send back on. | UI `EditorTest.typingRedShowsTheMessageAndDisablesSend`, `.aHugeBadAccentNeverLocksTheEditor`, `.aHugeBadAccentWithLittleRoomLeftStillReachesSend`, `DesignScreenTest.aDisabledSendSaysWhyOnTheDesignScreen`, `AppearanceTest.badColourTextShowsTheErrorAndKeepsTheRawText`, `.outOfRangeIsAnErrorAndTheSliderStaysInRange`, `ControlsA11yTest.aColourFieldErrorIsALiveRegion`; JVM `EditorStateTest`, `FieldHelpersTest` |
| 13 | Complications and rings: drag, nudge, resize, add, remove, cap "N of 8", refusals with readable messages, ring layouts over the cap disabled and explained. | UI `ComplicationsTest`, `DesignLayoutTest.inALandscapePhoneTheSlotCanvasKeepsItsMinimumSizeScrollsIntoViewAndStillDrags`; JVM `SlotGeometryTest`, `ui/design/SlotCanvasSideTest`, `ui/design/SlotEditorMinSideTest` |
| 14 | Photo background: while the saved photo is read the Background section shows a skeleton and a missing-photo error stays hidden; a picked photo shows a skeleton then the thumbnail; an unreadable photo gets a message. | UI `PhotoLoadingTest` (all seven), `DesignScreenTest.theMissingPhotoErrorStaysHiddenWhileTheSavedPhotoLoadsAndShowsOnceItIsKnownMissing`, `AppearanceTest.photoThumbnailDimAndRemove`; JVM `EditorStateTest`, `StoreTest` (photo tests), `FieldHelpersTest` |

## C. Sending to the watch

| # | Scenario | Covered by |
|---|---|---|
| 15 | Presses "Send to watch" on Design: the app opens Send and starts one send. | UI `MainShellTest.designSendShortcutOpensTheSendDestination`, `SendFlowTest.aSendFromDesignShowsEveryStageThenTheVersionOnTheWatch` |
| 16 | Watches progress: "Building the face...", "Looking for the watch...", "Sending the face to the watch...", "The watch is installing the face...", the bar and button disabled throughout. | UI `SendFlowTest.aSendFromDesignShowsEveryStageThenTheVersionOnTheWatch` (each stage held open by the fake Data Layer); JVM `SenderTest.senderStagesAreShown`, `.successShowsStagesThenSuccess` |
| 17 | Success: "Sent. The watch now has version N.", a pill "Version N is on the watch", the button usable again; the next send gets a higher version. | UI `SendFlowTest.aSendFromDesignShowsEveryStageThenTheVersionOnTheWatch`, `.aSecondSendFromTheSendScreenGetsAHigherVersion`; JVM `StoreTest` (version counter), `SendControllerTest.successWithANewKeyStoresTheNewFingerprint` |
| 18 | No watch found: the text says to open Bluetooth and check the pairing; after the watch appears, the same button works. | UI `SendFlowTest.noWatchSaysToOpenBluetoothAndTheNextTrySucceedsOnceTheWatchIsThere`; JVM `SenderTest.noNodeFailsWithTheBluetoothHint` |
| 19 | Watch paired but the Analog Plus watch app is missing: the text says to install it on the watch. | UI `SendFlowTest.aWatchWithoutTheAppSaysToInstallItOnTheWatch`; JVM `SenderTest.connectedWatchWithoutTheAppFailsAtOnce` |
| 20 | The Data Layer cannot be reached (Bluetooth off, Play services): the cause is named and the Bluetooth advice repeated. | UI `SendFlowTest.aDataLayerThatCannotBeReachedNamesTheCauseAndRepeatsTheBluetoothAdvice` |
| 21 | The face file never arrives: a timeout that says to wake the watch and keep it connected. | UI `SendFlowTest.aFileThatNeverArrivesTimesOutAndSaysToWakeTheWatch`; JVM `SenderTest.fileThatNeverFinishesFailsAfterTheFileTimeoutWithReadableText` |
| 22 | The transfer breaks off ("watch disconnected"): the reason is named. | UI `SendFlowTest.aTransferThatBreaksOffSaysWhyAndCanBeRepeated`; JVM `SenderTest.channelClosingBeforeTheOutputClosedIsAReadableError`, `.outputClosedWithAnAbnormalReasonIsAnError` |
| 23 | The watch does not answer: a timeout that says what to check (awake, connected, watch app present). | UI `SendFlowTest.aWatchThatDoesNotAnswerTimesOutAndSaysWhatToCheck`; JVM `SenderTest.timeoutWithoutAnAnswerFailsAndCleansUp`, `.defaultAnswerTimeoutOutlastsTheWatchsOwnFileWait` |
| 24 | The watch refuses the face ("version not higher" ...): its reason is shown. | UI `SendFlowTest.aWatchThatRefusesTheFaceShowsItsReason`; JVM `SenderTest.watchRefusalBecomesAnErrorWithItsReason`, `SendControllerTest.versionNotHigherIsNeverBlamedOnTheKey` |
| 25 | The watch refuses an update because the phone now signs with another key: the text explains the key change, how to remove the old face (uninstalling the watch app does not remove it; the adb command) and quotes the watch. | UI `SendFlowTest.aRefusedUpdateAfterTheKeyChangedExplainsTheKeyAndHowToRemoveTheOldFace`; JVM `SendControllerTest` (key tests) |
| 26 | The face fails the watch checks, runs out of memory (photo), the native builder is missing, or the build fails for another reason: each has its own text and Send can be pressed again. | UI `SendFlowTest.aFaceThatFailsTheWatchChecksListsTheFailedChecks`, `.aBuildThatRunsOutOfMemorySaysToTryASmallerPhoto`, `.aMissingNativeBuilderIsNamed`, `.anUnexpectedBuildErrorShowsItsMessage`; JVM `SenderTest.buildFailureListsTheFailedChecks`, `.illegalArgumentAndUnexpectedBuildErrorsAreReadable`, `.failedBuildNeverReusesTheVersionNumber` |
| 27 | Every message exists as a string resource with the English wording the logic tests assert. | UI `SendProblemResourcesTest`, `ShareProblemResourcesTest`; JVM `StringsXmlMatchesCodeTest` |
| 28 | The design is invalid when Send is pressed on Send: the button is off and a "Fix the design first" summary names the field. | UI `ShareTemplatesTest.shellShowsTheFourDestinationsAndDisablesSendForAnInvalidDesign`, `SendScreenTest.theMissingPhotoErrorStaysHiddenWhileTheSavedPhotoLoadsAndShowsOnceItIsKnownMissing`; JVM `SendControllerTest`, `SenderTest.invalidDesignIsRefusedWithoutBuilding` |
| 29 | Watch status: paired and app installed (both "OK"); paired without the app (install advice); no watch (Bluetooth advice, app "Could not check"); the check failed or never answers ("Could not check" for both, no false advice); "Check again" picks up a newly paired watch; coming back to the screen re-checks without flashing "Checking". | UI `WatchStatusScreenTest` (all six), `SendScreenTest` (`noWatchShowsActionNeededAndAdviceAndCheckAgainReruns`, `appMissingShowsInstallAdvice`, `returningToTheScreenKeepsTheLastStatusVisibleWhileRechecking`); JVM `ui/send/WatchStatusTest` |
| 30 | A send is running and the person goes to Library or Design: it keeps running, Design's button stays off, a second start is ignored, the result is waiting on return (success pill or error). | UI `SendFlowTest.aSendThatFinishesWhileAwayShowsItsResultOnReturn`, `.theDesignShortcutStaysOffWhileASendRunsAndComesBackWhenItEnds`, `RecreationTest.sendKeepsRunningWhenSwitchingDestinations`, `SendScreenTest.aSendInFlightSurvivesLeavingAndReturningAndASecondStartIsIgnored`, `DesignScreenTest.sendButtonIsOffWhileBusy`; JVM `SenderTest.secondSendWhileRunningIsRefused` |
| 31 | A link arrives during a send: the question shows over the Send screen, "keep mine" leaves the design alone, the send goes on and finishes. | UI `SendFlowTest.aLinkArrivingDuringASendAsksOverTheSendScreenAndTheSendGoesOn` |

## D. Library

| # | Scenario | Covered by |
|---|---|---|
| 32 | Templates: tapping one asks "Replace your current design?"; Cancel keeps the design, Replace swaps the whole design (Design then shows its colour); choosing the design already open asks nothing. | UI `LibraryAccountTest.templateAsksBeforeReplacingThenReplaces`, `ShareTemplatesTest.tappingATemplateAsksBeforeReplacingTheWholeDesign`, `.choosingTheDesignThatIsAlreadyOpenAsksNothing`, `EditorTest.applyingATemplateAsksFirstThenReplacesTheDesignAndTheStateOfThePreview`, `LibraryJourneyTest` |
| 33 | My designs: empty state; save under a name (a blank name is refused); the list; load (asks first, Cancel and Replace); delete; deleting the last brings the empty state back. | UI `LibraryAccountTest.emptyMyDesignsShowsTheEmptyStateThenSavedDesignAppears`, `.savedDesignNameIsA48dpTarget`, `ShareTemplatesTest.saveLoadAndDeleteMyDesigns`, `.savingWithoutANameShowsAnError`, `LibraryJourneyTest`; JVM `StoreTest` (saved designs, blank name, corrupt list) |
| 34 | Share link: the link shown is the design being edited and follows it; Copy puts it on the clipboard and says "Link copied."; a photo background says the link will not carry the photo. | UI `ShareLinkActionsTest` (all four) |
| 35 | Load a design from a pasted link: valid link loads and clears the field and says "Design loaded."; garbage or empty text gives a message and keeps both field and design; a small pasted text survives recreation, a huge one is not saved. | UI `ShareTemplatesTest` (`pastingAValidLinkLoadsThatDesign`, `theLinkFieldIsEmptyAfterASuccessfulLoadAndKeepsItsTextAfterAFailedOne`, `garbageLinkShowsAnErrorAndKeepsTheDesign`, `nonLinkTextShowsAnErrorAndEmptyFieldToo`, `aSmallPastedLinkSurvivesRecreationButOneOverTheLimitIsNotSaved`), `EditorTest.pastingAShareLinkLoadsThatDesign`, `ShareLinkActionsTest.aLoadedLinkSaysTheDesignWasLoaded`; JVM `ui/library/BoundedTextSaverTest`, `LinkDialogTest.parseShareLinkRejectsEmptyAndGarbageWithoutThrowing` |
| 36 | QR code of the design: shown on a white card in either theme, with the scan button; a design too big for a QR says so and points to the link. | UI `QrSectionTest`, `LinkDialogSharingTest.theQrCodeAndItsScanButtonAreInsideACard`; JVM `QrCodeTest` |
| 37 | Scans someone else's QR code: a design asks "Load shared design?" (Keep mine changes nothing, Load replaces); another QR code, an empty scan, a scanner that is missing or cannot start each show a message; backing out leaves nothing; the next good scan clears an old message. Google's scanner itself is replaced by a test scanner (`QrScanner` seam in `QrSection.kt`). | UI `QrScanFlowTest` (all eight); JVM `QrCodeTest.scanningAnOwnLinkGivesTheDesign`, `.scanningOtherTextIsRejectedWithAReadableMessage` |
| 38 | A tapped link (VIEW intent `analogplus://design?d=...`) arrives: at start, or while the app is open on Account, or on any destination, or during a send: the question shows over whatever is open, names the design (cleaned), and never replaces the design without a yes; a bad link asks nothing. | UI `DeepLinkTest` (all four, real activity and real intents), `MainShellTest` (`linkDialogShowsOnTopOfEveryDestinationAndNeverReplacesSilently`, `loadingTheLinkReplacesTheDesignAndResolves`), `LoadSharedDesignDialogTest`, `LinkDialogSharingTest.aTappedLinkShowsTheSharedDialogWithItsTags`, `SendFlowTest` (31), `BackButtonOverDialogTest` (8); JVM `LinkDialogTest`, `QrCodeTest.dialogNameIsShortAndHasNoControlCharacters` |
| 39 | Each Library section keeps its own scroll and typed text, and is entered at its top. | UI `LibraryAccountTest.eachLibrarySectionIsEnteredAtItsTop`, `.librarySectionsKeepTheirTypedTextWhenYouSwitchChipsAndBack` |

## E. Account

| # | Scenario | Covered by |
|---|---|---|
| 40 | Account shows the installed version, and "Backup and sync" saying that sign-in is not set up in a build without accounts and that designs are saved on the phone (before the accounts work it said "Sign-in is coming"). | UI `AccountFlowTest.theAppVersionIsTheInstalledOne`, `.withoutAnAccountsControllerTheCardSaysSignInIsNotSetUpAndTheDesignsStayOnThePhone` |
| 41 | "Reset signing key" asks first and says what it costs; Cancel keeps the key; Reset makes a new key on the next send and says so. (The tests use a key file of their own, never the app's real key.) | UI `AccountFlowTest.theResetQuestionSaysWhatItCostsAndCancelKeepsTheKey`, `ShareTemplatesTest.resetSigningKeyAsksFirstAndThenRemovesTheKey`, `LibraryAccountTest.accountHostsTheSigningKeyReset`, `.signingKeyCardHasOneHeading` |

## F. Rotation, process death, appearance, accessibility, odd windows

| # | Scenario | Covered by |
|---|---|---|
| 42 | Rotation on every destination (real activity, recreated in place and turned sideways and back): destination, section and the design being edited come back; the rail and the bar swap. | UI `EveryDestinationRecreationTest` (Design, Library, Send, Account; two tests each), `RotationTest`, `DesignLayoutTest.rotatingKeepsTheSectionAndItsInnerStateBothWays`, `.landscapeSectionChipSurvivesStateRestoration` |
| 43 | The system ends the app and it comes back: the destination and the sections come back from the saved state (emulated), and the design comes back from storage after a cold start (a new activity and view model). The running send does not survive, by design. | UI `RecreationTest.selectedDestinationAndSectionsSurviveRecreation`, `ColdStartTest.theDesignComesBackAfterTheAppWasClosedAndOpenedAgain`, `ShareTemplatesTest.aSmallPastedLinkSurvivesRecreationButOneOverTheLimitIsNotSaved`; JVM `StoreTest.currentDesignRoundTrips`, `.corruptJsonFallsBackToTheDefaultDesign` |
| 44 | Light and dark: every destination renders on the theme's background; the two differ; text pairs meet WCAG AA; the window background matches before the first frame; no raw colours, sizes or strings in screens. | UI `ShellThemeAndSweepTest` (all three theme tests); JVM `ui/theme/PaletteContrastTest`, `ui/theme/WindowColourResourceTest`, `ui/theme/NoHardCodedLiteralsTest`, `ui/theme/TypographyTest`, `ui/theme/ElevationTest`, `ui/components/ComponentPreviewsTest` |
| 45 | Font scale 2.0: bottom labels stay on one line (they stop growing at 1.3x), Send stays reachable, status rows never break mid-word, Design and Library key controls are whole, number fields fit four digits, Account's reset question keeps both answers, the landscape Design keeps the summary and the Send row. | UI `AccessibilityTest` (all seven), `NavigationRailTest.railLabelsStayWholeAtFontScale2`, `.anUprightPhoneStillGetsTheBottomBarWithItsLabelsAtFontScale2`, `ControlsA11yTest.fourDigitsFitAnIntFieldAtFontScale2`, `DesignLayoutTest.aLandscapePhoneAtFontScale2WithAnErrorStillShowsTheSummaryAndTheSendRow`, `AccountFlowTest.atFontScale2TheResetQuestionKeepsBothAnswersReachable` |
| 46 | Animations removed in the system settings: destination and section switches have no in-between frame, the preview replaces the old render at once, the skeleton does not pulse (each with a control run with animations on). | UI `AnimationsOffTest` (all six), `PreviewAnimationsOffTest` (both), `ComponentsTest.skeletonIsStaticWhenAnimationsAreOff`; JVM `ui/theme/MotionTest` |
| 47 | Keyboard open (a real keyboard inset): the bottom bar and the Send row give their room to the field, the field stays visible, the rail stays; everything is back when it closes. | UI `KeyboardOpenTest` (upright and sideways), `NavigationRailTest.withTheKeyboardOpenTheRailItemsKeepTheirSizeDoNotOverlapAndScroll`, `EditorTest.aHugeBadAccentWithLittleRoomLeftStillReachesSend` |
| 48 | Loading and empty states: photo skeleton (14), thumbnails of templates and saved designs render as skeletons first, "Nothing saved yet" (33), Send before any send reads "Ready to send.". | UI `PhotoLoadingTest.aDesignThumbnailIsASkeletonUntilItIsRendered`, `ComponentsTest` (skeleton and empty-state tests), `EveryDestinationRecreationTest` (idle Send) |
| 49 | Screen readers and touch: every clickable on every destination and section has a name; navigation items, presets, saved names and buttons are at least 48 dp; switches, nudge arrows, shape chips, error messages are named; headings are marked. | UI `ShellThemeAndSweepTest.everyClickableOnEveryDestinationAndSectionHasATextOrAContentDescription`, `ControlsA11yTest`, `AccessibilityTest`, `NavigationRailTest.everyRailItemHasItsLabelAsContentDescriptionAndATargetOfAtLeast48dp`, `ComponentsTest` |
| 50 | Windows the shell is not designed for (tablet upright and sideways, a half-open foldable, a 320 x 480 phone): every destination opens and shows its main control, Send is reachable. | UI `WindowSizesTest` (four sizes) |

## Not covered, and why

- **The phone-to-watch transfer over the real Wear Data Layer** (nodes, channel, apply message, reply) and the **watch status check against a
  real paired watch**. The emulators cannot be paired (no Google sign-in in an automated run). The logic is tested on a fake
  `WatchTransport` under the real `DataLayerSender`/`TransportWatchStatusChecker` (rows 16 to 26, 29), and E2E relays the same APK
  over adb. The owner's phone and watch were not available in this run, so the real-device checklist in `docs/poc/README.md` is still open.
- **Scanning with the camera** (Google's scanner needs a camera and Play services): everything after the scan is covered with a test scanner (37);
  the scanner screen itself and its first-use module download are not.
- **The system share sheet** behind the Share button, the **photo picker**, and the **Android permission/Play services dialogs**: system screens;
  the intent and the bytes are not asserted either (no intents library in the project).
- **A real process kill.** A test cannot kill its own process. Saved-instance-state restore is emulated with `StateRestorationTester`
  and a cold start is a new activity and view model (43); a running send does not survive a kill, by design (the controller lives in the view model).
- **Tablet and foldable layouts beyond "does not break"** (spec out of scope): single-pane stretch only, row 50; no fold-posture or
  multi-window testing.
- **Localisation beyond resources** (spec out of scope): all text is in string resources and tests compare the English resource with the
  logic's wording (27); no second language is shipped or tested.
- **The system "remove animations" setting itself** is not changed by tests; `LocalAnimationScale = 0f` stands in for the value the app reads
  (each test has a control run proving the assertion can tell a fade from a snap).
- **Always-on mode, complication data, and the face as the watch renders it**: unchanged by the shell; see the builder POC catalogue.
- **Visual quality** (spacing, colour taste, typography) is judged by eye from the screenshots in `docs/poc/screens/`; tests guard contrast,
  sizes, tokens and clipping, not beauty.
