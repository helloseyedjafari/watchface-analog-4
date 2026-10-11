package com.seyedjafari.analogplus.phone.accounts

import com.seyedjafari.analogplus.facegen.design.BackgroundDesign
import com.seyedjafari.analogplus.facegen.design.BackgroundKind
import com.seyedjafari.analogplus.facegen.design.DesignJson
import com.seyedjafari.analogplus.facegen.design.DesignSettings
import com.seyedjafari.analogplus.phone.canonicalDesignName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncResolverTest {
    private val a = DesignSettings(name = "A")
    private val b = DesignSettings(name = "B", accent = "#FF1E6BFF")
    private fun remote(id: String, d: DesignSettings, at: Long, state: DesignState = DesignState.LIVE, name: String = "n") =
        RemoteDesign(id, name, if (state == DesignState.LIVE) DesignJson.toJson(d) else "", at, state, 1)
    private fun local(
        id: String, d: DesignSettings?, modified: Long, base: Long?, mark: SyncMark, state: EntryState = EntryState.LIVE, name: String = "n",
    ) = LocalDesignEntry(id, name, d, state, modified, base, mark)
    private val suffix = " (Pixel 8)"
    private val toJson: (DesignSettings) -> String? = { DesignJson.toJson(it) }
    private fun plan(l: List<LocalDesignEntry>, r: List<RemoteDesign>) = planSync(l, r, l.map { it.name }.toSet(), toJson, suffix)

    @Test fun nothingToDoWhenBothSidesAgree() {
        assertEquals(emptyList<SyncAction>(), plan(listOf(local("1", a, 10, 500, SyncMark.CLEAN)), listOf(remote("1", a, 500))))
    }

    @Test fun dirtyLocalOnlyPushes() {
        assertEquals(listOf(SyncAction.PushLive("1")), plan(listOf(local("1", b, 10, 500, SyncMark.DIRTY)), listOf(remote("1", a, 500))))
    }

    @Test fun newerRemoteOnlyIsApplied() {
        val r = remote("1", b, 900)
        assertEquals(listOf(SyncAction.ApplyRemoteLive("1", r)), plan(listOf(local("1", a, 10, 500, SyncMark.CLEAN)), listOf(r)))
    }

    @Test fun designOnlyInTheCloudIsApplied() {
        val r = remote("1", a, 900)
        assertEquals(listOf(SyncAction.ApplyRemoteLive("1", r)), plan(emptyList(), listOf(r)))
    }

    @Test fun designOnlyOnThePhoneIsPushed() {
        assertEquals(listOf(SyncAction.PushLive("1")), plan(listOf(local("1", a, 10, null, SyncMark.DIRTY)), emptyList()))
    }

    @Test fun bothChangedLaterEditWins_localNewer() {
        val l = local("1", b, modified = 2_000, base = 500, mark = SyncMark.DIRTY)
        assertEquals(listOf(SyncAction.PushLive("1")), plan(listOf(l), listOf(remote("1", a, 1_500))))
    }

    @Test fun bothChangedLaterEditWins_remoteNewer() {
        val r = remote("1", a, 3_000)
        val l = local("1", b, modified = 2_000, base = 500, mark = SyncMark.DIRTY)
        assertEquals(listOf(SyncAction.ApplyRemoteLive("1", r)), plan(listOf(l), listOf(r)))
    }

    @Test fun tieGoesToTheCloud() {
        val r = remote("1", a, 2_000)
        val l = local("1", b, modified = 2_000, base = 500, mark = SyncMark.DIRTY)
        assertEquals(listOf(SyncAction.ApplyRemoteLive("1", r)), plan(listOf(l), listOf(r)))
    }

    @Test fun localDeleteIsPushedAsATombstone() {
        val l = local("1", null, 2_000, 500, SyncMark.DIRTY, EntryState.TOMBSTONE)
        assertEquals(listOf(SyncAction.PushTombstone("1")), plan(listOf(l), listOf(remote("1", a, 500))))
    }

    @Test fun remoteTombstoneDeletesACleanLocalDesign() {
        val r = remote("1", a, 900, DesignState.TOMBSTONE)
        assertEquals(listOf(SyncAction.ApplyRemoteTombstone("1", r)), plan(listOf(local("1", a, 10, 500, SyncMark.CLEAN)), listOf(r)))
    }

    @Test fun aNewerEditElsewhereRevivesALocallyDeletedDesign() {
        val r = remote("1", b, 5_000)
        val l = local("1", null, modified = 2_000, base = 500, mark = SyncMark.DIRTY, state = EntryState.TOMBSTONE)
        assertEquals(listOf(SyncAction.ApplyRemoteLive("1", r)), plan(listOf(l), listOf(r)))
    }

    @Test fun anOlderEditElsewhereDoesNotReviveALocallyDeletedDesign() {
        val l = local("1", null, modified = 9_000, base = 500, mark = SyncMark.DIRTY, state = EntryState.TOMBSTONE)
        assertEquals(listOf(SyncAction.PushTombstone("1")), plan(listOf(l), listOf(remote("1", b, 5_000))))
    }

    @Test fun aLocalEditNewerThanARemoteTombstoneRevivesIt() {
        val l = local("1", b, modified = 9_000, base = 500, mark = SyncMark.DIRTY)
        assertEquals(listOf(SyncAction.PushLive("1")), plan(listOf(l), listOf(remote("1", a, 5_000, DesignState.TOMBSTONE))))
    }

    @Test fun deletedOnBothSidesOnlyRecordsTheBase() {
        val r = remote("1", a, 5_000, DesignState.TOMBSTONE)
        val l = local("1", null, 9_000, 500, SyncMark.DIRTY, EntryState.TOMBSTONE)
        assertEquals(listOf(SyncAction.RecordBase("1", r)), plan(listOf(l), listOf(r)))
    }

    @Test fun aSyncedUntouchedDesignThatVanishedFromTheCloudIsNotWrittenBack() {
        assertEquals(emptyList<SyncAction>(), plan(listOf(local("1", a, 10, 500, SyncMark.CLEAN)), emptyList()))
    }

    @Test fun aSyncedDesignEditedAfterTheCloudCopyVanishedIsPushedAgain() {
        assertEquals(listOf(SyncAction.PushLive("1")), plan(listOf(local("1", b, 10, 500, SyncMark.DIRTY)), emptyList()))
    }

    @Test fun aTombstoneForSomethingTheCloudNeverHadIsForgotten() {
        assertEquals(listOf(SyncAction.Drop("1")), plan(listOf(local("1", null, 9_000, null, SyncMark.DIRTY, EntryState.TOMBSTONE)), emptyList()))
    }

    @Test fun aRemoteTombstoneForAnUnknownDesignIsIgnored() {
        assertEquals(emptyList<SyncAction>(), plan(emptyList(), listOf(remote("1", a, 5_000, DesignState.TOMBSTONE))))
    }

    @Test fun identicalContentOnBothSidesWithoutHistoryOnlyRecordsTheBase() {
        val r = remote("1", a, 5_000)
        assertEquals(listOf(SyncAction.RecordBase("1", r)), plan(listOf(local("1", a, 9_000, null, SyncMark.DIRTY)), listOf(r)))
    }

    @Test fun differingContentWithoutHistoryKeepsBoth() {
        val r = remote("1", b, 5_000, name = "Night")
        val l = local("1", a, 9_000, null, SyncMark.DIRTY, name = "Night")
        assertEquals(listOf(SyncAction.KeepBoth("1", r, "Night (Pixel 8)")), plan(listOf(l), listOf(r)))
    }

    @Test fun keepBothPicksAFreeName() {
        val r = remote("1", b, 5_000, name = "Night")
        val l = local("1", a, 9_000, null, SyncMark.DIRTY, name = "Night")
        val taken = setOf("Night", "Night (Pixel 8)")
        assertEquals("Night (Pixel 8) 2", (planSync(listOf(l), listOf(r), taken, toJson, suffix).single() as SyncAction.KeepBoth).copyName)
    }

    @Test fun unreadableRemoteJsonIsNeverTreatedAsEqual() {
        val bad = RemoteDesign("1", "n", "{not json", 5_000, DesignState.LIVE, 1)
        val actions = plan(listOf(local("1", a, 9_000, null, SyncMark.DIRTY, name = "Night")), listOf(bad))
        assertEquals(listOf(SyncAction.KeepBoth("1", bad, "Night (Pixel 8)")), actions)
    }

    // ---- no shared history (base == null): nothing may be deleted in either direction --------------------------------

    @Test fun aLocalDesignIsNotDeletedByACloudTombstoneItNeverSaw() {
        for (cloudAt in listOf(5_000L, 20_000L)) { // older and newer than the local edit
            val l = local("1", a, 10_000, null, SyncMark.DIRTY)
            assertEquals(listOf(SyncAction.PushLive("1")), plan(listOf(l), listOf(remote("1", a, cloudAt, DesignState.TOMBSTONE))))
        }
    }

    @Test fun aLocalDeletionDoesNotDeleteACloudDesignThePhoneNeverSaw() {
        val r = remote("1", b, 5_000)
        val l = local("1", null, 9_000, null, SyncMark.DIRTY, EntryState.TOMBSTONE)
        assertEquals(listOf(SyncAction.ApplyRemoteLive("1", r)), plan(listOf(l), listOf(r)))
    }

    @Test fun tombstonesOnBothSidesWithoutHistoryOnlyRecordTheBase() {
        val r = remote("1", a, 5_000, DesignState.TOMBSTONE)
        val l = local("1", null, 9_000, null, SyncMark.DIRTY, EntryState.TOMBSTONE)
        assertEquals(listOf(SyncAction.RecordBase("1", r)), plan(listOf(l), listOf(r)))
    }

    @Test fun differingContentWithoutHistoryIsKeptBothWaysWhicheverSideIsNewer() {
        for (cloudAt in listOf(5_000L, 20_000L)) {
            val r = remote("1", b, cloudAt, name = "Night")
            val l = local("1", a, 10_000, null, SyncMark.DIRTY, name = "Night")
            assertEquals(listOf(SyncAction.KeepBoth("1", r, "Night (Pixel 8)")), plan(listOf(l), listOf(r)))
        }
    }

    @Test fun signingInToAnotherAccountMergesWithoutDeletingAnythingEitherWay() {
        // The phone was signed in to account A: "Night" synced and untouched, "Day" deleted and confirmed, "Mine" synced.
        val kv = MemoryKeyValueStore()
        val journal = SyncJournal(kv) { 1_000 }
        journal.bindTo("A")
        journal.markApplied(designId("Night"), "Night", EntryState.LIVE, 700)
        journal.markApplied(designId("Day"), "Day", EntryState.TOMBSTONE, 800)
        journal.markApplied(designId("Mine"), "Mine", EntryState.LIVE, 900)
        assertEquals(BindResult.NEW_ACCOUNT, journal.bindTo("B"))
        val designs = mapOf("Night" to a, "Mine" to a)
        val local = journal.entries().map { (id, e) ->
            LocalDesignEntry(id, e.name, designs[e.name].takeIf { e.state == EntryState.LIVE }, e.state, e.modifiedAtMs, e.baseRemoteAtMs, e.mark)
        }
        // Account B has its own "Night", has not deleted "Day", and deleted a "Mine" this phone never uploaded to B.
        val rNight = remote(designId("Night"), b, 3_000, name = "Night")
        val rDay = remote(designId("Day"), b, 3_000, name = "Day")
        val rMine = remote(designId("Mine"), a, 3_000, DesignState.TOMBSTONE, name = "Mine")
        val actions = planSync(local, listOf(rNight, rDay, rMine), local.map { it.name }.toSet(), toJson, suffix)
        assertEquals(
            setOf(
                SyncAction.KeepBoth(designId("Night"), rNight, "Night (Pixel 8)"),
                SyncAction.ApplyRemoteLive(designId("Day"), rDay),
                SyncAction.PushLive(designId("Mine")),
            ),
            actions.toSet(),
        )
        assertEquals(3, actions.size)
    }

    // ---- names ------------------------------------------------------------------------------------------------------

    @Test fun aKeepBothCopyNeverTakesTheNameOfADocumentThatIsOnlyInTheCloud() {
        val rNight = remote("1", b, 5_000, name = "Night")
        val rCopy = remote("2", b, 5_000, name = "Night (Pixel 8)")
        val rGone = remote("3", a, 5_000, DesignState.TOMBSTONE, name = "Night (Pixel 8) 2")
        val l = local("1", a, 9_000, null, SyncMark.DIRTY, name = "Night")
        val copy = (planSync(listOf(l), listOf(rNight, rCopy, rGone), setOf("Night"), toJson, suffix).first { it.id == "1" } as SyncAction.KeepBoth).copyName
        assertEquals("Night (Pixel 8) 3", copy)
    }

    @Test fun theCopySuffixIsTheCallersNotHardCoded() {
        val r = remote("1", b, 5_000, name = "Night")
        val l = local("1", a, 9_000, null, SyncMark.DIRTY, name = "Night")
        val copy = (planSync(listOf(l), listOf(r), setOf("Night"), toJson, " [mon téléphone]").single() as SyncAction.KeepBoth).copyName
        assertEquals("Night [mon téléphone]", copy)
    }

    @Test fun theSuffixNamesTheDeviceThatMadeTheCopyInTheEnglishOfTheStrings() {
        val strings = com.seyedjafari.analogplus.phone.StringsXml
        fun suffixFor(label: String) = copyNameSuffix(label, { strings.text("sync_copy_suffix", it) }, strings.text("sync_copy_fallback"))
        assertEquals(suffix, suffixFor("Pixel 8"))
        assertEquals(" (copy)", suffixFor(""))
        assertEquals("a blank name counts as none", " (copy)", suffixFor("   "))
        assertEquals(" (Pixel 8)", suffixFor("  Pixel 8 "))
    }

    @Test fun aLongDeviceNameIsCutSoTheCopyKeepsMostOfTheDesignsName() {
        val cut = copyNameSuffix("x".repeat(80), { " ($it)" }, "copy")
        assertEquals(" (" + "x".repeat(MAX_COPY_DEVICE_CHARS) + ")", cut)
        val emoji = "a" + "\uD83D\uDE00".repeat(20)          // the cut at 30 units falls between the two halves of a character
        val suffix = copyNameSuffix(emoji, { " ($it)" }, "copy")
        assertEquals(" (a" + "\uD83D\uDE00".repeat(14) + ")", suffix)
    }

    /** The rules count UTF-16 units (see MAX_DESIGN_NAME_CHARS); an emoji is two of them. */
    @Test fun theNameFieldStopsAtTheLimitTheCloudAcceptsCountingUtf16Units() {
        assertEquals("Night", limitDesignName("Night"))
        assertEquals("x".repeat(MAX_DESIGN_NAME_CHARS), limitDesignName("x".repeat(250)))
        val emoji = "\uD83D\uDE00".repeat(MAX_DESIGN_NAME_CHARS + 20)       // two UTF-16 units per character
        val cut = limitDesignName(emoji)
        assertEquals("50 emoji are 100 units", MAX_DESIGN_NAME_CHARS / 2, cut.codePointCount(0, cut.length))
        assertEquals(MAX_DESIGN_NAME_CHARS, cut.length)
        assertEquals("a name of exactly the limit is kept whole", cut, limitDesignName(cut))
        // the cut would fall between the two halves of a character: the whole character goes
        val odd = limitDesignName("a" + emoji)
        assertEquals("a" + "\uD83D\uDE00".repeat((MAX_DESIGN_NAME_CHARS - 1) / 2), odd)
        assertTrue(odd.length <= MAX_DESIGN_NAME_CHARS && !odd.last().isHighSurrogate())
    }

    @Test fun uniqueNameNeverExceedsTheFirestoreNameLimit() {
        val long = "x".repeat(99)
        val first = uniqueName(long, emptySet(), suffix)
        assertEquals(100, first.length)
        assertTrue(first.endsWith(suffix))
        val second = uniqueName(long, setOf(first), suffix)
        assertTrue("was ${second.length} chars", second.length <= 100)
        assertTrue(second != first && second.endsWith(" 2"))
        val full = "y".repeat(100)
        assertEquals("y".repeat(98) + " 2", uniqueName(full, setOf(full), ""))
        assertEquals("y".repeat(98) + " 3", uniqueName(full, setOf(full, "y".repeat(98) + " 2"), ""))
    }

    @Test fun uniqueNameDoesNotSplitASurrogatePair() {
        val name = "x".repeat(97) + "😀" + "y" // 100 chars; room for " 2" cuts through the emoji
        assertEquals("x".repeat(97) + " 2", uniqueName(name, setOf(name), ""))
    }

    // ---- comparing like with like (photos) --------------------------------------------------------------------------

    private val photoDesign = DesignSettings(background = BackgroundDesign(kind = BackgroundKind.PHOTO, photoJpeg = ByteArray(5000) { 3 }))
    private val shrunkJson = DesignJson.toJson(photoDesign.copy(background = photoDesign.background.copy(photoJpeg = ByteArray(40) { 3 })))
    private val shrink: (DesignSettings) -> String? = { d -> DesignJson.toJson(d.copy(background = d.background.copy(photoJpeg = d.background.photoJpeg?.copyOf(40)))) }

    @Test fun aPhotoDesignEqualsItsOwnShrunkCloudCopyAndIsNotCloned() {
        val r = RemoteDesign("1", "Photo", shrunkJson, 5_000, DesignState.LIVE, 1)
        val l = local("1", photoDesign, 9_000, null, SyncMark.DIRTY, name = "Photo")
        assertEquals(listOf(SyncAction.RecordBase("1", r)), planSync(listOf(l), listOf(r), setOf("Photo"), shrink, suffix))
    }

    @Test fun withoutTheShrinkingFunctionThePhotoDesignWouldDiffer() {
        val r = RemoteDesign("1", "Photo", shrunkJson, 5_000, DesignState.LIVE, 1)
        val l = local("1", photoDesign, 9_000, null, SyncMark.DIRTY, name = "Photo")
        assertTrue(planSync(listOf(l), listOf(r), setOf("Photo"), toJson, suffix).single() is SyncAction.KeepBoth)
    }

    @Test fun aLocalDesignThatCannotBeMappedIsNeverEqualToTheCloud() {
        val r = remote("1", a, 5_000)
        val l = local("1", a, 9_000, null, SyncMark.DIRTY)
        assertTrue(planSync(listOf(l), listOf(r), setOf("n"), { null }, suffix).single() is SyncAction.KeepBoth)
    }

    // Plan Review Focus 1: a phone that is still signed in must not re-upload what another phone deleted.
    @Test fun aWholeLibraryOfSyncedUntouchedDesignsThatVanishedFromTheCloudWritesNothingBack() {
        val l = listOf(
            local("1", a, 10, 500, SyncMark.CLEAN, name = "One"),
            local("2", b, 20, 600, SyncMark.CLEAN, name = "Two"),
            local("3", a, 30, 700, SyncMark.CLEAN, name = "Three"),
        )
        assertEquals(emptyList<SyncAction>(), plan(l, emptyList()))
    }

    @Test fun afterTheCloudWasWipedOnlyTheDesignEditedSinceIsPushedAndDeletionsAreForgotten() {
        val l = listOf(
            local("1", a, 10, 500, SyncMark.CLEAN, name = "One"),
            local("2", b, 20, 600, SyncMark.DIRTY, name = "Two"),
            local("3", null, 30, 700, SyncMark.DIRTY, EntryState.TOMBSTONE, name = "Three"),
        )
        assertEquals(listOf(SyncAction.PushLive("2"), SyncAction.Drop("3")), plan(l, emptyList()))
    }

    @Test fun aNeverSyncedCleanDesignIsStillPushed() {
        // CLEAN with no base cannot normally happen, but it must never be mistaken for "removed in the cloud".
        assertEquals(listOf(SyncAction.PushLive("1")), plan(listOf(local("1", a, 10, null, SyncMark.CLEAN)), emptyList()))
    }

    @Test fun twoKeepBothCopiesGetDifferentNames() {
        val l = listOf(
            local("1", a, 9_000, null, SyncMark.DIRTY, name = "Night"),
            local("2", a, 9_000, null, SyncMark.DIRTY, name = "Night (Pixel 8)"),
        )
        val r = listOf(remote("1", b, 5_000, name = "Night"), remote("2", b, 5_000, name = "Night (Pixel 8)"))
        val names = planSync(l, r, setOf("Night", "Night (Pixel 8)"), toJson, suffix).map { (it as SyncAction.KeepBoth).copyName }
        assertEquals(2, names.toSet().size)
        assertTrue(names.none { it == "Night" || it == "Night (Pixel 8)" })
    }

    @Test fun aRenameIsATombstoneForTheOldIdPlusANewDocument() {
        val l = listOf(
            local("old", null, 9_000, 500, SyncMark.DIRTY, EntryState.TOMBSTONE, name = "Old"),
            local("new", a, 9_000, null, SyncMark.DIRTY, name = "New"),
        )
        assertEquals(
            listOf(SyncAction.PushTombstone("old"), SyncAction.PushLive("new")),
            plan(l, listOf(remote("old", a, 500, name = "Old"))),
        )
    }

    @Test fun anEditElsewhereAfterOurTombstoneWasPlannedOnlyRevivesWhenNewer() {
        // Equal timestamps: ties go to the cloud, so the design comes back rather than silently disappearing.
        val r = remote("1", b, 9_000)
        val l = local("1", null, modified = 9_000, base = 500, mark = SyncMark.DIRTY, state = EntryState.TOMBSTONE)
        assertEquals(listOf(SyncAction.ApplyRemoteLive("1", r)), plan(listOf(l), listOf(r)))
    }

    @Test fun uniqueNameCountsUpFromTwo() {
        assertEquals("A", uniqueName("A", emptySet()))
        assertEquals("A 2", uniqueName("A", setOf("A")))
        assertEquals("A 3", uniqueName("A", setOf("A", "A 2")))
    }

    // ---- names are compared the way designId makes ids: trimmed and NFC -----------------------------------------------

    @Test fun aCloudNameWithATrailingSpaceStillBlocksTheCopyName() {
        val rNight = remote("1", b, 5_000, name = "Night")
        val rSpaced = remote("2", b, 5_000, name = "Night (Pixel 8) ") // same document id as "Night (Pixel 8)"
        val l = local("1", a, 9_000, null, SyncMark.DIRTY, name = "Night")
        val copy = (planSync(listOf(l), listOf(rNight, rSpaced), setOf("Night"), toJson, suffix).first { it.id == "1" } as SyncAction.KeepBoth).copyName
        assertEquals("Night (Pixel 8) 2", copy)
        assertTrue(designId(copy) != designId("Night (Pixel 8) "))
    }

    @Test fun uniqueNameComparesCanonicalNames() {
        assertEquals("Café 2", uniqueName("Café", setOf("Café")))   // decomposed vs composed é
        assertEquals("A 2", uniqueName("A", setOf(" A ")))
        assertEquals("A 3", uniqueName("A", setOf("A", " A 2")))
    }

    @Test fun canonicalDesignNameIsWhatDesignIdHashes() {
        assertEquals("Café", canonicalDesignName("  Café "))
        assertEquals(designId("Café "), designId("Café"))
    }

    @Test fun designIdIsStableTrimmedAndFirestoreSafe() {
        assertEquals(designId("Night"), designId("  Night "))
        assertEquals(designId("café"), designId("café"))
        assertTrue(designId("Night").matches(Regex("[0-9a-f]{32}")))
        assertTrue(designId("a/b") != designId("a"))
    }
}
